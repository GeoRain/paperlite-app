#!/usr/bin/env bash
# PaperLite 构建入口。
#
# 本机 JDK / Gradle / SDK 都在 E:/pl_tools 下，不属于系统 PATH，
# 所以每次构建前先在这里固定环境变量，避免依赖外部 shell 状态。
set -euo pipefail

export JAVA_HOME="E:\\pl_tools\\jdk-21.0.13+9"
export ANDROID_HOME="E:\\pl_tools\\sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="/e/pl_tools/jdk-21.0.13+9/bin:$PATH"

GRADLE="/e/pl_tools/gradle-9.7.1/bin/gradle"
cd "$(dirname "$0")"

echo "JAVA_HOME=$JAVA_HOME"
"$GRADLE" "$@" --console=plain
