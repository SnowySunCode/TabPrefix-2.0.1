#!/usr/bin/env bash
set -euo pipefail

TABPREFIX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source "$TABPREFIX_ROOT/setup.sh" "$@"

for tabprefix_dir in .build/classes .build/work dist; do
  [[ ! -L "$TABPREFIX_ROOT/$tabprefix_dir" ]] || tabprefix_fail "Build directory is a symlink: $tabprefix_dir"
done
for tabprefix_resource in plugin.yml config.yml messages.yml messages_ru.yml web/editor.html web/editor.css web/editor.js; do
  [[ -f "$TABPREFIX_ROOT/resources/$tabprefix_resource" ]] || tabprefix_fail "Missing resources/$tabprefix_resource"
done
[[ -d "$TABPREFIX_ROOT/src" ]] || tabprefix_fail "Missing src directory."

TABPREFIX_CLASSES="$TABPREFIX_ROOT/.build/classes"
TABPREFIX_WORK="$TABPREFIX_ROOT/.build/work"
TABPREFIX_PACKAGE="$TABPREFIX_WORK/package"
TABPREFIX_SERVICES="$TABPREFIX_WORK/services"
rm -rf "$TABPREFIX_CLASSES" "$TABPREFIX_WORK"
mkdir -p "$TABPREFIX_CLASSES" "$TABPREFIX_PACKAGE" "$TABPREFIX_SERVICES" "$TABPREFIX_ROOT/dist"

TABPREFIX_CLASSPATH=""
TABPREFIX_TOOL_CLASSPATH=""
TABPREFIX_RUNTIME_JARS=()
for tabprefix_entry in "${TABPREFIX_DEPENDENCIES[@]}"; do
  IFS='|' read -r tabprefix_scope tabprefix_name tabprefix_url <<< "$tabprefix_entry"
  tabprefix_path="$TABPREFIX_ROOT/.build/deps/$tabprefix_scope/$tabprefix_name"
  if [[ "$tabprefix_scope" == tool ]]; then
    TABPREFIX_TOOL_CLASSPATH="${TABPREFIX_TOOL_CLASSPATH:+$TABPREFIX_TOOL_CLASSPATH:}$tabprefix_path"
  else
    TABPREFIX_CLASSPATH="${TABPREFIX_CLASSPATH:+$TABPREFIX_CLASSPATH:}$tabprefix_path"
    if [[ "$tabprefix_scope" == runtime ]]; then
      TABPREFIX_RUNTIME_JARS+=("$tabprefix_path")
    fi
  fi
done

TABPREFIX_SOURCES=()
while IFS= read -r -d '' tabprefix_source; do
  TABPREFIX_SOURCES+=("$tabprefix_source")
