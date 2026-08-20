#!/bin/sh
# ============================================================
# MixAuth login-chain test - shell wrapper for RunLoginChain.java
# (stage B cross-platform orchestrator; POSIX entry point)
# Usage: ./run-login-chain <mc-version> [--mock] [--trusted]
#                          [--user NAME] [--no-build] [--scenario ID]
# Env overrides: MCC_EXE, MCC_DIR, JDK_EXE, JAVA_HOME, RCON_PW, RCON_PORT,
# SPORT, MOCK_PORT (same fallback chain as the original .bat).
# ============================================================
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$(dirname "$DIR")" || exit 1   # project root (RunLoginChain resolves paths relative to cwd)

JAVA_BIN="${JDK_EXE:-}"
if [ -z "$JAVA_BIN" ] && [ -n "$JAVA_HOME" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi
if [ -z "$JAVA_BIN" ]; then
  JAVA_BIN=java
fi

if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "ERROR: java not found: $JAVA_BIN - set JDK_EXE or JAVA_HOME" >&2
  exit 1
fi
exec "$JAVA_BIN" --source 21 "$DIR/RunLoginChain.java" "$@"