#!/usr/bin/env bash
# Builds the KN Shell (Rust + UniFFI + Kotlin/Native) and launches the panel as a standalone
# process (not as a child of the Gradle daemon).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -f .tools/env.sh ]]; then
    # shellcheck disable=SC1091
    source .tools/env.sh
fi

if [[ -z "${WAYLAND_DISPLAY:-}" && -z "${DISPLAY:-}" ]]; then
    echo "error: no Wayland/X11 display detected" >&2
    exit 1
fi

echo "==> building (Rust release + UniFFI bindings + Kotlin/Native link)"
./gradlew build --console=plain

echo "==> launching KN Shell"
exec shell-app/build/bin/linuxX64/releaseExecutable/shell-app.kexe
