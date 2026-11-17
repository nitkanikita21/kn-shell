#!/usr/bin/env bash
# Phase 4 acceptance benchmark for the Compose-owned animated-size work
# (docs/tasks/compose-owned-animated-size.md, §5 "Плавність (вимірювано)" / "Ефективність" /
# "Пам'ять" and §7 risks).
#
# The script measures a *running* shell; it never builds anything (no Gradle) and never
# modifies the repository. By default it launches the existing debug/release binary in
# windowed mode with GDK_DEBUG=frames, measures, and kills it again. If a shell is already
# running, attach with --pid (plus --log if it was started with GDK_DEBUG=frames).
#
# What it checks (PASS/FAIL unless marked INFO/SKIP):
#   frames.pacing          tick interval ≈ monitor period (mean/std of GDK frame intervals)
#   frames.gaps            no gap > 2 periods while an animation is running
#   visual.width-monotonic grim screenshot series: width deltas are monotonic (OutCubic)
#   idle.ctxt              voluntary_ctxt_switches/s in the idle UI (target ≈ 0, legacy ≈ 62.5)
#   cpu.animation          CPU% while animating (compare with --baseline: ≤ +10 %)
#   memory.rss-drift       RSS drift over N toggles (default short smoke; < 5 MB)
#   recomp.count           recomposition counter during an animation == 0
#   source.*               migration gates: no legacy Behavior/16 ms clock/C-side animation
#
# Not covered here (manual scenarios from §5): child insertion during an animation,
# nested animated containers, RTL/fractional scaling, spring overshoot, popover lifecycle.
#
# Usage: scripts/anim-bench.sh [options]    (see --help)
#
# Exit codes:
#   0  every measured check passed (or was skipped for a non-critical reason)
#   1  at least one check failed (or --strict and something was skipped)
#   2  critical dependency missing / shell could not be started (nothing was measured)
#
# Author: Phase 4; read-only with respect to the repository.
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ -f .tools/env.sh ]]; then
    # shellcheck disable=SC1091
    source .tools/env.sh
fi

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
DURATION=6              # frame measurement window, seconds
IDLE_SECS=10            # idle context-switch window, seconds
IDLE_MAX_PER_SEC=3      # PASS limit for voluntary_ctxt_switches/s (legacy clock ≈ 62.5)
CPU_WINDOW=4            # CPU measurement window, seconds
TOGGLES=30              # RSS drift toggle cycles (short smoke; 1800 @1s = 30 min)
TOGGLE_DELAY=0.12       # pause between RSS toggles, seconds
RSS_MAX_MB=5            # PASS limit for RSS drift
SETTLE=2.0              # settle time after a trigger / before idle, seconds
TRIGGER_INTERVAL=0.30   # auto-trigger period for the frame/CPU tests
SHOTS=24                # max screenshots in a series (0 disables)
SHOT_WINDOW=2           # max seconds for the screenshot series
SHOT_THRESHOLD=12       # per-channel pixel delta that counts as "changed"
PX_TOLERANCE=1          # width monotonicity tolerance, px
PLATEAU_MAX=2           # allowed consecutive equal widths (grim ≈ 25 ms, animation ≈ 180 ms)
MIN_DELTA=4             # minimum width change for the screenshot test to be meaningful
FRAME_STD_MAX=1.0       # PASS limit for frame interval std, ms
BAR_HEIGHT=40           # auto screenshot region height for panel mode
HZ=""                   # expected refresh rate; empty = auto-detect
MODE=windowed           # KN_SHELL_MODE for the launched app
BINARY=""
PID=""
LOG=""
TRIGGER=auto            # auto | hypr | none (--trigger-cmd overrides)
TRIGGER_CMD=""
SHOT_REGION=""
SHOT_REGION_EXPLICIT=0
BASELINE_IN=""
BASELINE_OUT=""
RECOMP_FILE=""
SOURCE_CHECK=1
STRICT=0
KEEP=0
VERBOSE=0

WORK=""
APP_PID=""
OWN_APP=0
CRITICAL=0
TRIGGER_KIND=none
TRIGGER_PID=""
TRIGGER_TOGGLE=0
ORIG_WS=""
OTHER_WS=""
EXIT_CODE=0
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
INFO_COUNT=0
RESULTS=()

# ---------------------------------------------------------------------------
# Presentation
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
    C_RED=$'\033[31m' C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m'
    C_BLUE=$'\033[34m' C_DIM=$'\033[2m' C_RESET=$'\033[0m'
else
    C_RED="" C_GREEN="" C_YELLOW="" C_BLUE="" C_DIM="" C_RESET=""
fi

