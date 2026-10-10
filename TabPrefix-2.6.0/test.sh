#!/usr/bin/env bash
set -euo pipefail
TABPREFIX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ "${1:-}" == "--skip-build" ]]; then shift; else bash "$TABPREFIX_ROOT/build.sh" "$@"; fi
source "$TABPREFIX_ROOT/setup.sh" --offline

TABPREFIX_TEST_CP="$TABPREFIX_ROOT/.build/classes"
TABPREFIX_TEST_TOOL_CP=""
TABPREFIX_TEST_PROVIDED_CP=""
for tabprefix_entry in "${TABPREFIX_DEPENDENCIES[@]}"; do
  IFS='|' read -r tabprefix_scope tabprefix_name tabprefix_url <<< "$tabprefix_entry"
  tabprefix_path="$TABPREFIX_ROOT/.build/deps/$tabprefix_scope/$tabprefix_name"
  if [[ "$tabprefix_scope" == tool ]]; then
    TABPREFIX_TEST_TOOL_CP="${TABPREFIX_TEST_TOOL_CP:+$TABPREFIX_TEST_TOOL_CP:}$tabprefix_path"
  else
    TABPREFIX_TEST_CP="$TABPREFIX_TEST_CP:$tabprefix_path"
    if [[ "$tabprefix_scope" == provided ]]; then
      TABPREFIX_TEST_PROVIDED_CP="${TABPREFIX_TEST_PROVIDED_CP:+$TABPREFIX_TEST_PROVIDED_CP:}$tabprefix_path"
    fi
  fi
done
TABPREFIX_TEST_SOURCES=()
while IFS= read -r -d '' tabprefix_source; do TABPREFIX_TEST_SOURCES+=("$tabprefix_source"); done \
  < <(find "$TABPREFIX_ROOT/tests" -type f -name '*.java' -print0)
[[ ${#TABPREFIX_TEST_SOURCES[@]} -gt 0 ]] || tabprefix_fail "No integration tests found."
case "$TABPREFIX_COMPILER_VERSION" in
  "javac 1.8."*) TABPREFIX_TEST_OPTIONS=(-source 8 -target 8) ;;
  *) TABPREFIX_TEST_OPTIONS=(--release 8) ;;
esac
rm -rf "$TABPREFIX_ROOT/.build/test-classes"
mkdir -p "$TABPREFIX_ROOT/.build/test-classes" "$TABPREFIX_ROOT/.build/test-data"
"$TABPREFIX_JAVAC" -J-Xmx384m "${TABPREFIX_TEST_OPTIONS[@]}" -encoding UTF-8 \
  -cp "$TABPREFIX_TEST_CP" -d "$TABPREFIX_ROOT/.build/test-classes" "${TABPREFIX_TEST_SOURCES[@]}"
"$TABPREFIX_JAR" cf "$TABPREFIX_ROOT/.build/tests-raw.jar" -C "$TABPREFIX_ROOT/.build/test-classes" .
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_TEST_TOOL_CP" com.eed3si9n.jarjar.Main process \
  "$TABPREFIX_ROOT/.build/work/relocation.rules" "$TABPREFIX_ROOT/.build/tests-raw.jar" "$TABPREFIX_ROOT/.build/tests.jar"
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.CoreIntegrationTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -Djava.awt.headless=true -Dsun.net.http.allowRestrictedHeaders=true -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.FullIntegrationTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.MessageDeliveryTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -Djava.awt.headless=true -Dsun.net.http.allowRestrictedHeaders=true -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.NetworkIntegrationTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -Djava.awt.headless=true -Dsun.net.http.allowRestrictedHeaders=true -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.DisplayIntegrationTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.BossBarIntegrationTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.TabStyleIntegrationTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -Djava.awt.headless=true -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.CompatibilityIntegrationTest "$TABPREFIX_ROOT"
"$TABPREFIX_JAVA" -Xmx384m -Djava.awt.headless=true -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.DreamIntegrationTest "$TABPREFIX_ROOT"

"$TABPREFIX_JAVA" -Xmx384m -Djava.awt.headless=true -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_TEST_PROVIDED_CP" \
  me.snowsun.tabprefix.test.NativeCanvasIntegrationTest "$TABPREFIX_ROOT"
TABPREFIX_STANDALONE_CP=""
for tabprefix_path in "$TABPREFIX_ROOT/.build/deps/provided/"*.jar; do
  [[ "${tabprefix_path##*/}" == luckperms-* ]] && continue
  TABPREFIX_STANDALONE_CP="${TABPREFIX_STANDALONE_CP:+$TABPREFIX_STANDALONE_CP:}$tabprefix_path"
done
"$TABPREFIX_JAVA" -Xmx384m -Djava.awt.headless=true -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_STANDALONE_CP" \
  me.snowsun.tabprefix.test.NativeCanvasIntegrationTest "$TABPREFIX_ROOT" --standalone
