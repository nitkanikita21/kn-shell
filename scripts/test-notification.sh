#!/usr/bin/env bash
# Sends a rich test notification to whichever process owns org.freedesktop.Notifications
# (the KN Shell notification server when it runs, otherwise swaync/mako/dunst).
#
# Usage:
#   scripts/test-notification.sh [TITLE] [BODY] [ICON_PATH]
#   scripts/test-notification.sh --plain "Title" "Body"   # no actions, notify-send only
#
# The rich variant includes an image (image-path), two actions and critical urgency — it
# exercises the notification card: icon, action buttons, accent colour.
set -euo pipefail

PLAIN=0
if [[ "${1:-}" == "--plain" ]]; then
    PLAIN=1
    shift
fi

TITLE="${1:-KN Shell test}"
BODY="${2:-If you can see this card, notifications work.}"
ICON="${3:-/usr/share/pixmaps/archlinux-logo.png}"

# Fall back to a Nerd Font glyph when the icon file is missing.
if [[ ! -f "$ICON" ]]; then
    ICON=""
fi

if [[ $PLAIN -eq 0 ]] && command -v gdbus >/dev/null 2>&1 && [[ -n "$ICON" ]]; then
    echo "Sending rich test notification (icon=$ICON, actions=Open/Later, urgency=critical)"
    gdbus call --session \
        --dest org.freedesktop.Notifications \
        --object-path /org/freedesktop/Notifications \
        --method org.freedesktop.Notifications.Notify \
        "KN Shell" 0 "$ICON" "$TITLE" "$BODY" \
        "['open','Open','later','Later']" \
        "{'urgency': <byte 2>, 'image-path': <'$ICON'>}" \
        8000
else
    if command -v notify-send >/dev/null 2>&1; then
        echo "Sending plain test notification"
        notify-send -a "KN Shell" ${ICON:+-i "$ICON"} -u critical "$TITLE" "$BODY"
    else
        echo "Neither gdbus nor notify-send is available" >&2
        exit 1
    fi
fi
