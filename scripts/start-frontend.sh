#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
command -v node >/dev/null 2>&1 || { echo '错误：未找到 Node.js 20.19+ 或 22.12+。'; exit 1; }
command -v npm >/dev/null 2>&1 || { echo '错误：未找到 npm。'; exit 1; }

cd "$PROJECT_ROOT/frontend"
if [[ ! -d node_modules ]]; then
  npm ci --no-fund
fi
npm run dev
