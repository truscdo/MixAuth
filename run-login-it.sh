#!/usr/bin/env bash
# ============================================================
# MixAuth 登录链集成测试聚合脚本（run-login-it.bat 的 POSIX 版本，行为一致）
#   默认同时运行离线注册/登录链与在线预检两层（全 4 个版本）；--offline /
#   --online 只跑其中一层
#
# 用法：
#   ./run-login-it.sh                 两层全跑，全 4 个版本
#   ./run-login-it.sh --offline       仅离线注册/登录链
#   ./run-login-it.sh --online        仅在线预检（需要本地 mock sessionserver）
#   ./run-login-it.sh 1.21.5          仅指定版本
#   ./run-login-it.sh 1.21.5 --offline --tests "*OfflineChainIT*"   额外参数透传 gradlew integrationTest
#
# 环境变量：MCC_EXE / MCC_DIR、RCON_PW、RCON_PORT、SPORT、MOCK_PORT、
# JDK_EXE、JAVA_HOME。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

ONLY="${1:-}"
LAYER=all
EXTRA=()
for a in "${@:2}"; do
  if [[ "$a" == "--offline" ]]; then
    LAYER=offline
  elif [[ "$a" == "--online" ]]; then
    LAYER=online
  else
    EXTRA+=("$a")
  fi
done
if [[ "$LAYER" == offline ]]; then
  TEST_FILTER=(-Plct.layer=offline)
elif [[ "$LAYER" == online ]]; then
  TEST_FILTER=(-Plct.layer=online)
elif [[ ${#EXTRA[@]} -eq 0 ]]; then
  TEST_FILTER=(-Plct.layer=all)
else
  TEST_FILTER=()
fi

it() {
  local mc="$1" neo="$2" pmc="$3" pmap="$4" mrange="$5"
  if [[ -n "$ONLY" && "$ONLY" != "$mc" ]]; then return 0; fi

  echo "=== integrationTest（$LAYER：offline=离线链 / online=在线预检 / all=全部）MC $mc / NeoForge $neo ==="
  rm -rf build/classes build/libs build/resources build/reports/integrationTest

  L3_CLEAN_BUILD=false ./gradlew integrationTest --no-configuration-cache \
    "${TEST_FILTER[@]}" \
    "${EXTRA[@]}" \
    -Pminecraft_version="$mc" \
    -Pneo_version="$neo" \
    -Pparchment_minecraft_version="$pmc" \
    -Pparchment_mappings_version="$pmap" \
    -Pminecraft_version_range="$mrange"
  echo "=== DONE $mc ==="
}

it 1.21.1 21.1.1 1.21.1 2024.11.17 "[1.21.1]"
it 1.21.5 21.5.98 1.21.5 2025.06.15 "[1.21.5]"
it 1.21.8 21.8.54 1.21.8 2025.09.14 "[1.21.8]"
it 1.21.11 21.11.45 1.21.11 2025.12.20 "[1.21.11]"