#!/usr/bin/env bash
set -euo pipefail

TABPREFIX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
TABPREFIX_CLEAN_ALL=false
case "${1:-}" in
  "") ;;
  --all) TABPREFIX_CLEAN_ALL=true ;;
  *) printf 'Usage: bash clean.sh [--all]\n' >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { printf 'Too many arguments.\n' >&2; exit 1; }

for tabprefix_dir in .build dist; do
  if [[ -L "$TABPREFIX_ROOT/$tabprefix_dir" ]]; then
    printf 'ERROR: Build directory is a symlink: %s\n' "$tabprefix_dir" >&2
    exit 1
  fi
done

rm -rf "$TABPREFIX_ROOT/.build/classes" "$TABPREFIX_ROOT/.build/work" "$TABPREFIX_ROOT/.build/ide-classes"
rm -rf "$TABPREFIX_ROOT/.build/test-classes" "$TABPREFIX_ROOT/.build/test-data"
rm -rf "$TABPREFIX_ROOT/.build/browser-checks"
rm -f "$TABPREFIX_ROOT/.build/editor-test.json" "$TABPREFIX_ROOT/.build/test-png.png" "$TABPREFIX_ROOT/.build/test-gif.gif"
rm -f "$TABPREFIX_ROOT/.build/tests-raw.jar" "$TABPREFIX_ROOT/.build/tests.jar"
rm -f "$TABPREFIX_ROOT/dist/TabPrefix.jar" "$TABPREFIX_ROOT/dist/.TabPrefix.jar.tmp"
if [[ "$TABPREFIX_CLEAN_ALL" == true ]]; then
  rm -rf "$TABPREFIX_ROOT/.build/deps"
fi
printf 'Build output cleaned.\n'
