#!/usr/bin/env bash
# ============================================================
# MixAuth 登录链集成测试聚合脚本（run-login-it.bat 的 POSIX 版本，行为一致）
#   默认同时运行离线注册/登录链与在线预检两层（全 5 个版本）；--offline /
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
# JDK25_HOME / JDK21_HOME、JAVA_HOME。
# 机器特定配置可放项目根 .env（KEY=VALUE，# 注释），脚本启动时自动加载。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

# 加载项目根目录 .env（若存在）：每行 KEY=VALUE，# 开头为注释。
# 已定义的环境变量优先，.env 仅填充缺失项（系统级/命令行设置优先）。
load_env() {
  [[ -f ".env" ]] || return 0
  local key value
  while IFS='=' read -r key value; do
    # 兼容 CRLF 行尾（Windows 记事本等工具创建的 .env）
    key="${key%$'\r'}"
    value="${value%$'\r'}"
    [[ -z "$key" || "$key" == \#* ]] && continue
    value="${value%\"}"
    value="${value#\"}"
    if [[ -z "${!key:-}" ]]; then
      export "$key=$value"
    fi
  done < .env
}
load_env

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

# 解析 JDK 25 安装路径（不硬编码任何机器特定目录）：
#   1) JDK25_HOME 环境变量（推荐，如 export JDK25_HOME=/path/to/jdk-25）
#   2) JAVA_HOME（若其 release 文件声明 Java 25）
#   3) 常见安装位置自动探测（/usr/lib/jvm、SDKMAN、/opt 下的 jdk-25*）
#   4) 均未找到 → 报错退出
resolve_jdk25() {
  local d
  if [[ -n "${JDK25_HOME:-}" ]]; then
    if [[ -x "$JDK25_HOME/bin/java" || -x "$JDK25_HOME/bin/java.exe" ]]; then
      JDK25="$JDK25_HOME"
      echo "  使用 JDK 25: $JDK25"
      return 0
    fi
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    if [[ -x "$JAVA_HOME/bin/java" || -x "$JAVA_HOME/bin/java.exe" ]]; then
      if grep -q 'JAVA_VERSION="25' "$JAVA_HOME/release" 2>/dev/null; then
        JDK25="$JAVA_HOME"
        echo "  使用 JDK 25: $JDK25"
        return 0
      fi
    fi
  fi
  for d in /usr/lib/jvm/jdk-25* "$HOME/.sdkman/candidates/java/25*" /opt/jdk-25*; do
    if [[ -d "$d" && ( -x "$d/bin/java" || -x "$d/bin/java.exe" ) ]]; then
      JDK25="$d"
      echo "  使用 JDK 25: $JDK25"
      return 0
    fi
  done
  echo "[ERROR] 未找到 JDK 25。请设置环境变量 JDK25_HOME 指向 JDK 25 安装目录后重试。" >&2
  exit 1
}

it() {
  local mc="$1" neo="$2" pmc="$3" pmap="$4" mrange="$5"
  if [[ -n "$ONLY" && "$ONLY" != "$mc" ]]; then return 0; fi

  # 26.1 起 Minecraft 使用 Java 25，Gradle 守护进程须以 JDK 25 运行；
  # 其余版本沿用系统默认 JDK（21）。
  if [[ "$mc" == "26.1.2" || "$mc" == "26.2" ]]; then
    resolve_jdk25
    export JAVA_HOME="$JDK25"
  else
    unset JAVA_HOME 2>/dev/null || true
  fi

  echo "=== integrationTest（$LAYER：offline=离线链 / online=在线预检 / all=全部）MC $mc / NeoForge $neo ==="
  rm -rf build/classes build/libs build/resources build/reports/integrationTest

  ./gradlew integrationTest --no-configuration-cache \
    "${TEST_FILTER[@]}" \
    "${EXTRA[@]}" \
    -Pminecraft_version="$mc" \
    -Pneo_version="$neo" \
    -Pparchment_minecraft_version="$pmc" \
    -Pparchment_mappings_version="$pmap" \
    -Pminecraft_version_range="$mrange"
  echo "=== DONE $mc ==="
}

# 版本矩阵单一数据源：version-matrix.txt（mc|neo|parchmentMc|parchmentMap|mcRange）
while IFS='|' read -r mc neo pmc pmap mrange || [[ -n "$mc" ]]; do
  mrange="${mrange%$'\r'}"
  [[ "$mc" == \#* || -z "$mc" ]] && continue
  it "$mc" "$neo" "$pmc" "$pmap" "$mrange"
done < version-matrix.txt
