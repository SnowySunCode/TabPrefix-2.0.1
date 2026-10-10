#!/usr/bin/env bash
set -euo pipefail
TABPREFIX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source "$TABPREFIX_ROOT/setup.sh" --offline
[[ "$TABPREFIX_COMPILER_VERSION" =~ ^javac\ ([0-9]+) && "${BASH_REMATCH[1]}" -ge 17 ]] || tabprefix_fail "Packet record fixtures require JDK 17+. Plugin build remains Java 8."
[[ -f "$TABPREFIX_ROOT/.build/tests.jar" && -f "$TABPREFIX_ROOT/dist/TabPrefix.jar" ]] || tabprefix_fail "Run bash test.sh first."
TABPREFIX_COMPAT="$TABPREFIX_ROOT/.build/compatibility"
rm -rf "$TABPREFIX_COMPAT"
mkdir -p "$TABPREFIX_COMPAT/tools" "$TABPREFIX_COMPAT/src" "$TABPREFIX_COMPAT/classes"
TABPREFIX_API="$TABPREFIX_ROOT/.build/deps/provided/spigot-api-1.16.5.jar"
TABPREFIX_ASM="$TABPREFIX_ROOT/.build/deps/tool/asm-9.6.jar"
TABPREFIX_LP="$TABPREFIX_ROOT/.build/deps/provided/luckperms-api-5.5.jar"
"$TABPREFIX_JAVAC" --release 8 -cp "$TABPREFIX_ASM" -d "$TABPREFIX_COMPAT/tools" "$TABPREFIX_ROOT/compat-fixtures/ApiSurface.java"
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_COMPAT/tools:$TABPREFIX_ASM" ApiSurface derive "$TABPREFIX_API" "$TABPREFIX_COMPAT/api-old.jar" old
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_COMPAT/tools:$TABPREFIX_ASM" ApiSurface derive "$TABPREFIX_API" "$TABPREFIX_COMPAT/api-new.jar" new
for tabprefix_api in "$TABPREFIX_COMPAT/api-old.jar" "$TABPREFIX_API" "$TABPREFIX_COMPAT/api-new.jar"; do
  "$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_COMPAT/tools:$TABPREFIX_ASM:$tabprefix_api:$TABPREFIX_LP:$TABPREFIX_ROOT/dist/TabPrefix.jar" ApiSurface verify "$TABPREFIX_ROOT/dist/TabPrefix.jar"
done
mkdir -p "$TABPREFIX_COMPAT/modern-api"
"$TABPREFIX_JAVAC" --release 8 -cp "$TABPREFIX_COMPAT/api-new.jar" -d "$TABPREFIX_COMPAT/modern-api" \
  "$TABPREFIX_ROOT/compat-fixtures/modern-api/org/bukkit/event/player/PlayerResourcePackStatusEvent.java"
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_COMPAT/api-old.jar:$TABPREFIX_LP" \
  me.snowsun.tabprefix.test.PackCompatibilityTest "$TABPREFIX_ROOT" old
"$TABPREFIX_JAVA" -Xmx384m -cp "$TABPREFIX_COMPAT/modern-api:$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$TABPREFIX_COMPAT/api-new.jar:$TABPREFIX_LP" \
  me.snowsun.tabprefix.test.PackCompatibilityTest "$TABPREFIX_ROOT" new
python3 "$TABPREFIX_ROOT/compat-fixtures/generate.py" "$TABPREFIX_COMPAT/src"
for tabprefix_family in legacy12 legacy13 legacy14 legacy15 spigot17 spigot19 update7 update8 update9 mojang26; do
  tabprefix_classes="$TABPREFIX_COMPAT/classes/$tabprefix_family"
  mkdir -p "$tabprefix_classes"
  TABPREFIX_FIXTURE_SOURCES=()
  while IFS= read -r -d '' tabprefix_file; do TABPREFIX_FIXTURE_SOURCES+=("$tabprefix_file"); done < <(find "$TABPREFIX_COMPAT/src/$tabprefix_family" -name '*.java' -print0)
  "$TABPREFIX_JAVAC" --release 17 -cp "$TABPREFIX_API:$TABPREFIX_ROOT/.build/tests.jar" -d "$tabprefix_classes" "${TABPREFIX_FIXTURE_SOURCES[@]}"
  tabprefix_surface="$TABPREFIX_API"
  if [[ "$tabprefix_family" == legacy12 ]]; then tabprefix_surface="$TABPREFIX_COMPAT/api-old.jar"; fi
  "$TABPREFIX_JAVA" -Xmx384m -cp "$tabprefix_classes:$TABPREFIX_ROOT/.build/tests.jar:$TABPREFIX_ROOT/dist/TabPrefix.jar:$tabprefix_surface:$TABPREFIX_LP" \
    me.snowsun.tabprefix.test.CompatibilityIntegrationTest "$TABPREFIX_ROOT" --packets "$tabprefix_family"
done
