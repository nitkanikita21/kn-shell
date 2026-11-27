#!/usr/bin/env bash
# Builds the shell and then execs the Kotlin/Native binary directly.
#
# `./gradlew run` executes the binary as a child of the Gradle daemon (java); this script
# keeps the shell a standalone process (the Gradle daemon only builds and exits).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -f .tools/env.sh ]]; then
    # shellcheck disable=SC1091
    source .tools/env.sh
fi

./gradlew :demo:shell-app:linkReleaseExecutableLinuxX64 --console=plain
exec demo/shell-app/build/bin/linuxX64/releaseExecutable/shell-app.kexe "$@"
