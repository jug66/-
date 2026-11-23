#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
NAME="${1:-你的姓名}"
SAFE_NAME="$(printf '%s' "$NAME" | tr '/:*?"<>|' '_')"
[[ -n "$SAFE_NAME" ]] || SAFE_NAME='你的姓名'
OUTPUT_DIR="$PROJECT_ROOT/output"
ARCHIVE="$OUTPUT_DIR/雪哈游-全栈-笔试题2-${SAFE_NAME}-源码.zip"
mkdir -p "$OUTPUT_DIR"
rm -f "$ARCHIVE"
cd "$PROJECT_ROOT"

INCLUDE=(
  START_HERE.md PROJECT_OVERVIEW.md .env.example compose.yml
  backend/pom.xml backend/src
  frontend/index.html frontend/package.json frontend/package-lock.json
  frontend/tsconfig.json frontend/vite.config.ts frontend/public frontend/src frontend/tests
  scripts/environment.ps1 scripts/start-backend.ps1 scripts/start-backend.sh
  scripts/start-frontend.ps1 scripts/start-frontend.sh
  scripts/stop-backend.ps1 scripts/stop-backend.sh scripts/test.ps1
)

if command -v zip >/dev/null 2>&1; then
  zip -rq "$ARCHIVE" "${INCLUDE[@]}"
elif command -v tar >/dev/null 2>&1; then
  tar -a -c -f "$ARCHIVE" "${INCLUDE[@]}"
else
  echo '错误：需要 zip 或 tar。' >&2
  exit 1
fi
du -h "$ARCHIVE"
echo '压缩包只包含：源代码、必要配置、启动脚本、START_HERE.md 和 PROJECT_OVERVIEW.md。'
