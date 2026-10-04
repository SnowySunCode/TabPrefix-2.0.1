#!/usr/bin/env bash
set -euo pipefail

TABPREFIX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
TABPREFIX_OFFLINE=false

case "${1:-}" in
  "") ;;
  --offline) TABPREFIX_OFFLINE=true ;;
  *) printf 'Usage: bash setup.sh [--offline]\n' >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { printf 'Too many arguments.\n' >&2; exit 1; }

tabprefix_fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for tabprefix_tool in java javac jar; do
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/$tabprefix_tool" ]]; then
    tabprefix_path="$JAVA_HOME/bin/$tabprefix_tool"
  else
    tabprefix_path="$(command -v "$tabprefix_tool" || true)"
  fi
  [[ -n "$tabprefix_path" ]] || tabprefix_fail "Missing $tabprefix_tool. Install JDK 8 or newer."
  case "$tabprefix_tool" in
    java) TABPREFIX_JAVA="$tabprefix_path" ;;
    javac) TABPREFIX_JAVAC="$tabprefix_path" ;;
    jar) TABPREFIX_JAR="$tabprefix_path" ;;
  esac
done

TABPREFIX_COMPILER_VERSION="$("$TABPREFIX_JAVAC" -version 2>&1)"
if [[ "$TABPREFIX_COMPILER_VERSION" =~ ^javac\ (1\.)?([0-9]+) ]]; then
  [[ "${BASH_REMATCH[2]}" -ge 8 ]] || tabprefix_fail "JDK 8 or newer is required."
else
  tabprefix_fail "Cannot detect javac version: $TABPREFIX_COMPILER_VERSION"
fi

command -v unzip >/dev/null || tabprefix_fail "Missing unzip."
if [[ "$TABPREFIX_OFFLINE" == false ]]; then
  command -v curl >/dev/null || tabprefix_fail "Missing curl."
fi
[[ -f "$TABPREFIX_ROOT/dependencies.conf" ]] || tabprefix_fail "Missing dependencies.conf."
source "$TABPREFIX_ROOT/dependencies.conf"
[[ -f "$TABPREFIX_ROOT/dependencies.sha256" ]] || tabprefix_fail "Missing dependencies.sha256."
if command -v sha256sum >/dev/null; then
  TABPREFIX_HASH_TOOL=(sha256sum)
elif command -v shasum >/dev/null; then
  TABPREFIX_HASH_TOOL=(shasum -a 256)
else
  tabprefix_fail "Missing sha256sum or shasum."
fi
tabprefix_verify() {
  local tabprefix_file="$1" tabprefix_key="$2" tabprefix_expected tabprefix_actual
  tabprefix_expected="$(awk -v key="$tabprefix_key" '$2 == key {print $1}' "$TABPREFIX_ROOT/dependencies.sha256")"
  [[ "$tabprefix_expected" =~ ^[a-f0-9]{64}$ ]] || tabprefix_fail "Missing checksum: $tabprefix_key"
  [[ -f "$tabprefix_file" ]] || return 1
  tabprefix_actual="$("${TABPREFIX_HASH_TOOL[@]}" "$tabprefix_file")"
  [[ "${tabprefix_actual%% *}" == "$tabprefix_expected" ]]
}

for tabprefix_dir in .build .build/deps .build/deps/provided .build/deps/runtime .build/deps/tool; do
  [[ ! -L "$TABPREFIX_ROOT/$tabprefix_dir" ]] || tabprefix_fail "Build directory is a symlink: $tabprefix_dir"
done
mkdir -p "$TABPREFIX_ROOT/.build/deps/"{provided,runtime,tool}

for tabprefix_entry in "${TABPREFIX_DEPENDENCIES[@]}"; do
  IFS='|' read -r tabprefix_scope tabprefix_name tabprefix_url <<< "$tabprefix_entry"
  case "$tabprefix_scope" in
    provided|runtime|tool) ;;
    *) tabprefix_fail "Invalid dependency scope: $tabprefix_scope" ;;
  esac
  [[ "$tabprefix_name" != */* && "$tabprefix_name" == *.jar ]] || tabprefix_fail "Invalid JAR filename."
  [[ "$tabprefix_url" == https://* ]] || tabprefix_fail "Dependency URL must use HTTPS."
  tabprefix_target="$TABPREFIX_ROOT/.build/deps/$tabprefix_scope/$tabprefix_name"

  if tabprefix_verify "$tabprefix_target" "$tabprefix_scope/$tabprefix_name" && unzip -tq "$tabprefix_target" >/dev/null 2>&1; then
    continue
  fi
  [[ "$TABPREFIX_OFFLINE" == false ]] || tabprefix_fail "Missing or invalid cached dependency: $tabprefix_name"

  printf 'Downloading %s\n' "$tabprefix_name"
  tabprefix_download="$(mktemp "$TABPREFIX_ROOT/.build/deps/$tabprefix_scope/.download.XXXXXX")"
  if ! curl --fail --location --silent --show-error --retry 3 \
    --connect-timeout 15 --max-time 180 --proto '=https' --proto-redir '=https' \
    "$tabprefix_url" -o "$tabprefix_download"; then
    rm -f "$tabprefix_download"
    tabprefix_fail "Download failed: $tabprefix_name"
  fi
  if ! unzip -tq "$tabprefix_download" >/dev/null 2>&1; then
    rm -f "$tabprefix_download"
    tabprefix_fail "Downloaded file is not a valid JAR: $tabprefix_name"
  fi
  if ! tabprefix_verify "$tabprefix_download" "$tabprefix_scope/$tabprefix_name"; then
    rm -f "$tabprefix_download"
    tabprefix_fail "SHA-256 mismatch: $tabprefix_name"
  fi
  mv -f "$tabprefix_download" "$tabprefix_target"
done

printf 'Dependencies ready.\n'