say()  { printf '%s\n' "$*"; }
info() { printf '%s[info]%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
err()  { printf '%s[error]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 2; }

usage() {
    cat <<'EOF'
Usage: scripts/anim-bench.sh [options]

Measures the Compose-owned size animation and the lazy GTK frame clock
(docs/tasks/compose-owned-animated-size.md §5). Read-only: it never runs Gradle.

Shell selection
  --pid PID             attach to a running shell instead of launching one
  --log FILE            app log with GDK_DEBUG=frames output (needed with --pid
                        for frame metrics; otherwise only idle/CPU/RSS work)
  --binary PATH         binary to launch (default: release, else debug kexe)
  --mode panel|windowed KN_SHELL_MODE for the launched app (default: windowed)

Measurement windows
  --duration SEC        frame measurement window (default: 6)
  --idle SEC            idle context-switch window (default: 10)
  --idle-max N          PASS limit for voluntary_ctxt_switches/s (default: 3)
  --settle SEC          settle time before measuring (default: 1.6)
  --hz HZ               expected refresh rate (default: auto from GTK
                        refresh_interval, then Hyprland, then 60)
  --frame-std-max MS    PASS limit for frame-interval std (default: 1.0)

Animation trigger (needed for frames/CPU/RSS/screenshots)
  --trigger auto|hypr|none   auto = Hyprland workspace switching if available
  --trigger-cmd CMD     custom command that changes an animated widget
                        (e.g. "playerctl play-pause"); overrides --trigger
  --trigger-interval S  auto-trigger period (default: 0.30)
  --no-trigger          never trigger; animation checks become SKIP

Screenshots (visual width monotonicity)
  --shots N             max frames in the series (default: 24; 0 disables)
  --shot-region GEOM    grim region, e.g. "24,8 400x40" (strongly recommended)
  --shot-window SEC     max capture time (default: 2)
  --threshold N         pixel delta counted as changed (default: 12)
  --px-tolerance N      width tolerance, px (default: 1)
  --plateau N           allowed consecutive equal widths (default: 2)
  --min-delta N         minimum width change, px (default: 4)

Memory / CPU
  --toggles N           RSS toggle cycles (default: 30; 1800 @1s ≈ 30 min)
  --toggle-delay SEC    pause between toggles (default: 0.12)
  --rss-max-mb MB       PASS limit for RSS drift (default: 5)
  --cpu-window SEC      CPU measurement window (default: 4)

Recomposition counter
  --recomp-file FILE    file containing the recomposition count (0 expected);
                        otherwise the script looks for "[recomp] count=N" in the
                        app log. See the TODO in measure_recomp().

Baselines (for "CPU ≤ old version +10 %")
  --save-baseline FILE  write measured values (explicit path, not in the repo)
  --baseline FILE       compare CPU against a saved baseline (limit: +10 %)

Migration gates / output
  --no-source-check     skip the read-only source greps
  --strict              treat SKIP as failure
  --keep                keep artifacts (app log, frames, screenshots)
  --verbose             show full source-gate details
  -h, --help            this text

Examples
  scripts/anim-bench.sh
  scripts/anim-bench.sh --mode panel --duration 8 --idle 10 --keep
  scripts/anim-bench.sh --pid "$(pgrep -f shell-app.kexe | head -1)" \
      --log /tmp/kn-shell.log
  scripts/anim-bench.sh --trigger-cmd 'playerctl play-pause' \
      --shot-region '24,8 400x40' --shots 30
  scripts/anim-bench.sh --toggles 1800 --toggle-delay 1   # 30-minute RSS drift
  scripts/anim-bench.sh --save-baseline ~/.cache/kn-anim-before.txt   # old binary
  scripts/anim-bench.sh --baseline ~/.cache/kn-anim-before.txt        # new binary

Environment
  GDK_DEBUG=frames is appended (not replaced) when the script launches the app.
  Known ambient timers in shell-app (not framework): Clock updates 1/s; PlayerChip's
  sheen loop runs every 33 ms even when media is stopped. The latter keeps idle
  wakeups at ~30/s and must be gated on media.playing to reach the framework target.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
need_value() {
    [[ $# -ge 2 ]] || die "option $1 requires a value (see --help)"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --pid) need_value "$@"; PID=$2; shift 2 ;;
        --log) need_value "$@"; LOG=$2; shift 2 ;;
        --binary) need_value "$@"; BINARY=$2; shift 2 ;;
        --mode) need_value "$@"; MODE=$2; shift 2 ;;
        --duration) need_value "$@"; DURATION=$2; shift 2 ;;
        --idle) need_value "$@"; IDLE_SECS=$2; shift 2 ;;
        --idle-max) need_value "$@"; IDLE_MAX_PER_SEC=$2; shift 2 ;;
        --settle) need_value "$@"; SETTLE=$2; shift 2 ;;
        --hz) need_value "$@"; HZ=$2; shift 2 ;;
        --frame-std-max) need_value "$@"; FRAME_STD_MAX=$2; shift 2 ;;
        --trigger) need_value "$@"; TRIGGER=$2; shift 2 ;;
        --trigger-cmd) need_value "$@"; TRIGGER_CMD=$2; shift 2 ;;
        --trigger-interval) need_value "$@"; TRIGGER_INTERVAL=$2; shift 2 ;;
        --no-trigger) TRIGGER=none; shift ;;
        --shots) need_value "$@"; SHOTS=$2; shift 2 ;;
        --shot-region) need_value "$@"; SHOT_REGION=$2; SHOT_REGION_EXPLICIT=1; shift 2 ;;
        --shot-window) need_value "$@"; SHOT_WINDOW=$2; shift 2 ;;
        --threshold) need_value "$@"; SHOT_THRESHOLD=$2; shift 2 ;;
        --px-tolerance) need_value "$@"; PX_TOLERANCE=$2; shift 2 ;;
        --plateau) need_value "$@"; PLATEAU_MAX=$2; shift 2 ;;
        --min-delta) need_value "$@"; MIN_DELTA=$2; shift 2 ;;
        --toggles) need_value "$@"; TOGGLES=$2; shift 2 ;;
        --toggle-delay) need_value "$@"; TOGGLE_DELAY=$2; shift 2 ;;
        --rss-max-mb) need_value "$@"; RSS_MAX_MB=$2; shift 2 ;;
        --cpu-window) need_value "$@"; CPU_WINDOW=$2; shift 2 ;;
        --recomp-file) need_value "$@"; RECOMP_FILE=$2; shift 2 ;;
        --save-baseline) need_value "$@"; BASELINE_OUT=$2; shift 2 ;;
        --baseline) need_value "$@"; BASELINE_IN=$2; shift 2 ;;
        --no-source-check) SOURCE_CHECK=0; shift ;;
        --strict) STRICT=1; shift ;;
        --keep) KEEP=1; shift ;;
        --verbose) VERBOSE=1; shift ;;
        --) shift; break ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done

is_number() { [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]; }
is_int()    { [[ "$1" =~ ^[0-9]+$ ]]; }

for pair in \
    "duration:$DURATION" "idle:$IDLE_SECS" "idle-max:$IDLE_MAX_PER_SEC" \
    "settle:$SETTLE" "frame-std-max:$FRAME_STD_MAX" "trigger-interval:$TRIGGER_INTERVAL" \
    "shots:$SHOTS" "shot-window:$SHOT_WINDOW" "threshold:$SHOT_THRESHOLD" \
    "px-tolerance:$PX_TOLERANCE" "plateau:$PLATEAU_MAX" "min-delta:$MIN_DELTA" \
    "toggles:$TOGGLES" "toggle-delay:$TOGGLE_DELAY" "rss-max-mb:$RSS_MAX_MB" \
    "cpu-window:$CPU_WINDOW" "bar-height:$BAR_HEIGHT"; do
    name=${pair%%:*} value=${pair#*:}
    is_number "$value" || die "--$name must be a number, got '$value'"
done
if ! is_int "$SHOTS" || ! is_int "$TOGGLES" || ! is_int "$PLATEAU_MAX" || ! is_int "$SHOT_WINDOW"; then
    die "--shots, --toggles, --plateau and --shot-window must be integers"
fi
if [[ -n "$HZ" ]] && ! is_number "$HZ"; then die "--hz must be a number"; fi
if [[ -n "$HZ" ]] && ! awk -v hz="$HZ" 'BEGIN { exit !(hz > 0) }'; then die "--hz must be > 0"; fi
case "$MODE" in windowed|panel) ;; *) die "--mode must be windowed or panel" ;; esac
case "$TRIGGER" in auto|hypr|none) ;; *) die "--trigger must be auto, hypr or none" ;; esac
if [[ -n "$PID" ]] && ! is_int "$PID"; then die "--pid must be an integer"; fi
if [[ -n "$PID" ]] && [[ ! -d "/proc/$PID" ]]; then die "process $PID does not exist"; fi
if [[ -n "$BASELINE_IN" ]] && [[ ! -r "$BASELINE_IN" ]]; then die "cannot read --baseline file: $BASELINE_IN"; fi

# ---------------------------------------------------------------------------
# Results bookkeeping
# ---------------------------------------------------------------------------
add_result() {
    local status=$1 name=$2 details=${3:-}
    local colour
    RESULTS+=("$status|$name|$details")
    case "$status" in
        PASS) PASS_COUNT=$((PASS_COUNT + 1)); colour=$C_GREEN ;;
        FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)); colour=$C_RED ;;
        SKIP) SKIP_COUNT=$((SKIP_COUNT + 1)); colour=$C_YELLOW ;;
        INFO) INFO_COUNT=$((INFO_COUNT + 1)); colour=$C_BLUE ;;
        *)    colour="" ;;
    esac
    printf '  %s%-4s%s %-24s %s\n' "$colour" "$status" "$C_RESET" "$name" "$details"
}

