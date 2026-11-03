#!/usr/bin/env bash
# Switches on the Port OS accessibility service (remote buttons and screen capture) over adb.
#
#   scripts/enable-accessibility.sh <package> [adb-serial]
#
# Google TV has no settings screen for it. Other enabled accessibility services are kept: the new one is
# appended to the colon-separated list, and nothing changes when it is already there.
set -euo pipefail

PACKAGE="${1:?usage: enable-accessibility.sh <package> [adb-serial]}"
SERIAL="${2:-}"
SERVICE="$PACKAGE/$PACKAGE.control.PortOsAccessibilityService"

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=(-s "$SERIAL")
fi

current="$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$current" in
  "" | null) updated="$SERVICE" ;;
  *"$SERVICE"*) updated="$current" ;;
  *) updated="$current:$SERVICE" ;;
esac

"${ADB[@]}" shell settings put secure enabled_accessibility_services "$updated"
"${ADB[@]}" shell settings put secure accessibility_enabled 1
echo "accessibility services enabled: $updated"
