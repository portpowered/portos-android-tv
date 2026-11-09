#!/usr/bin/env bash
# Vendors PortosTokens.kt, the Compose design tokens generated in the Port OS monorepo, into this app.
#
#   make sync-tokens MONOREPO=/path/to/portos-backend
#
# The file is generated from frontend/packages/ui/theme/tokens.json (bun theme/generate-tokens.mjs there) and
# must be committed in the monorepo, so the commit this records names exactly the bytes copied. The app never
# edits the vendored file by hand; TokensTest checks it against the commit named in its header.
set -euo pipefail

monorepo="${1:-${MONOREPO:-}}"
if [ -z "$monorepo" ]; then
  echo "usage: scripts/sync-tokens.sh <path to a portos-backend checkout>" >&2
  exit 2
fi
relative="frontend/packages/ui/theme/PortosTokens.kt"
source_file="$monorepo/$relative"
target="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/kotlin/com/portoperatingsystem/tv/surface/PortosTokens.kt"

[ -f "$source_file" ] || { echo "$source_file not found" >&2; exit 1; }
if ! git -C "$monorepo" diff --quiet HEAD -- "$relative"; then
  echo "$relative has uncommitted changes in $monorepo; commit them first so the recorded commit is true" >&2
  exit 1
fi
commit="$(git -C "$monorepo" rev-parse HEAD)"

sha256() { if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
original="$(tr -d '\r' < "$source_file")"
if ! grep -q '^// Source commit: UNRECORDED$' <<<"$original"; then
  echo "$source_file does not carry the UNRECORDED header lines; regenerate it" >&2
  exit 1
fi
digest="$(printf '%s\n' "$original" | sha256)"
printf '%s\n' "$original" \
  | sed -e "s|^// Source commit: UNRECORDED$|// Source commit: $commit|" \
        -e "s|^// Source SHA-256: UNRECORDED$|// Source SHA-256: $digest|" > "$target"
echo "vendored $relative at $commit"