print_summary() {
    say ""
    say "================ SUMMARY ================"
    local row status name details colour
    for row in "${RESULTS[@]}"; do
        IFS='|' read -r status name details <<< "$row"
        case "$status" in
            PASS) colour=$C_GREEN ;; FAIL) colour=$C_RED ;;
            SKIP) colour=$C_YELLOW ;; INFO) colour=$C_BLUE ;; *) colour="" ;;
        esac
        printf '%s%-4s%s %-24s %s\n' "$colour" "$status" "$C_RESET" "$name" "$details"
    done
    say "-----------------------------------------"
    printf 'passed=%d failed=%d skipped=%d info=%d\n' \
        "$PASS_COUNT" "$FAIL_COUNT" "$SKIP_COUNT" "$INFO_COUNT"
    if [[ -n "$BASELINE_OUT" ]]; then say "baseline written to: $BASELINE_OUT"; fi
}

float_lt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a < b) }'; }
float_le() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a <= b) }'; }

# ---------------------------------------------------------------------------
# Lifecycle
# ---------------------------------------------------------------------------
cleanup() {
    trap - EXIT INT TERM
    stop_trigger
    if [[ "$OWN_APP" -eq 1 && -n "$APP_PID" ]]; then
        stop_app
    fi
    restore_workspace
    if [[ -n "$WORK" && -d "$WORK" ]]; then
        if [[ "$KEEP" -eq 1 ]]; then
            say ""
            say "artifacts kept in: $WORK"
        else
            rm -rf "$WORK"
        fi
    fi
    exit "$EXIT_CODE"
}
trap cleanup EXIT
trap 'EXIT_CODE=130; exit 130' INT TERM

# ---------------------------------------------------------------------------
# Source gates (read-only greps; no app required)
# ---------------------------------------------------------------------------
FRAMEWORK_KT="framework/src/nativeMain/kotlin"
APP_KT="shell-app/src/nativeMain/kotlin"
C_LAYOUT="rust-core/src/kn_box_layout.c"

gate() {
    local name=$1 details=$2 file=$3
    [[ -n "$details" ]] || details="ok"
    if [[ -n "$file" && -s "$file" ]]; then
        if [[ "$VERBOSE" -eq 1 ]]; then
            say "    source gate $name:"
            sed 's/^/      /' "$file"
        fi
        add_result FAIL "$name" "$(head -3 "$file" | tr '\n' ';') (see $file)"
    else
        add_result PASS "$name" "$details"
    fi
}

run_source_checks() {
    say ""
    say "== source gates (migration status) =="
    if [[ "$SOURCE_CHECK" -eq 0 ]]; then
        add_result SKIP source.gates "disabled with --no-source-check"
        return
    fi

    # 1. Legacy Behavior API must be gone from framework and call-sites.
    # NOTE: `ContentSizeAnimator` is the NEW registry contract (SizeAnimationRegistry),
    # not the removed `SizeAnimator`/`SizeTween` from Behavior.kt — exclude it explicitly.
    local hits="$WORK/source-legacy.txt"
    grep -rnE 'widthBehavior|heightBehavior|sizeBehavior|SizeBehaviorRegistry|BehaviorElement|BehaviorAxis|[^t]SizeAnimator' \
        "$FRAMEWORK_KT" "$APP_KT" >"$hits" 2>/dev/null || true
    # Filter the new ContentSizeAnimator contract out of the SizeAnimator pattern above.
    grep -v 'ContentSizeAnimator' "$hits" >"$hits.filtered" 2>/dev/null || true
    mv "$hits.filtered" "$hits"
    gate source.legacy-behavior "no Behavior/SizeAnimator symbols" "$hits"

    # 2. Call-sites must use the new `spec = Tween(...)` API, not the removed durationMs.
    local api="$WORK/source-api.txt"
    grep -rnE 'durationMs[[:space:]]*=|easing[[:space:]]*=' "$APP_KT" >"$api" 2>/dev/null || true
    gate source.animated-size-api "call-sites use spec = Tween(...)" "$api"

    # 3. One clock: GTK frame clock instead of a 16 ms GLib timeout.
    local clock_ok=1 clock_file="$WORK/source-clock.txt"
    : >"$clock_file"
    if ! grep -rq 'gdk_frame_clock_get_frame_time' "$FRAMEWORK_KT" 2>/dev/null; then
        echo "missing gdk_frame_clock_get_frame_time in $FRAMEWORK_KT" >>"$clock_file"
        clock_ok=0
    fi
    if grep -q 'g_timeout_add(' "$FRAMEWORK_KT/dev/knshell/compose/ComposeWindow.kt" 2>/dev/null; then
        echo "ComposeWindow.kt still uses g_timeout_add" >>"$clock_file"
        clock_ok=0
    fi
    if [[ "$clock_ok" -eq 1 ]]; then
        add_result PASS source.frame-clock "GTK frame clock, no g_timeout_add"
    else
        gate source.frame-clock "" "$clock_file"
    fi

    # 3. The C layout manager must be passive (no tick callback / easing / clock).
    local c_hits="$WORK/source-c-tick.txt"
    grep -nE 'gtk_widget_add_tick_callback|g_get_monotonic_time|kn_ease' "$C_LAYOUT" >"$c_hits" 2>/dev/null || true
    gate source.c-layout-passive "no animation inside the C manager" "$c_hits"

    # 4. The Kotlin node must use the registry and the apply observer.
    local wired="$WORK/source-wired.txt"
    : >"$wired"
    if ! grep -q 'SizeAnimationRegistry' "$FRAMEWORK_KT/dev/knshell/compose/AnimatedSize.kt" 2>/dev/null; then
        echo "AnimatedSize.kt does not reference SizeAnimationRegistry" >>"$wired"
    fi
    if ! grep -rq 'SizeAnimationRegistry.tick' "$FRAMEWORK_KT" 2>/dev/null; then
        echo "SizeAnimationRegistry.tick is never called" >>"$wired"
    fi
    if ! grep -rq 'registerApplyObserver' "$FRAMEWORK_KT" 2>/dev/null; then
        echo "no registerApplyObserver (sendApplyNotifications still polled?)" >>"$wired"
    fi
    gate source.kotlin-wiring "" "$wired"

    # 5. Stale docs are informational (the lead updates framework-api.md/README).
    local docs
    docs=$(grep -rlE 'widthBehavior|heightBehavior|sizeBehavior' docs 2>/dev/null | tr '\n' ' ' || true)
    if [[ -n "$docs" ]]; then
        add_result INFO docs.legacy-references "${docs% }"
    else
        add_result PASS docs.legacy-references "no removed API in docs/"
    fi
}

# ---------------------------------------------------------------------------
# App lifecycle
# ---------------------------------------------------------------------------
resolve_binary() {
    if [[ -n "$BINARY" ]]; then
        [[ -x "$BINARY" ]] || die "--binary is not executable: $BINARY"
        echo "$BINARY"
        return 0
    fi
    local candidate
    for candidate in \
        shell-app/build/bin/linuxX64/releaseExecutable/shell-app.kexe \
        shell-app/build/bin/linuxX64/debugExecutable/shell-app.kexe; do
        if [[ -x "$candidate" ]]; then echo "$candidate"; return 0; fi
    done
    return 1
}

