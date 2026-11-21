#!/usr/bin/env bash
# Fast development builds: Kotlin/Native *debug* binary (no LTO) and an optional
# file watcher that rebuilds and restarts the shell on source changes.
#
#   scripts/dev.sh                 # build + run once, log mirrored to the terminal
#   scripts/dev.sh --watch         # rebuild + restart on every change
#   scripts/dev.sh --no-logs       # keep the log in the file only
#   KN_SHELL_MODE=panel scripts/dev.sh --watch
#
# The shell runs in its own process group with its stdout/stderr in
# shell-app/build/dev.log, which is also streamed to the terminal by default (--no-logs
# silences it). The log stays the source of truth because every restart truncates it and
# --watch keeps exactly one `tail` alive, so Ctrl+C stops the app, the watcher and the
# follower without leaving orphans.
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ -f .tools/env.sh ]]; then
    # shellcheck disable=SC1091
    source .tools/env.sh
fi

BIN=shell-app/build/bin/linuxX64/debugExecutable/shell-app.kexe
LOG=shell-app/build/dev.log
MARK=shell-app/build/.dev-mark
mkdir -p shell-app/build

# The Kotlin/Native compiler writes its temporary files into $TMPDIR, and on this machine
# /tmp is a small tmpfs: when it fills up the build dies with "Disk quota exceeded" from
# deep inside the compiler, which reads like a project error. Keep the temp dir inside the
# repository unless the caller explicitly chose one.
if [[ -z "${TMPDIR:-}" ]]; then
    export TMPDIR="$PWD/build/tmp"
    mkdir -p "$TMPDIR"
    export GRADLE_OPTS="${GRADLE_OPTS:-} -Djava.io.tmpdir=$TMPDIR"
fi
CHILD_PID=""
TAIL_PID=""
WATCH=0
# Streaming is the default: the app logs were invisible in the dev loop otherwise.
LOGS=1

for arg in "$@"; do
    case "$arg" in
        --watch) WATCH=1 ;;
        --logs|--follow) LOGS=1 ;;
        --no-logs|--quiet) LOGS=0 ;;
    esac
done

stop_tail() {
    [[ -z "$TAIL_PID" ]] && return
    kill "$TAIL_PID" 2>/dev/null || true
    wait "$TAIL_PID" 2>/dev/null || true
    TAIL_PID=""
}

cleanup() {
    local rc=$?
    trap - INT TERM EXIT
    rm -f "$MARK"
    stop_tail
    if [[ -n "$CHILD_PID" ]] && kill -0 "$CHILD_PID" 2>/dev/null; then
        kill -TERM -- "-$CHILD_PID" 2>/dev/null || kill -TERM "$CHILD_PID" 2>/dev/null
        for _ in $(seq 1 20); do
            kill -0 "$CHILD_PID" 2>/dev/null || break
            sleep 0.05
        done
        kill -KILL -- "-$CHILD_PID" 2>/dev/null || true
    fi
    echo "[dev] stopped"
    exit "$rc"
}
trap cleanup INT TERM EXIT

stop_app() {
    [[ -z "$CHILD_PID" ]] && return
    kill -TERM -- "-$CHILD_PID" 2>/dev/null || kill -TERM "$CHILD_PID" 2>/dev/null
    for _ in $(seq 1 20); do
        kill -0 "$CHILD_PID" 2>/dev/null || break
        sleep 0.05
    done
    kill -KILL -- "-$CHILD_PID" 2>/dev/null || true
    CHILD_PID=""
    stop_tail
}

start_app() {
    mkdir -p "$(dirname "$LOG")"
    stop_tail
    : >"$LOG"
    # stdout/stderr always land in the log: the app is restarted dozens of times under
    # --watch and a terminal-only pipe would lose every earlier run. `tail` mirrors the
    # file so the dev loop sees the log live.
    setsid "$BIN" >>"$LOG" 2>&1 &
    CHILD_PID=$!
    if [[ $LOGS -eq 1 ]]; then
        # `-F` (not `-f`): the next restart truncates the log (`: >"$LOG"`), after which
        # plain `-f` would keep following a file whose buffer is now empty.
        tail -n +1 -F "$LOG" &
        TAIL_PID=$!
    fi
    echo "[dev] running (pid $CHILD_PID, mode=${KN_SHELL_MODE:-windowed}), log: $LOG"
}

build() {
    # Cargo is incremental and Gradle already declares rust-core's `src/` + Cargo.{toml,lock}
    # as inputs (see rust-core/build.gradle.kts), so an edited .rs file invalidates the
    # cargo staticlib and the cinterop/link tasks correctly — no manual clean needed.
    # The old `rm -rf rust-core/build shell-app/build` wiped the Kotlin/Native
    # incremental cache on *every* Rust change, forcing a full recompile of the Kotlin
    # modules and turning the dev loop into a ~minutes-long rebuild. We no longer do that.
    ./gradlew :shell-app:linkDebugExecutableLinuxX64 --console=plain
}

# Mark BEFORE the build: a file saved while the compiler runs must stay newer than the
# mark, otherwise it is swallowed and never triggers anything (the old post-build
# `touch` did exactly that during a ~40 s link).
touch "$MARK"
build || { echo "[dev] build failed"; exit 1; }
start_app

if [[ $WATCH -eq 0 ]]; then
    wait "$CHILD_PID"
    RC=$?
    # Give the follower a moment to print the app's last lines before the EXIT trap
    # kills it, so a crash trace is not cut off mid-way.
    if [[ $LOGS -eq 1 ]]; then sleep 0.3; fi
    exit "$RC"
fi

echo "[dev] watching framework/src, shell-app/src, rust-core/src, palette-core/src (Ctrl+C to stop)"
while true; do
    sleep 1
    CHANGED=0
    # `*.c` matters: the box layout manager is C and it is the file edited most often while
    # working on layout semantics. Gradle already tracks it as an input (rust-core/build.gradle.kts);
    # without it here the watcher simply never notices.
    # `-print -quit` instead of `| grep -q .`: with `set -o pipefail`, `grep -q` exiting on
    # the first match gives `find` a SIGPIPE, and a `find` error (a missing source dir, as
    # when a module has not been created yet) makes the whole pipeline non-zero — so the
    # watcher silently ignored every edit. `-print -quit` also stops at the first match.
    if [[ -n "$(find framework/src shell-app/src rust-core/src palette-core/src \
        \( -name '*.kt' -o -name '*.rs' -o -name '*.c' -o -name '*.h' \) \
        -newer "$MARK" -print -quit 2>/dev/null)" ]]; then
        CHANGED=1
    fi
    # Crash/restart detection: only meaningful while a child actually exists. With an
    # empty CHILD_PID (after stop_app, or when a build failed and nothing ever started)
    # `kill -0 ""` fails and used to force CHANGED=1 on *every* tick — an endless
    # rebuild loop after any build failure. A dead-but-unwaited child lingers as a
    # zombie, which `kill -0` still reports alive, so check the process state too.
    if [[ -n "$CHILD_PID" ]]; then
        if ! kill -0 "$CHILD_PID" 2>/dev/null || [[ "$(ps -o stat= -p "$CHILD_PID" 2>/dev/null)" == Z* ]]; then
            CHANGED=1
        fi
    fi
    if [[ $CHANGED -eq 1 ]]; then
        stop_app
        # Stamp before building (see startup) and only once — re-touching after a *failed*
        # build made the mark newer than every edit, so the retry could only ever start from
        # the next save.
        touch "$MARK"
        if build; then
            start_app
        else
            echo "[dev] build failed — waiting for the next change"
        fi
    fi
done