done < <(find "$TABPREFIX_ROOT/src" -type f -name '*.java' -print0)
[[ ${#TABPREFIX_SOURCES[@]} -gt 0 ]] || tabprefix_fail "No Java source files found."

tabprefix_compiler_version="$("$TABPREFIX_JAVAC" -version 2>&1)"
case "$tabprefix_compiler_version" in
  "javac 1.8."*) TABPREFIX_COMPILER_OPTIONS=(-source 8 -target 8) ;;
  *) TABPREFIX_COMPILER_OPTIONS=(--release 8) ;;
esac
printf 'Compiling %s source files for Java 8...\n' "${#TABPREFIX_SOURCES[@]}"
"$TABPREFIX_JAVAC" "${TABPREFIX_COMPILER_OPTIONS[@]}" -encoding UTF-8 \
  -cp "$TABPREFIX_CLASSPATH" -d "$TABPREFIX_CLASSES" "${TABPREFIX_SOURCES[@]}"

tabprefix_merge_services() {
  local tabprefix_input="$1"
  local tabprefix_service tabprefix_service_name
  [[ -d "$tabprefix_input/META-INF/services" ]] || return 0
  while IFS= read -r -d '' tabprefix_service; do
    tabprefix_service_name="${tabprefix_service##*/}"
    cat "$tabprefix_service" >> "$TABPREFIX_SERVICES/$tabprefix_service_name"
    printf '\n' >> "$TABPREFIX_SERVICES/$tabprefix_service_name"
  done < <(find "$tabprefix_input/META-INF/services" -type f -print0)
}

printf 'Packaging runtime libraries...\n'
for tabprefix_path in "${TABPREFIX_RUNTIME_JARS[@]}"; do
  tabprefix_name="${tabprefix_path##*/}"
  TABPREFIX_EXTRACT="$TABPREFIX_WORK/extract"
  rm -rf "$TABPREFIX_EXTRACT"
  mkdir -p "$TABPREFIX_EXTRACT"
  unzip -oq "$tabprefix_path" -d "$TABPREFIX_EXTRACT"
  tabprefix_merge_services "$TABPREFIX_EXTRACT"

  if [[ -d "$TABPREFIX_EXTRACT/META-INF" ]]; then
    while IFS= read -r -d '' tabprefix_license; do
      tabprefix_license_dir="$TABPREFIX_PACKAGE/META-INF/licenses/$tabprefix_name"
      mkdir -p "$tabprefix_license_dir"
      cp "$tabprefix_license" "$tabprefix_license_dir/"
    done < <(find "$TABPREFIX_EXTRACT/META-INF" -type f \
      \( -iname 'LICENSE*' -o -iname 'NOTICE*' -o -iname 'COPYING*' \) -print0)
  fi
  rm -rf "$TABPREFIX_EXTRACT/META-INF/services" "$TABPREFIX_EXTRACT/META-INF/versions"
  rm -f "$TABPREFIX_EXTRACT/META-INF/MANIFEST.MF" "$TABPREFIX_EXTRACT/META-INF/INDEX.LIST"
  find "$TABPREFIX_EXTRACT" -type f \
    \( -name 'module-info.class' -o -iname '*.sf' -o -iname '*.rsa' \
       -o -iname '*.dsa' -o -iname '*.ec' \) -delete
  cp -R "$TABPREFIX_EXTRACT/." "$TABPREFIX_PACKAGE/"
done

tabprefix_merge_services "$TABPREFIX_ROOT/resources"
cp -R "$TABPREFIX_CLASSES/." "$TABPREFIX_PACKAGE/"
cp -R "$TABPREFIX_ROOT/resources/." "$TABPREFIX_PACKAGE/"
rm -rf "$TABPREFIX_PACKAGE/META-INF/services"

cat > "$TABPREFIX_WORK/relocation.rules" <<'RULES'
rule net.kyori.** me.snowsun.tabprefix.internal.kyori.@1
rule com.google.gson.** me.snowsun.tabprefix.internal.gson.@1
rule org.slf4j.** me.snowsun.tabprefix.internal.slf4j.@1
rule org.jetbrains.annotations.** me.snowsun.tabprefix.internal.annotations.@1
RULES

"$TABPREFIX_JAR" cf "$TABPREFIX_WORK/unshaded.jar" -C "$TABPREFIX_PACKAGE" .
printf 'Isolating embedded libraries...\n'
"$TABPREFIX_JAVA" -cp "$TABPREFIX_TOOL_CLASSPATH" com.eed3si9n.jarjar.Main \
  process "$TABPREFIX_WORK/relocation.rules" "$TABPREFIX_WORK/unshaded.jar" "$TABPREFIX_WORK/TabPrefix.jar"

# ServiceLoader descriptors require relocation of both names and contents.
TABPREFIX_RELOCATED_SERVICES="$TABPREFIX_WORK/relocated/META-INF/services"
mkdir -p "$TABPREFIX_RELOCATED_SERVICES"
tabprefix_relocate() {
  sed -e 's/net\.kyori\./me.snowsun.tabprefix.internal.kyori./g' \
      -e 's/com\.google\.gson\./me.snowsun.tabprefix.internal.gson./g' \
      -e 's/org\.slf4j\./me.snowsun.tabprefix.internal.slf4j./g' \
      -e 's/org\.jetbrains\.annotations\./me.snowsun.tabprefix.internal.annotations./g'
}
for tabprefix_service in "$TABPREFIX_SERVICES"/*; do
  [[ -f "$tabprefix_service" ]] || continue
  tabprefix_service_name="$(printf '%s' "${tabprefix_service##*/}" | tabprefix_relocate)"
  tabprefix_relocate < "$tabprefix_service" | \
    awk '{sub(/#.*/, ""); gsub(/^[ \t]+|[ \t]+$/, "")} NF && !seen[$0]++' \
    > "$TABPREFIX_RELOCATED_SERVICES/$tabprefix_service_name"
done
"$TABPREFIX_JAR" uf "$TABPREFIX_WORK/TabPrefix.jar" -C "$TABPREFIX_WORK/relocated" META-INF/services

unzip -tq "$TABPREFIX_WORK/TabPrefix.jar" >/dev/null
unzip -l "$TABPREFIX_WORK/TabPrefix.jar" 'me/snowsun/tabprefix/TabPrefix.class' \
  | awk '$4 == "me/snowsun/tabprefix/TabPrefix.class" {found=1} END {exit !found}' \
  || tabprefix_fail "Main class me.snowsun.tabprefix.TabPrefix is missing."
cp "$TABPREFIX_WORK/TabPrefix.jar" "$TABPREFIX_ROOT/dist/.TabPrefix.jar.tmp"
mv -f "$TABPREFIX_ROOT/dist/.TabPrefix.jar.tmp" "$TABPREFIX_ROOT/dist/TabPrefix.jar"
printf 'Built: %s/dist/TabPrefix.jar\n' "$TABPREFIX_ROOT"