start_app() {
    if [[ -n "$PID" ]]; then
        APP_PID=$PID
        OWN_APP=0
        [[ -n "$LOG" && -r "$LOG" ]] || LOG=""
        info "attaching to pid $APP_PID${LOG:+, log $LOG}"
        return 0
    fi

    local bin
    if ! bin=$(resolve_binary); then
        err "no shell binary found (looked for release/debug kexe)."
        err "build it first (scripts/dev.sh) or pass --pid/--binary."
        return 1
    fi
    BINARY=$bin
    LOG="$WORK/app.log"
    : >"$LOG"

    local gdk_debug="${GDK_DEBUG:-}"
    if [[ -n "$gdk_debug" ]]; then gdk_debug="$gdk_debug,frames"; else gdk_debug="frames"; fi

    (
        export GDK_DEBUG="$gdk_debug"
        export KN_SHELL_MODE="$MODE"
        setsid "$BINARY" >>"$LOG" 2>&1 &
        echo $! >"$WORK/app.pid"
    )
    APP_PID=$(cat "$WORK/app.pid" 2>/dev/null || true)
    OWN_APP=1
    [[ "$APP_PID" =~ ^[0-9]+$ ]] || { err "failed to start $BINARY"; return 1; }
    info "launched $BINARY (pid $APP_PID, mode $MODE, GDK_DEBUG=$gdk_debug)"
    return 0
}

wait_for_ready() {
    [[ "$OWN_APP" -eq 0 ]] && return 0
    local i
    for i in $(seq 1 100); do
        kill -0 "$APP_PID" 2>/dev/null || return 1
        if [[ -n "$LOG" ]] && grep -qE 'mounted;|entering GLib main loop' "$LOG" 2>/dev/null; then
            return 0
        fi
        sleep 0.1
    done
    # A custom --binary may not print the runtime marker; if it is alive, measure it and
    # let the individual checks decide. A real shell prints the marker well within 5 s.
    if kill -0 "$APP_PID" 2>/dev/null; then
        warn "no 'mounted' marker after 10 s; the process is alive, continuing"
        return 0
    fi
    return 1
}

stop_app() {
    [[ -z "$APP_PID" ]] && return 0
    kill -TERM -- "-$APP_PID" 2>/dev/null || kill -TERM "$APP_PID" 2>/dev/null || true
    local i
    for i in $(seq 1 30); do
        kill -0 "$APP_PID" 2>/dev/null || break
        sleep 0.05
    done
    kill -KILL -- "-$APP_PID" 2>/dev/null || kill -KILL "$APP_PID" 2>/dev/null || true
    APP_PID=""
}

# ---------------------------------------------------------------------------
# Trigger: something that changes an animated widget
# ---------------------------------------------------------------------------
init_trigger() {
    if [[ -n "$TRIGGER_CMD" ]]; then
        TRIGGER_KIND=cmd
        add_result INFO trigger "custom: $TRIGGER_CMD"
        return
    fi
    if [[ "$TRIGGER" == "none" ]]; then
        TRIGGER_KIND=none
        add_result SKIP trigger "disabled (--no-trigger)"
        return
    fi
    if [[ "$TRIGGER" == "hypr" || "$TRIGGER" == "auto" ]] && command -v hyprctl >/dev/null 2>&1; then
        ORIG_WS=$(hyprctl activeworkspace -j 2>/dev/null |
            sed -n 's/.*"id": *\(-\{0,1\}[0-9][0-9]*\).*/\1/p' | head -1)
        local ids id
        ids=$(hyprctl workspaces -j 2>/dev/null |
            grep -o '"id": *-\{0,1\}[0-9][0-9]*' | grep -o '\-\{0,1\}[0-9][0-9]*$' || true)
        OTHER_WS=""
        for id in $ids; do
            if [[ -n "$ORIG_WS" && "$id" != "$ORIG_WS" ]]; then OTHER_WS=$id; break; fi
        done
        if [[ -n "$ORIG_WS" && -n "$OTHER_WS" ]]; then
            TRIGGER_KIND=hypr
            TRIGGER_TOGGLE=0
            add_result INFO trigger "Hyprland workspace $ORIG_WS <-> $OTHER_WS (restored at exit)"
            warn "auto-trigger switches workspaces during the run; original workspace is restored"
            return
        fi
        warn "Hyprland has fewer than two workspaces; cannot auto-trigger"
    elif [[ "$TRIGGER" == "hypr" ]]; then
        warn "hyprctl not available; cannot auto-trigger"
    fi
    TRIGGER_KIND=none
    add_result SKIP trigger "no trigger available (use --trigger-cmd)"
}

trigger_once() {
    case "$TRIGGER_KIND" in
        cmd)
            bash -c "$TRIGGER_CMD" >/dev/null 2>>"$WORK/trigger.log"
            ;;
        hypr)
            if [[ "$TRIGGER_TOGGLE" -eq 0 ]]; then
                hyprctl dispatch workspace "$OTHER_WS" >/dev/null 2>>"$WORK/trigger.log"
                TRIGGER_TOGGLE=1
            else
                hyprctl dispatch workspace "$ORIG_WS" >/dev/null 2>>"$WORK/trigger.log"
                TRIGGER_TOGGLE=0
            fi
            ;;
        *)
            return 1
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Media state: the idle gates need a deterministic UI (no equalizer/sheen running).
# ---------------------------------------------------------------------------
# playerctl toggles leave the player in an unknown state; an idle window measured while
# music plays is not idle at all (equalizer + sheen animate ~30-60 frames/s, correctly
# keeping the lazy clock awake). Pause before idle, restore afterwards.
MEDIA_WAS_PLAYING=""

ensure_media_paused() {
    command -v playerctl >/dev/null 2>&1 || return 0
    local st
    st=$(playerctl status 2>/dev/null || true)
    if [[ "$st" == "Playing" ]]; then
        MEDIA_WAS_PLAYING=1
        playerctl pause >/dev/null 2>&1 || true
        sleep 1
    fi
}

restore_media() {
    if [[ "$MEDIA_WAS_PLAYING" == "1" ]]; then
        playerctl play >/dev/null 2>&1 || true
        MEDIA_WAS_PLAYING=""
    fi
}

restore_workspace() {
    if [[ "$TRIGGER_KIND" == "hypr" && -n "$ORIG_WS" ]]; then
        hyprctl dispatch workspace "$ORIG_WS" >/dev/null 2>&1 || true
    fi
    restore_media
}

start_trigger_loop() {
    [[ "$TRIGGER_KIND" == "none" ]] && return 1
    trigger_once || return 1
    (
        while :; do
            sleep "$TRIGGER_INTERVAL"
            trigger_once || exit 0
        done
    ) &
    TRIGGER_PID=$!
    return 0
}

stop_trigger() {
    [[ -n "${TRIGGER_PID:-}" ]] || return 0
    kill "$TRIGGER_PID" 2>/dev/null || true
    wait "$TRIGGER_PID" 2>/dev/null || true
    TRIGGER_PID=""
}

settle() {
    stop_trigger
    restore_workspace
    sleep "$SETTLE"
}

