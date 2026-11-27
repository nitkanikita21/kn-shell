#!/usr/bin/env python3
"""Checks what assistive technology actually sees of the UI kit.

Starts the kit's atlas on a private display and a private D-Bus session, opens stories through the
inspector socket and reads them back over AT-SPI, the interface a screen reader such as Orca uses.
The inspector reports what the kit declares; this reports what arrives.

    ./gradlew :demo:shell-ui-kit:linkAtlasDebugExecutableLinuxX64 :headless-compositor:cargoBuild
    scripts/a11y-check.py

Needs python-gobject with the Atspi typelib, at-spi2-core and dbus-run-session.
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMPOSITOR = os.path.join(ROOT, "headless-compositor/target/release/headless-compositor")
ATLAS = os.path.join(ROOT, "demo/shell-ui-kit/build/bin/linuxX64/atlasDebugExecutable/atlas.kexe")

# Per story: the objects a screen reader must find, as (role, name, states it must have).
CHECKS = {
    "UButton/Accessibility": [
        ("button", "Зберегти", []),
        ("button", "Видалити", []),
        ("button", "Зачекайте", ["busy"]),
    ],
    "UTabs/Accessibility": [
        ("page tab", "Загальні", ["selected"]),
        ("page tab", "Звук", []),
        ("page tab list", "Налаштування", []),
    ],
    "UCheckbox/Accessibility": [
        ("check box", "Увімкнено", ["checked"]),
    ],
    "USwitch/Accessibility": [
        ("switch", "Wi-Fi", ["checked"]),
    ],
    "URadioGroup/Accessibility": [
        ("radio button", "Згори", ["checked"]),
        ("radio button", "Знизу", []),
    ],
    "USlider/Accessibility": [
        ("slider", "Гучність", []),
    ],
    "USelect/Accessibility": [
        ("combo box", "Шрифт", []),
    ],
    "UModal/Open": [
        ("dialog", "Перейменувати простір", ["modal"]),
        ("button", "Закрити", []),
        ("button", "Скасувати", []),
    ],
}


def fail(message):
    print(f"a11y-check: {message}", file=sys.stderr)
    sys.exit(2)


class Inspector:
    """The line protocol of `atlas --inspect`."""

    def __init__(self, path):
        self.socket = socket.socket(socket.AF_UNIX)
        self.socket.connect(path)
        self.lines = self.socket.makefile("r", encoding="utf-8")
        self.next_id = 0

    def call(self, tool, **arguments):
        self.next_id += 1
        request = {"id": self.next_id, "method": "tools/call", "params": {"name": tool, "arguments": arguments}}
        self.socket.sendall((json.dumps(request) + "\n").encode())
        response = json.loads(self.lines.readline())
        result = response.get("result")
        if result is None or result.get("isError"):
            fail(f"{tool} {arguments}: {response}")
        return result


def objects(Atspi, pid):
    """Every accessible object of the process [pid], as (role, name, states)."""
    desktop = Atspi.get_desktop(0)
    found = []

    def walk(node, depth):
        if node is None or depth > 60:
            return
        node.clear_cache()
        states = node.get_state_set()
        names = [name for name in ("selected", "checked", "busy", "modal", "expanded", "focused", "enabled")
                 if states.contains(getattr(Atspi.StateType, name.upper()))]
        found.append((node.get_role_name(), node.get_name(), names))
        for index in range(node.get_child_count()):
            walk(node.get_child_at_index(index), depth + 1)

    for index in range(desktop.get_child_count()):
        app = desktop.get_child_at_index(index)
        if app is not None and app.get_process_id() == pid:
            walk(app, 0)
    return found


def start_accessibility(log):
    """Brings up the accessibility bus of the private session, and its registry.

    The bus launcher may leave the registry to systemd, which this session does not have.
    """
    from gi.repository import Gio, GLib

    session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    session.call_sync("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus", "GetAddress", None,
                      GLib.VariantType("(s)"), Gio.DBusCallFlags.NONE, 10000, None)
    for path in ("/usr/lib/at-spi2-registryd", "/usr/libexec/at-spi2-registryd",
                 "/usr/lib/at-spi2-core/at-spi2-registryd"):
        if os.path.exists(path):
            registry = subprocess.Popen([path, "--use-gnome-session=false"], stdout=log, stderr=log)
            time.sleep(0.5)
            return registry
    fail("at-spi2-registryd is not installed")


def inner():
    import gi

    gi.require_version("Atspi", "2.0")
    from gi.repository import Atspi

    workdir = tempfile.mkdtemp(prefix="a11y-check-")
    path = os.path.join(workdir, "inspect.sock")
    log = open(os.path.join(workdir, "atlas.log"), "w")
    registry = start_accessibility(log)
    atlas = subprocess.Popen(
        [ATLAS, f"--inspect={path}"],
        env={**os.environ, "GTK_A11Y": "atspi", "GSETTINGS_BACKEND": "memory", "GDK_DEBUG": "no-portals"},
        stdout=log,
        stderr=log,
    )
    failures = 0
    try:
        deadline = time.time() + 20
        while not os.path.exists(path):
            if atlas.poll() is not None or time.time() > deadline:
                fail(f"the atlas did not start, see {log.name}")
            time.sleep(0.1)
        time.sleep(0.5)
        inspector = Inspector(path)
        for story, expected in CHECKS.items():
            inspector.call("select_story", story=story)
            missing = list(expected)
            seen = []
            # AT-SPI learns about new widgets a moment after they appear.
            for _ in range(20):
                time.sleep(0.25)
                seen = objects(Atspi, atlas.pid)
                missing = [
                    (role, name, states) for role, name, states in expected
                    if not any(r == role and n == name and set(states) <= set(s) for r, n, s in seen)
                ]
                if not missing:
                    break
            print(f"{'ok  ' if not missing else 'FAIL'} {story}: {len(expected) - len(missing)}/{len(expected)}")
            for role, name, states in missing:
                failures += 1
                similar = [(r, n, s) for r, n, s in seen if r == role or n == name]
                print(f"     missing {role} \"{name}\" {states}; close: {similar[:8]}")
        if not seen:
            fail("AT-SPI shows nothing of the atlas")
    finally:
        atlas.kill()
        atlas.wait()
        registry.kill()
        registry.wait()
        log.close()
        # GTK complains in its log when it is told something it cannot pass on.
        with open(log.name, encoding="utf-8", errors="replace") as written:
            complaints = sorted({line.split(": ", 2)[-1].strip() for line in written if "CRITICAL" in line})
        for complaint in complaints:
            failures += 1
            print(f"FAIL GTK: {complaint}")
        if not failures:
            shutil.rmtree(workdir, ignore_errors=True)
        else:
            print(f"atlas log: {log.name}")
    sys.exit(1 if failures else 0)


def main():
    if "--inner" in sys.argv:
        inner()
    for binary in (COMPOSITOR, ATLAS):
        if not os.path.exists(binary):
            fail(f"{binary} is missing; build it first (see --help in the file header)")
    if shutil.which("dbus-run-session") is None:
        fail("dbus-run-session is not installed")
    # A runtime directory of its own: the accessibility bus puts its socket there, and in the
    # user's directory it would replace the socket of the desktop session.
    runtime = tempfile.mkdtemp(prefix="a11y-check-runtime-")
    command = ["dbus-run-session", "--", COMPOSITOR, "--", sys.executable, os.path.abspath(__file__), "--inner"]
    try:
        sys.exit(subprocess.call(command, env={**os.environ, "XDG_RUNTIME_DIR": runtime}))
    finally:
        shutil.rmtree(runtime, ignore_errors=True)


if __name__ == "__main__":
    main()
