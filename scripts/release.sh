#!/usr/bin/env bash
# Builds the signed release APK for one environment.
#
#   scripts/release.sh <pilot|local>
#
# The signing keystore and its password come from Secret Manager in the Port OS GCP project; they are written
# to a temporary directory that is removed on exit and never touch the repository.
set -euo pipefail

ENV="${1:?usage: release.sh <pilot|local>}"
PROJECT="${PORTOS_GCP_PROJECT:-project-c0c329ff-45f7-49e0-923}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

case "$ENV" in
  pilot) VARIANT=Pilot ;;
  local) VARIANT=Local ;;
  *) echo "ENV must be pilot or local" >&2; exit 2 ;;
esac

SECRETS="$(mktemp -d)"
trap 'rm -rf "$SECRETS"' EXIT

gcloud secrets versions access latest --project "$PROJECT" --secret portos-android-tv-signing-keystore \
  | base64 --decode > "$SECRETS/release.jks"
PORTOS_TV_KEYSTORE_PASSWORD="$(gcloud secrets versions access latest --project "$PROJECT" \
  --secret portos-android-tv-signing-keystore-password)"
export PORTOS_TV_KEYSTORE="$SECRETS/release.jks" PORTOS_TV_KEYSTORE_PASSWORD

if [[ "${OS:-}" == "Windows_NT" ]]; then GRADLE=./gradlew.bat; else GRADLE=./gradlew; fi
"$GRADLE" --console=plain ":app:assemble${VARIANT}Release"

APK="app/build/outputs/apk/$ENV/release/app-$ENV-release.apk"
test -f "$APK" || { echo "expected $APK" >&2; exit 1; }
echo "$APK"