# ---------------------------------------------------------------------------
# /proc helpers
# ---------------------------------------------------------------------------
proc_alive() { [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; }

ctxt_switches() {
    awk -v key="$1:" '$1 == key { print $2; found=1; exit } END { if (!found) print "" }' \
        "/proc/$APP_PID/status" 2>/dev/null
}

# Latest GtkFrameClock tick count from the app log ([frameclock] ticks=N ...).
# Empty when the clock has not logged yet (fewer than 600 ticks) or no log exists.
clock_ticks() {
    [[ -n "$LOG" && -r "$LOG" ]] || { echo ""; return; }
    grep -o '\[frameclock\] ticks=[0-9]*' "$LOG" 2>/dev/null | tail -1 | grep -o '[0-9]*$'
}

rss_kb() {
    awk '/^VmRSS:/ { print $2; found=1; exit } END { if (!found) print "" }' \
        "/proc/$APP_PID/status" 2>/dev/null
}

# utime+stime in clock ticks (process-wide, includes all threads).
cpu_ticks() {
    awk '{ sub(/^.*\) /, ""); print $12 + $13 }' "/proc/$APP_PID/stat" 2>/dev/null
}

now_ns() { date +%s%N; }

# ---------------------------------------------------------------------------
# Frame metrics
# ---------------------------------------------------------------------------
refresh_median_from() {
    awk '
        /refresh_interval=/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^refresh_interval=/) {
                    v = $i; sub(/^refresh_interval=/, "", v); v += 0
                    if (v >= 3.5 && v <= 50) ri[ri_n++] = v
                }
            }
        }
        END {
            if (ri_n == 0) { print ""; exit }
            for (i = 1; i < ri_n; i++) {
                v = ri[i]; j = i - 1
                while (j >= 0 && ri[j] > v) { ri[j + 1] = ri[j]; j-- }
                ri[j + 1] = v
            }
            if (ri_n % 2) printf "%.3f\n", ri[int(ri_n / 2)]
            else printf "%.3f\n", (ri[int(ri_n / 2) - 1] + ri[int(ri_n / 2)]) / 2
        }' "$1" 2>/dev/null
}

snap_hz() {
    awk -v hz="$1" 'BEGIN {
        split("30 60 75 90 120 144 165 240", a, " ")
        best = a[1]; bd = 1e9
        for (i = 1; i <= 8; i++) { d = a[i] - hz; if (d < 0) d = -d; if (d < bd) { bd = d; best = a[i] } }
        if (bd <= 3) print best; else printf "%d", hz + 0.5
    }'
}

resolve_hz() {
    local file=$1
    if [[ -n "$HZ" ]]; then echo "$HZ"; return; fi
    local med raw
    med=$(refresh_median_from "$file")
    if [[ -n "$med" ]]; then
        raw=$(awk -v m="$med" 'BEGIN { printf "%.3f", 1000 / m }')
        HZ=$(snap_hz "$raw")
        return
    fi
    if command -v hyprctl >/dev/null 2>&1; then
        raw=$(hyprctl monitors -j 2>/dev/null |
            sed -n 's/.*"refreshRate": *\([0-9][0-9.]*\).*/\1/p' | head -1)
        if [[ -n "$raw" ]]; then HZ=$(snap_hz "$raw"); return; fi
    fi
    HZ=60
}

parse_frame_stats() {
    # Only consecutive frame pairs closer than 3 periods count as "ticks": trigger
    # switching and settle pauses produce multi-period gaps in the GDK log which are
    # not missed vsyncs. Samples above the cutoff are counted separately as pauses.
    awk -v period="$PERIOD" '
        /interval=/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^interval=/) {
                    v = $i; sub(/^interval=/, "", v); v += 0
                    if (v > 0.2 && v < 1000) {
                        if (v <= 3 * period) {
                            n++; s += v; ss += v * v
                            if (v > mx) mx = v
                            if (v > 1.5 * period) slow++
                        } else {
                            pauses++
                        }
                    }
                }
            }
        }
        END {
            if (n == 0) { printf "0 0 0 0 0 %d\n", pauses + 0; exit }
            mean = s / n; var = ss / n - mean * mean
            if (var < 0) var = 0
            printf "%d %.3f %.3f %.3f %d %d\n", n, mean, sqrt(var), mx, slow, pauses + 0
        }' "$1" 2>/dev/null
}

frame_count() {
    [[ -n "$LOG" && -r "$LOG" ]] || { echo 0; return; }
    grep -c 'interval=' "$LOG" 2>/dev/null || true
}

measure_frames() {
    say ""
    say "== frames: tick interval vs monitor period =="
    if [[ -z "$LOG" || ! -r "$LOG" ]]; then
        add_result SKIP frames.pacing "no app log (start the shell with GDK_DEBUG=frames or pass --log)"
        add_result SKIP frames.gaps "no app log"
        return
    fi
    if [[ "$TRIGGER_KIND" == "none" ]]; then
        add_result SKIP frames.pacing "no animation trigger (use --trigger-cmd)"
        add_result SKIP frames.gaps "no animation trigger"
        return
    fi

    settle
    local start
    start=$(wc -l <"$LOG")
    if ! start_trigger_loop; then
        add_result SKIP frames.pacing "trigger failed to start"
        add_result SKIP frames.gaps "trigger failed to start"
        return
    fi
    sleep "$DURATION"
    stop_trigger

    local window="$WORK/frames-window.log"
    tail -n +$((start + 1)) "$LOG" >"$window" 2>/dev/null || : >"$window"
    resolve_hz "$window"
    PERIOD=$(awk -v hz="$HZ" 'BEGIN { printf "%.3f", 1000 / hz }')

    local n mean std max slow pauses
    read -r n mean std max slow pauses <<<"$(parse_frame_stats "$window")"

    if [[ "${n:-0}" -lt 20 ]]; then
        add_result SKIP frames.pacing "only ${n:-0} frames in ${DURATION}s (no animation?)"
        add_result SKIP frames.gaps "only ${n:-0} frames"
        return
    fi

    local pausedetail=""
    [[ "${pauses:-0}" -gt 0 ]] && pausedetail=" pauses=${pauses}"

    local mean_limit
    mean_limit=$(awk -v p="$PERIOD" 'BEGIN { m = p * 0.10; if (m < 1.0) m = 1.0; print m }')
    local mean_delta
    mean_delta=$(awk -v m="$mean" -v p="$PERIOD" 'BEGIN { d = m - p; if (d < 0) d = -d; printf "%.3f", d }')

    if float_le "$mean_delta" "$mean_limit" && float_le "$std" "$FRAME_STD_MAX"; then
        add_result PASS frames.pacing \
            "n=$n mean=${mean}ms std=${std}ms period=${PERIOD}ms (${HZ}Hz) slow=$slow$pausedetail"
    else
        add_result FAIL frames.pacing \
            "n=$n mean=${mean}ms std=${std}ms (limit ${FRAME_STD_MAX}) period=${PERIOD}ms (${HZ}Hz) delta=${mean_delta} limit=${mean_limit} slow=$slow$pausedetail"
    fi

    local gap_limit
    gap_limit=$(awk -v p="$PERIOD" 'BEGIN { printf "%.3f", 2 * p + 0.5 }')
    if float_le "$max" "$gap_limit"; then
        add_result PASS frames.gaps "max=${max}ms limit=${gap_limit}ms (2 periods)$pausedetail"
    else
        add_result FAIL frames.gaps "max=${max}ms limit=${gap_limit}ms (2 periods) — missed vsyncs$pausedetail"
    fi
}

