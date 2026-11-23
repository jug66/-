#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

die() { printf '错误：%s\n' "$*" >&2; exit 1; }
command -v java >/dev/null 2>&1 || die '未找到 java，请安装 JDK 21 并设置 PATH。'
command -v mvn >/dev/null 2>&1 || die '未找到 mvn，请安装 Maven 3.6.3+ 并设置 PATH。'

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java)"
fi

JAVA_VERSION="$($JAVA -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')"
[[ "$JAVA_VERSION" == "21" ]] || die "需要 JDK 21，当前检测到 Java ${JAVA_VERSION:-未知}。"

EXTERNAL=false
if [[ "${1:-}" == "--external" || "${1:-}" == "-e" ]]; then
  EXTERNAL=true
elif [[ -n "${1:-}" ]]; then
  die "未知参数：$1；可用参数为 --external。"
fi

cd "$PROJECT_ROOT/backend"
if $EXTERNAL; then
  echo '正在构建后端 JAR，并连接外部 PostgreSQL。'
  mvn -B -DskipTests package
  exec "$JAVA" -jar target/message-board-1.0.0.jar
fi

echo '正在启动本地 PostgreSQL（127.0.0.1:55432）和 API（127.0.0.1:8080）。按 Ctrl+C 停止。'
mvn -B test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/dev-classpath.txt \
  -DincludeScope=test
DEPENDENCIES="$(tr -d '\r\n' < target/dev-classpath.txt)"
exec "$JAVA" -cp "target/test-classes:target/classes:$DEPENDENCIES" dev.messageboard.DevApplication
