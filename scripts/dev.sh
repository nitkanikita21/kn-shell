#!/usr/bin/env bash
# Fast development builds: Kotlin/Native *debug* binary (no LTO) and an optional
# file watcher that rebuilds and restarts the shell on source changes.
#
#   scripts/dev.sh                 # build + run once (windowed by default)
#   scripts/dev.sh --watch         # rebuild + restart on every change
#   KN_SHELL_MODE=panel scripts/dev.sh --watch
#
# Release builds are ~7x slower (whole-program LTO); use scripts/run.sh for those.
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ -f .tools/env.sh ]]; then
    # shellcheck disable=SC1091
    source .tools/env.sh
fi

BIN=shell-app/build/bin/linuxX64/debugExecutable/shell-app.kexe
MARK=$(mktemp -t kn-shell-dev.XXXXXX)
trap 'rm -f "$MARK"' EXIT

build() {
    # Incremental links have been observed to reuse a stale Rust static library from
    # rust-core/build / shell-app/build (an edited .rs file silently did not reach the
    # binary). Kotlin-only edits are fine; a Rust change forces a clean rebuild of the
    # native artefacts (~15-20 s with the debug binary).
    if [[ ! -x "$BIN" ]] || find rust-core/src -name '*.rs' -newer "$BIN" | grep -q . || [[ rust-core/Cargo.toml -nt "$BIN" ]]; then
        echo "[dev] Rust changed (or binary missing) — cleaning native build dirs"
        rm -rf rust-core/build shell-app/build
    fi
    ./gradlew :shell-app:linkDebugExecutableLinuxX64 --console=plain
}

run() {
    KN_SHELL_MODE="${KN_SHELL_MODE:-windowed}" "$BIN" "$@" &
    PID=$!
}

build || { echo "[dev] build failed"; exit 1; }
touch "$MARK"

if [[ "${1:-}" != "--watch" ]]; then
    run "${@:2}"
    wait $PID
    exit $?
fi

echo "[dev] watching shell-ui/src, shell-app/src, rust-core/src (Ctrl+C to stop)"
run "${@:2}"

while true; doк
    sleep 1
    CHANGED=0
    if find shell-ui/src shell-app/src rust-core/src \( -name '*.kt' -o -name '*.rs' \) -newer "$MARK" | grep -q .; then
        CHANGED=1
    fi
    if ! kill -0 $PID 2>/dev/null; then CHANGED=1; fi
    if [[ $CHANGED -eq 1 ]]; then
        kill $PID 2>/dev/null; wait $PID 2>/dev/null
        if build; then
            touch "$MARK"
            run "${@:2}"
        else
            echo "[dev] build failed — waiting for the next change"
            touch "$MARK"
        fi
    fi
done