# ---------------------------------------------------------------------------
# Idle: voluntary context switches
# ---------------------------------------------------------------------------
measure_idle() {
    say ""
    say "== idle: no 16 ms wakeups =="
    if float_le "$IDLE_SECS" 0; then
        add_result SKIP idle.ctxt "disabled (--idle 0)"
        return
    fi
    settle
    # Deterministic UI: pause the player so the equalizer/sheen do not animate through
    # the idle window (that would be a correct non-idle measurement, not a clock leak).
    ensure_media_paused
    sleep 2
    local c0 c1 f0 f1 t0 t1 delta rate frames_rate ticks_rate nv
    c0=$(ctxt_switches voluntary_ctxt_switches)
    if [[ -z "$c0" ]]; then
        add_result SKIP idle.ctxt "voluntary_ctxt_switches not available in /proc"
        return
    fi
    f0=$(frame_count)
    t0=$(clock_ticks)
    sleep "$IDLE_SECS"
    c1=$(ctxt_switches voluntary_ctxt_switches)
    f1=$(frame_count)
    t1=$(clock_ticks)
    nv=$(ctxt_switches nonvoluntary_ctxt_switches)
    if [[ -z "$c1" ]]; then
        add_result SKIP idle.ctxt "process exited during the idle window"
        return
    fi

    delta=$((c1 - c0))
    rate=$(awk -v d="$delta" -v s="$IDLE_SECS" 'BEGIN { printf "%.2f", d / s }')
    frames_rate=$(awk -v d="$((f1 - f0))" -v s="$IDLE_SECS" 'BEGIN { printf "%.1f", d / s }')
    local ticks_detail=""
    if [[ -n "$t0" && -n "$t1" ]]; then
        ticks_rate=$(awk -v d="$((t1 - t0))" -v s="$IDLE_SECS" 'BEGIN { printf "%.1f", d / s }')
        ticks_detail=", clock ticks=${ticks_rate}/s"
        LAST_TICKS="$ticks_rate"
    fi

    local detail="voluntary=${rate}/s limit=${IDLE_MAX_PER_SEC}/s (${delta} in ${IDLE_SECS}s)"
    detail+=", gdk frames=${frames_rate}/s"
    [[ -n "$nv" ]] && detail+=", nonvoluntary=$nv"
    detail+="$ticks_detail"

    # The honest gate for the lazy frame clock is idle.ticks below: it counts ticks
    # produced by GtkFrameClock itself. idle.ctxt counts every process wakeup, including
    # app-level timers (Clock 1 Hz, PlayerChip sheen 30 Hz) that are out of scope for
    # compose-owned-animated-size.md (§5 «Ефективність» targets the 16 ms clock wakeups).
    if float_le "$rate" "$IDLE_MAX_PER_SEC"; then
        add_result PASS idle.ctxt "$detail"
    else
        add_result FAIL idle.ctxt "$detail"
        if float_lt 50 "$rate"; then
            warn "≈62.5 wakeups/s suggests the legacy g_timeout_add(16) clock is still running"
        fi
        warn "known app-level timers: Clock 1 Hz; PlayerChip sheen 30 Hz (gate it on media.playing)"
    fi

    # Honest lazy-clock gate: GtkFrameClock must produce ~0 ticks/s in idle. The clock
    # logs [frameclock] ticks=N every 600 ticks; before the first such line (or when no
    # log is available) the gate is SKIP, never FAIL.
    if [[ -z "$ticks_detail" ]]; then
        add_result SKIP idle.ticks "no [frameclock] marker in the app log yet"
    elif float_le "$ticks_rate" 1.0; then
        add_result PASS idle.ticks "clock ticks=${ticks_rate}/s (lazy: no waiters, no size work)"
    else
        add_result FAIL idle.ticks "clock ticks=${ticks_rate}/s — tick callback alive in idle"
    fi
}

# ---------------------------------------------------------------------------
# CPU during animation
# ---------------------------------------------------------------------------
measure_cpu() {
    say ""
    say "== CPU during animation =="
    if [[ "$TRIGGER_KIND" == "none" ]]; then
        add_result SKIP cpu.animation "no animation trigger"
        return
    fi
    if float_le "$CPU_WINDOW" 0; then
        add_result SKIP cpu.animation "disabled (--cpu-window 0)"
        return
    fi
    local clk t0 t1 w0 w1 pct
    clk=$(getconf CLK_TCK 2>/dev/null || echo 100)
    settle
    start_trigger_loop || { add_result SKIP cpu.animation "trigger failed"; return; }
    t0=$(cpu_ticks); w0=$(now_ns)
    sleep "$CPU_WINDOW"
    t1=$(cpu_ticks); w1=$(now_ns)
    stop_trigger
    if [[ -z "$t0" || -z "$t1" ]]; then
        add_result SKIP cpu.animation "cannot read /proc/$APP_PID/stat"
        return
    fi
    pct=$(awk -v t0="$t0" -v t1="$t1" -v w0="$w0" -v w1="$w1" -v clk="$clk" \
        'BEGIN { d = (t1 - t0) / clk; w = (w1 - w0) / 1e9; if (w <= 0) w = 0.001; printf "%.2f", 100 * d / w }')

    if [[ -n "$BASELINE_IN" ]]; then
        local base limit
        base=$(sed -n 's/^cpu_percent=//p' "$BASELINE_IN" | head -1)
        if [[ -z "$base" ]]; then
            add_result SKIP cpu.animation "baseline has no cpu_percent"
            return
        fi
        limit=$(awk -v b="$base" 'BEGIN { printf "%.2f", b * 1.10 }')
        if float_le "$pct" "$limit"; then
            add_result PASS cpu.animation "${pct}% (baseline ${base}%, limit ${limit}%, +10%)"
        else
            add_result FAIL cpu.animation "${pct}% > baseline ${base}% +10% (${limit}%)"
        fi
    else
        add_result INFO cpu.animation "${pct}% (no --baseline; criterion is ≤ old +10%)"
    fi
}

# ---------------------------------------------------------------------------
# RSS drift over toggles
# ---------------------------------------------------------------------------
measure_rss() {
    say ""
    say "== memory: RSS drift over toggles =="
    if [[ "$TOGGLES" -le 0 ]]; then
        add_result SKIP memory.rss-drift "disabled (--toggles 0)"
        return
    fi
    if [[ "$TRIGGER_KIND" == "none" ]]; then
        add_result SKIP memory.rss-drift "no animation trigger"
        return
    fi
    settle
    local r0 r1 r max i drift peak_drift
    r0=$(rss_kb)
    if [[ -z "$r0" ]]; then
        add_result SKIP memory.rss-drift "cannot read VmRSS"
        return
    fi
    max=$r0
    for ((i = 0; i < TOGGLES; i++)); do
        trigger_once || break
        sleep "$TOGGLE_DELAY"
        r=$(rss_kb)
        [[ -n "$r" ]] || break
        if ((r > max)); then max=$r; fi
    done
    r1=$(rss_kb)
    [[ -n "$r1" ]] || { add_result SKIP memory.rss-drift "process exited during toggles"; return; }

    drift=$(awk -v a="$r0" -v b="$r1" 'BEGIN { printf "%.2f", (b - a) / 1024 }')
    peak_drift=$(awk -v a="$r0" -v b="$max" 'BEGIN { printf "%.2f", (b - a) / 1024 }')

    # §5 "дрейф RSS < 5 МБ" targets a leak (monotonic growth). A negative drift means
    # the allocator returned pages after warm-up — not a leak. Gate on growth only:
    # FAIL when end-start exceeds the limit; a shrink passes with a note.
    if float_lt "$drift" "$RSS_MAX_MB"; then
        if float_lt "$drift" 0; then
            add_result PASS memory.rss-drift \
                "${drift} MB over $TOGGLES toggles (shrink after warm-up, peak +${peak_drift} MB, limit ${RSS_MAX_MB} MB)"
        else
            add_result PASS memory.rss-drift \
                "${drift} MB over $TOGGLES toggles (peak +${peak_drift} MB, limit ${RSS_MAX_MB} MB)"
        fi
    else
        add_result FAIL memory.rss-drift \
            "${drift} MB over $TOGGLES toggles (peak +${peak_drift} MB, limit ${RSS_MAX_MB} MB)"
    fi
}

