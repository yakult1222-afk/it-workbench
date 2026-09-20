#!/usr/bin/env bash
# 一体化打包：构建前端 -> 复制到后端静态资源 -> 打后端可执行 jar
# 产物：backend/target/it-workbench-1.0.0.jar（单端口 8080 同时提供页面与 API）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$ROOT_DIR/backend"
STATIC_DIR="$BACKEND_DIR/src/main/resources/static"

echo "==> 1/3 构建前端"
cd "$FRONTEND_DIR"
npm install --no-fund --no-audit
npm run build

echo "==> 2/3 复制前端产物到后端静态资源目录"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -R "$FRONTEND_DIR/dist/." "$STATIC_DIR/"

echo "==> 3/3 打包后端"
cd "$BACKEND_DIR"
mvn clean package -DskipTests

echo "==> 完成：$BACKEND_DIR/target/it-workbench-1.0.0.jar"
