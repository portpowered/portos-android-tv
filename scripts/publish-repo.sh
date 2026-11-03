#!/usr/bin/env bash
# Publishes this directory to the public release copy, github.com/portpowered/portos-android-tv.
#
#   scripts/publish-repo.sh            commit and push "Sync from portos-monolith <sha>"
#   DRY_RUN=1 scripts/publish-repo.sh  stage the copy and show the diff without pushing
#
# The monorepo stays the source. Only tracked files are copied, CLAUDE.md (agent notes about monorepo paths) is
# left out, and github/ becomes .github/ so the workflows run there and never in the monorepo. Run it from a
# clean checkout of main.
set -euo pipefail

REPO="${PORTOS_TV_PUBLIC_REPO:-portpowered/portos-android-tv}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
test -z "$(git status --porcelain -- .)" || { echo "devices/android-tv has uncommitted changes" >&2; exit 1; }
SHA="$(git rev-parse --short HEAD)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
gh repo clone "$REPO" "$WORK/repo" -- --quiet
cd "$WORK/repo" || exit 1
git ls-files -z | xargs -0 -r git rm -q --cached --
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +

cd "$ROOT" || exit 1
git ls-files -z -- . ':!CLAUDE.md' | while IFS= read -r -d '' file; do
  target="$file"
  case "$file" in github/*) target=".${file}" ;; esac
  mkdir -p "$WORK/repo/$(dirname "$target")"
  cp -p "$file" "$WORK/repo/$target"
done
# Monorepo-relative links point at files the public copy does not have.
sed -i '/\](\.\.\/\.\.\//d' "$WORK/repo/README.md"

cd "$WORK/repo" || exit 1
git add -A
if git diff --cached --quiet; then echo "already current"; exit 0; fi
if [[ -n "${DRY_RUN:-}" ]]; then git diff --cached --stat; exit 0; fi
git commit -q -m "Sync from portos-monolith $SHA"
git push -q origin HEAD
echo "published $REPO at $SHA"