# ---------------------------------------------------------------------------
# Recomposition counter (placeholder until the lead adds it)
# ---------------------------------------------------------------------------
measure_recomp() {
    say ""
    say "== recompositions during animation =="
    # TODO(phase-4): the app has no recomposition counter yet. Add one around the
    # Recomposer in ShellRuntime (e.g. androidx.compose.runtime.RecomposeObserver, or a
    # counter incremented by a wrapper composable around each window's content) and emit,
    # env-gated:
    #     [recomp] count=<n>
    # to stdout, or write the count to $KN_RECOMP_FILE. This script then measures the delta
    # around one animation window (below) and requires 0. See docs/tasks §5 "Ефективність".
    if [[ -n "$RECOMP_FILE" ]]; then
        if [[ ! -r "$RECOMP_FILE" ]]; then
            add_result SKIP recomp.count "cannot read $RECOMP_FILE"
            return
        fi
        local value
        value=$(tr -dc '0-9' <"$RECOMP_FILE" | head -c 12)
        if [[ -z "$value" ]]; then
            add_result SKIP recomp.count "empty counter file"
        elif [[ "$value" -eq 0 ]]; then
            add_result PASS recomp.count "0 (from $RECOMP_FILE)"
        else
            add_result FAIL recomp.count "$value recompositions (expected 0) from $RECOMP_FILE"
        fi
        return
    fi

    if [[ -z "$LOG" || "$TRIGGER_KIND" == "none" ]]; then
        add_result SKIP recomp.count "no [recomp] marker and no trigger (see TODO in script)"
        return
    fi
    local c0 c1
    c0=$(grep -o '\[recomp\] count=[0-9]*' "$LOG" 2>/dev/null | tail -1 | grep -o '[0-9]*$')
    if [[ -z "$c0" ]]; then
        add_result SKIP recomp.count "no [recomp] count=N marker (TODO: add counter in ShellRuntime)"
        return
    fi
    settle
    start_trigger_loop || { add_result SKIP recomp.count "trigger failed"; return; }
    sleep 1
    stop_trigger
    c1=$(grep -o '\[recomp\] count=[0-9]*' "$LOG" 2>/dev/null | tail -1 | grep -o '[0-9]*$')
    if [[ -z "$c1" ]]; then
        add_result SKIP recomp.count "recomposition marker disappeared"
        return
    fi
    if ((c1 < c0)); then
        add_result SKIP recomp.count "counter reset during the window ($c0 -> $c1)"
        return
    fi
    local delta=$((c1 - c0))
    if [[ "$delta" -eq 0 ]]; then
        add_result PASS recomp.count "0 during a 1 s animation window"
    else
        add_result FAIL recomp.count "$delta recompositions during a 1 s animation window"
    fi
}

# ---------------------------------------------------------------------------
# Screenshots: width monotonicity
# ---------------------------------------------------------------------------
auto_region() {
    command -v hyprctl >/dev/null 2>&1 || return 1
    command -v python3 >/dev/null 2>&1 || return 1
    local region
    region=$(hyprctl -j clients 2>/dev/null | python3 -c '
import json, sys
try:
    clients = json.load(sys.stdin)
except Exception:
    sys.exit(1)
for c in clients:
    if c.get("title") == "KN Shell":
        x, y = c.get("at", [0, 0])
        w, h = c.get("size", [0, 0])
        print("%d,%d %dx%d" % (x, y, w, h))
        break
else:
    sys.exit(1)
' 2>/dev/null) || region=""
    if [[ -n "$region" ]]; then echo "$region"; return 0; fi
    region=$(hyprctl -j monitors 2>/dev/null | python3 -c '
import json, sys
bar_height = int(sys.argv[1])
try:
    ms = json.load(sys.stdin)
except Exception:
    sys.exit(1)
m = next((m for m in ms if m.get("focused")), ms[0] if ms else None)
if not m:
    sys.exit(1)
print("%d,%d %dx%d" % (m["x"], m["y"], m["width"], bar_height))
' "$BAR_HEIGHT" 2>/dev/null) || region=""
    [[ -n "$region" ]] || return 1
    echo "$region"
}

run_screenshot_analysis() {
    local base=$1 dir=$2
    python3 - "$base" "$dir" "$SHOT_THRESHOLD" "$PX_TOLERANCE" "$PLATEAU_MAX" "$MIN_DELTA" <<'PY'
import glob
import os
import sys

from PIL import Image, ImageChops

base_path, shots_dir, threshold, tolerance, plateau_max, min_delta = (
    sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]),
    int(sys.argv[5]), int(sys.argv[6]),
)
files = sorted(glob.glob(os.path.join(shots_dir, "f*.png")))
if len(files) < 3:
    print("SKIP only %d frames captured (grim too slow for the animation)" % len(files))
    sys.exit(2)

base = Image.open(base_path).convert("RGB")
rows = []
for path in files:
    img = Image.open(path).convert("RGB")
    if img.size != base.size:
        continue
    diff = ImageChops.difference(img, base)
    r, g, b = diff.split()
    mask = ImageChops.lighter(ImageChops.lighter(r, g), b).point(
        lambda v: 255 if v >= threshold else 0
    )
    bbox = mask.getbbox()
    if bbox:
        width = bbox[2] - bbox[0]
        height = bbox[3] - bbox[1]
        area = mask.histogram()[255]
    else:
        width = height = area = 0
    rows.append((os.path.basename(path), width, height, area))

# Leading zero rows are frames before the trigger; the trailing constant tail is after
# the animation settled. Keep only the moving middle.
nonzero = [i for i, row in enumerate(rows) if row[1] > 0]
if not nonzero:
    print("SKIP no pixel change vs the baseline (wrong region or trigger?)")
    sys.exit(2)
start, end = nonzero[0], nonzero[-1]
seq = rows[start:end + 1]
widths = [row[1] for row in seq]
if len(widths) < 3:
    print("SKIP only %d moving frames" % len(widths))
    sys.exit(2)

last_change = 0
for i in range(1, len(widths)):
    if abs(widths[i] - widths[i - 1]) > tolerance:
        last_change = i
if last_change < len(widths) - 1:
    seq = seq[:last_change + 1]
    widths = widths[:last_change + 1]

if len(widths) < 3:
    print("SKIP only %d changing frames" % len(widths))
    sys.exit(2)

direction = "grow" if widths[-1] >= widths[0] else "shrink"
delta = abs(widths[-1] - widths[0])
if delta < min_delta:
    print("SKIP width delta %dpx < %dpx (region too tight or not animated)" % (delta, min_delta))
    sys.exit(2)

violations = []
plateau = 0
max_plateau = 0
for i in range(1, len(widths)):
    d = widths[i] - widths[i - 1]
    if direction == "grow" and d < -tolerance:
        violations.append("f%d:%+dpx" % (i, d))
    if direction == "shrink" and d > tolerance:
        violations.append("f%d:%+dpx" % (i, d))
    if abs(d) <= tolerance:
        plateau += 1
        max_plateau = max(max_plateau, plateau)
    else:
        plateau = 0

csv_path = os.path.join(shots_dir, "analysis.csv")
with open(csv_path, "w", encoding="utf-8") as fh:
    fh.write("frame,width,height,changed_px\n")
    for row in seq:
        fh.write("%s,%d,%d,%d\n" % row)

summary = "%s %d->%dpx over %d frames, max_plateau=%d, widths=%s" % (
    direction, widths[0], widths[-1], len(widths), max_plateau, widths,
)
if violations:
    print("FAIL %s; non-monotonic steps: %s" % (summary, ", ".join(violations[:5])))
    sys.exit(1)
if max_plateau > plateau_max:
    print("FAIL %s; plateau > %d frame(s)" % (summary, plateau_max))
    sys.exit(1)
print("PASS %s" % summary)
sys.exit(0)
PY
}

