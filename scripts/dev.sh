#!/usr/bin/env bash
# Fast development builds: Kotlin/Native *debug* binary (no LTO) and an optional
# file watcher that rebuilds and restarts the shell on source changes.
#
#   scripts/dev.sh                 # build + run once (windowed by default)
#   scripts/dev.sh --watch         # rebuild + restart on every change
#   scripts/dev.sh --logs          # stream the shell's log to the terminal
#   KN_SHELL_MODE=panel scripts/dev.sh --watch
#
# The shell runs in its own process group with its stdout/stderr in
# shell-app/build/dev.log (use --logs to follow it), so Ctrl+C stops both the app and the
# watcher without leaving orphans or log spam in the terminal.
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ -f .tools/env.sh ]]; then
    # shellcheck disable=SC1091
    source .tools/env.sh
fi

BIN=shell-app/build/bin/linuxX64/debugExecutable/shell-app.kexe
LOG=shell-app/build/dev.log
MARK=$(mktemp -t kn-shell-dev.XXXXXX)
CHILD_PID=""
WATCH=0
LOGS=0

for arg in "$@"; do
    case "$arg" in
        --watch) WATCH=1 ;;
        --logs) LOGS=1 ;;
    esac
done

cleanup() {
    trap - INT TERM EXIT
    rm -f "$MARK"
    if [[ -n "$CHILD_PID" ]] && kill -0 "$CHILD_PID" 2>/dev/null; then
        kill -TERM -- "-$CHILD_PID" 2>/dev/null || kill -TERM "$CHILD_PID" 2>/dev/null
        for _ in $(seq 1 20); do
            kill -0 "$CHILD_PID" 2>/dev/null || break
            sleep 0.05
        done
        kill -KILL -- "-$CHILD_PID" 2>/dev/null || true
    fi
    echo "[dev] stopped"
    exit 0
}
trap cleanup INT TERM EXIT

needs_rust_clean() {
    [[ ! -x "$BIN" ]] && return 0
    find rust-core/src -name '*.rs' -newer "$BIN" | grep -q . && return 0
    [[ rust-core/Cargo.toml -nt "$BIN" ]] && return 0
    return 1
}

stop_app() {
    [[ -z "$CHILD_PID" ]] && return
    kill -TERM -- "-$CHILD_PID" 2>/dev/null || kill -TERM "$CHILD_PID" 2>/dev/null
    for _ in $(seq 1 20); do
        kill -0 "$CHILD_PID" 2>/dev/null || break
        sleep 0.05
    done
    kill -KILL -- "-$CHILD_PID" 2>/dev/null || true
    CHILD_PID=""
}

start_app() {
    mkdir -p "$(dirname "$LOG")"
    : >"$LOG"
    if [[ $LOGS -eq 1 ]]; then
        setsid "$BIN" >>"$LOG" 2>&1 &
    else
        setsid "$BIN" >>"$LOG" 2>&1 &
    fi
    CHILD_PID=$!
    if [[ $LOGS -eq 1 ]]; then
        tail -n +1 -f "$LOG" &
    fi
    echo "[dev] running (pid $CHILD_PID, mode=${KN_SHELL_MODE:-windowed}), log: $LOG"
}

build() {
    if needs_rust_clean; then
        # Incremental links were observed to reuse a stale Rust static library; a clean
        # rebuild of these directories guarantees the binary matches the sources.
        echo "[dev] Rust changed (or binary missing) — cleaning native build dirs"
        rm -rf rust-core/build shell-app/build
    fi
    ./gradlew :shell-app:linkDebugExecutableLinuxX64 --console=plain
}

build || { echo "[dev] build failed"; exit 1; }
touch "$MARK"
start_app

if [[ $WATCH -eq 0 ]]; then
    wait "$CHILD_PID"
    exit $?
fi

echo "[dev] watching shell-ui/src, shell-app/src, rust-core/src (Ctrl+C to stop)"
while true; do
    sleep 1
    CHANGED=0
    if find shell-ui/src shell-app/src rust-core/src \( -name '*.kt' -o -name '*.rs' \) -newer "$MARK" | grep -q .; then
        CHANGED=1
    fi
    if ! kill -0 "$CHILD_PID" 2>/dev/null; then CHANGED=1; fi
    if [[ $CHANGED -eq 1 ]]; then
        stop_app
        if build; then
            touch "$MARK"
            start_app
        else
            echo "[dev] build failed — waiting for the next change"
            touch "$MARK"
        fi
    fi
done
