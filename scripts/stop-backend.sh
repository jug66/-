#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="$PROJECT_ROOT/.runtime"
mkdir -p "$RUNTIME_DIR"
touch "$RUNTIME_DIR/stop-backend"
PID_FILE="$RUNTIME_DIR/backend.pid"
if [[ -f "$PID_FILE" ]]; then
  PID="$(tr -d '[:space:]' < "$PID_FILE")"
  for _ in {1..20}; do
    kill -0 "$PID" 2>/dev/null || break
    sleep 0.25
  done
fi
echo '已请求本项目本地后端停止。若仍在运行，请回到启动窗口按 Ctrl+C。'
