#!/usr/bin/env bash
set -euo pipefail
TABPREFIX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
bash "$TABPREFIX_ROOT/test.sh" "$@"
source "$TABPREFIX_ROOT/setup.sh" --offline
TABPREFIX_BROWSER_TEST_CP="$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar"
for tabprefix_entry in "${TABPREFIX_DEPENDENCIES[@]}"; do
  IFS='|' read -r tabprefix_scope tabprefix_name tabprefix_url <<< "$tabprefix_entry"
  if [[ "$tabprefix_scope" == provided ]]; then
    TABPREFIX_BROWSER_TEST_CP="$TABPREFIX_BROWSER_TEST_CP:$TABPREFIX_ROOT/.build/deps/provided/$tabprefix_name"
  fi
done
"$TABPREFIX_JAVA" -Djava.awt.headless=true -cp "$TABPREFIX_BROWSER_TEST_CP" \
  me.snowsun.tabprefix.test.FullIntegrationTest "$TABPREFIX_ROOT" --serve
