#!/usr/bin/env bash
# 启动 PsyCare 后端（本机 mvn CLI 损坏，故绕开 mvn 直调 java）。
#
# 前置：target/classes 必须是最新编译产物；依赖类路径文件 target/cp.txt 需存在。
#   编译：见 maven-compile-win skill（JDK17 + plexus-classworlds launcher）
#   生成 cp.txt：
#     ...Launcher -o -B dependency:build-classpath -Dmdep.outputFile=target/cp.txt -DincludeScope=runtime
#
# 用法：bash .workbuddy/scripts/run-server.sh
# 停止：kill 掉 18989 端口的监听进程（或 Ctrl+C）
set -u
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

JDK="/c/Users/Lenovo/.jdks/ms-17.0.18"
SERVER="/d/WorkBuddy/2026-09-23-14-52-33/community-mental-health/psycare-server"

cd "$SERVER" || { echo "找不到项目目录: $SERVER"; exit 1; }

if [ ! -f target/cp.txt ]; then
  echo "缺少 target/cp.txt（依赖类路径文件）。请先执行 dependency:build-classpath，见脚本头部注释。"
  exit 1
fi

# Windows 的 -cp 用分号分隔；target/classes 必须排在最前，保证加载的是本地最新字节码
CP="target/classes;$(cat target/cp.txt)"

exec "$JDK/bin/java.exe" \
  -Dfile.encoding=UTF-8 \
  -Dspring.output.ansi.enabled=always \
  -cp "$CP" \
  com.cmh.psycare.PsycareApplication
