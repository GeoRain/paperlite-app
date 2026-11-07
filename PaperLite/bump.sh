#!/usr/bin/env bash
# PaperLite 版本号递增。
#
# 规则：每次更新版本号 +0.1（1.0 → 1.1 → 1.2 …），
# 同时 versionCode +1（整数必须单调递增，系统才认升级）。
#
# 用法：
#   ./bump.sh          递增并写回 app/build.gradle.kts
#   ./bump.sh 2.3      直接指定版本号
set -euo pipefail
cd "$(dirname "$0")"

GRADLE_FILE="app/build.gradle.kts"

if [[ $# -ge 1 ]]; then
  NEW_NAME="$1"
else
  CUR=$(grep -oE 'versionName = "[^"]+"' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+\.[0-9]+')
  if [[ -z "$CUR" ]]; then
    echo "读不到当前 versionName，请手动指定：./bump.sh 1.1" >&2
    exit 1
  fi
  MAJ=${CUR%%.*}
  MIN=${CUR##*.}
  NEW_MIN=$((10#$MIN + 1))
  NEW_NAME="$MAJ.$NEW_MIN"
fi

CUR_CODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+')
NEW_CODE=$((CUR_CODE + 1))

# 用 | 分隔，避免 sed 里出现斜杠转义
sed -i "s|versionCode = $CUR_CODE|versionCode = $NEW_CODE|" "$GRADLE_FILE"
sed -i "s|versionName = \"[^\"]*\"|versionName = \"$NEW_NAME\"|" "$GRADLE_FILE"

echo "版本号：$CUR → $NEW_NAME"
echo "versionCode：$CUR_CODE → $NEW_CODE"
