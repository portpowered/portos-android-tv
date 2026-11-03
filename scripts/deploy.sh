#!/usr/bin/env bash
# Uploads the release APK built by release.sh to the public downloads bucket.
#
#   scripts/deploy.sh <pilot|local>
#
# Each release is kept under its version name; "latest" is what the plugin setup docs link to.
set -euo pipefail

ENV="${1:?usage: deploy.sh <pilot|local>}"
BUCKET="${PORTOS_DOWNLOADS_BUCKET:-gs://portos-downloads-685761720421}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

[[ "$ENV" == "pilot" ]] || { echo "only pilot releases are published" >&2; exit 2; }

APK="app/build/outputs/apk/$ENV/release/app-$ENV-release.apk"
test -f "$APK" || { echo "missing $APK: run make release first" >&2; exit 1; }
VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
test -n "$VERSION" || { echo "could not read versionName" >&2; exit 1; }

gcloud storage cp "$APK" "$BUCKET/android-tv/portos-android-tv-$VERSION.apk"
gcloud storage cp "$APK" "$BUCKET/android-tv/portos-android-tv-latest.apk" \
  --cache-control="no-cache, max-age=0"
echo "published https://storage.googleapis.com/${BUCKET#gs://}/android-tv/portos-android-tv-latest.apk"