measure_screenshots() {
    say ""
    say "== visuals: width monotonicity from grim frames =="
    if [[ "$SHOTS" -le 0 ]]; then
        add_result SKIP visual.width-monotonic "disabled (--shots 0)"
        return
    fi
    local missing=""
    command -v grim >/dev/null 2>&1 || missing+="grim "
    command -v python3 >/dev/null 2>&1 || missing+="python3 "
    if [[ -z "$missing" ]]; then
        python3 -c 'import PIL' >/dev/null 2>&1 || missing+="PIL "
    fi
    if [[ -n "$missing" ]]; then
        add_result SKIP visual.width-monotonic "missing: ${missing}(install grim + python3-Pillow)"
        return
    fi
    if [[ "$TRIGGER_KIND" == "none" ]]; then
        add_result SKIP visual.width-monotonic "no animation trigger"
        return
    fi

    local region=$SHOT_REGION
    if [[ -z "$region" ]]; then
        region=$(auto_region || true)
    fi
    if [[ -z "$region" ]]; then
        add_result SKIP visual.width-monotonic "cannot auto-detect the window; pass --shot-region"
        return
    fi

    settle
    local shots_dir="$WORK/shots"
    mkdir -p "$shots_dir"
    if ! grim -g "$region" "$shots_dir/base.png" 2>>"$WORK/grim.log"; then
        add_result SKIP visual.width-monotonic "grim failed (region '$region'; see grim.log)"
        return
    fi
    trigger_once || true

    local i=0 frame
    local deadline=$((SECONDS + SHOT_WINDOW))
    while ((i < SHOTS && SECONDS <= deadline)); do
        i=$((i + 1))
        printf -v frame '%s/f%03d.png' "$shots_dir" "$i"
        grim -g "$region" "$frame" 2>>"$WORK/grim.log" || break
    done

    local analysis rc
    analysis=$(run_screenshot_analysis "$shots_dir/base.png" "$shots_dir" 2>&1)
    rc=$?
    local verdict=${analysis%% *}
    local detail=${analysis#* }
    if [[ "$SHOT_REGION_EXPLICIT" -eq 0 ]]; then
        # An auto-detected region covers the whole window/bar, so several widgets may
        # animate at once: report the observation but do not fail the run on it.
        add_result INFO visual.width-monotonic "auto region '$region' ($verdict): $detail"
    else
        case "$rc" in
            0) add_result PASS visual.width-monotonic "region '$region': $detail" ;;
            2) add_result SKIP visual.width-monotonic "region '$region': $detail" ;;
            *) add_result FAIL visual.width-monotonic "region '$region': $detail" ;;
        esac
    fi
}

# ---------------------------------------------------------------------------
# Baselines
# ---------------------------------------------------------------------------
# Filled by the measurements; written only when --save-baseline is used.
LAST_MEAN=""
LAST_STD=""
LAST_MAX=""
LAST_IDLE=""
LAST_TICKS=""
LAST_CPU=""
LAST_RSS=""

save_baseline() {
    [[ -n "$BASELINE_OUT" ]] || return
    local dir
    dir=$(dirname "$BASELINE_OUT")
    [[ -d "$dir" ]] || mkdir -p "$dir" 2>/dev/null || { warn "cannot create $dir"; return; }
    {
        echo "# kn-anim-bench baseline v1"
        echo "created=$(date -Is 2>/dev/null || date)"
        echo "git=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
        echo "mode=$MODE"
        echo "hz=${HZ:-}"
        echo "frames_mean_ms=${LAST_MEAN:-}"
        echo "frames_std_ms=${LAST_STD:-}"
        echo "frames_max_gap_ms=${LAST_MAX:-}"
        echo "idle_ctxt_per_sec=${LAST_IDLE:-}"
        echo "clock_ticks_per_sec=${LAST_TICKS:-}"
        echo "cpu_percent=${LAST_CPU:-}"
        echo "rss_drift_mb=${LAST_RSS:-}"
    } >"$BASELINE_OUT"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
mkdir -p "${TMPDIR:-/tmp}"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/kn-anim-bench.XXXXXX") || die "cannot create a temp dir"

say "KN Shell animation benchmark"
say "repo: $(pwd)"
say "artifacts: $WORK"

run_source_checks

if ! start_app; then
    add_result FAIL app.launch "no shell binary / process — nothing measured"
    CRITICAL=1
elif ! wait_for_ready; then
    err "shell did not start within 10 s; last log lines:"
    [[ -n "$LOG" && -r "$LOG" ]] && tail -n 15 "$LOG" >&2
    add_result FAIL app.launch "did not reach the GLib loop"
    CRITICAL=1
else
    add_result PASS app.launch "pid $APP_PID${BINARY:+, $BINARY}${MODE:+, mode $MODE}"

    init_trigger

    # Idle first: the app is freshly mounted and no animation has been triggered yet.
    measure_idle
    measure_frames
    measure_screenshots
    measure_cpu
    measure_rss
    measure_recomp
fi

# Pull the numbers out of RESULTS for --save-baseline.
for row in "${RESULTS[@]}"; do
    IFS='|' read -r status name details <<<"$row"
    case "$name" in
        frames.pacing)
            LAST_MEAN=$(sed -n 's/.*mean=\([0-9.]*\)ms.*/\1/p' <<<"$details" | head -1)
            LAST_STD=$(sed -n 's/.*std=\([0-9.]*\)ms.*/\1/p' <<<"$details" | head -1)
            ;;
        frames.gaps)
            LAST_MAX=$(sed -n 's/.*max=\([0-9.]*\)ms.*/\1/p' <<<"$details" | head -1)
            ;;
        idle.ctxt)
            LAST_IDLE=$(sed -n 's/.*voluntary=\([0-9.]*\)\/s.*/\1/p' <<<"$details" | head -1)
            ;;
        idle.ticks)
            LAST_TICKS=$(sed -n 's/.*clock ticks=\([0-9.]*\)\/s.*/\1/p' <<<"$details" | head -1)
            ;;
        cpu.animation)
            LAST_CPU=$(sed -n 's/^\([0-9.]*\)%.*/\1/p' <<<"$details" | head -1)
            ;;
        memory.rss-drift)
            LAST_RSS=$(sed -n 's/^\(-\{0,1\}[0-9.]*\) MB.*/\1/p' <<<"$details" | head -1)
            ;;
    esac
done
save_baseline

print_summary
if [[ "$CRITICAL" -eq 1 ]]; then
    EXIT_CODE=2
elif [[ "$FAIL_COUNT" -gt 0 ]]; then
    EXIT_CODE=1
elif [[ "$STRICT" -eq 1 && "$SKIP_COUNT" -gt 0 ]]; then
    EXIT_CODE=1
else
    EXIT_CODE=0
fi
exit "$EXIT_CODE"
