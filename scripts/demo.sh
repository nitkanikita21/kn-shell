#!/usr/bin/env bash
# Builds the KN Shell (Rust + UniFFI + Kotlin/Native) and launches the panel.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -f .tools/env.sh ]]; then
    # Sandbox/local toolchains (JDK 21, rustup, Gradle and Kotlin/Native caches in-repo).
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
exec ./gradlew run --console=plain
