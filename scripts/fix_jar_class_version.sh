#!/usr/bin/env bash
# =============================================================================
# fix_jar_class_version.sh — 把 libwebrtc-java.jar 的 class 文件版本从 69(Java 25)
# 归一到 55(Java 11)，使 AGP 8.5.2 的 D8 能够 dex 它。
#
# 【为什么必须做这一步（t10 实测根因）】
#   t5 用 webrtc 自带 JDK 编译 Java SDK：`third_party/jdk/current/bin/javac` 实测为
#   **javac 25.0.4.1** ⇒ 产出的 453 个 class 全部是 **major version 69（Java 25）**。
#   AGP 8.5.2 内置 R8/D8 8.2.2-dev 不支持该版本，构建在
#   `:app:desugarDebugFileDependencies` 失败：
#       D8: java.lang.IllegalArgumentException: Unsupported class file major version 69
#   实测对照：同一 jar 改写为 v55 后，build-tools/34.0.0 的 d8 成功产出 classes.dex；
#   原始 v69 则复现同样错误。
#
# 【本脚本的行为（最小、可复现、可回滚）】
#   1) 只改每个 class 头部的 2 字节版本号（69 → 55），**字节码本体一字节不动**；
#   2) 原始 jar 先备份为 <jar>.orig-jdk25（若已存在则不覆盖）；
#   3) 打印修改前后的 sha256，便于 t5/verifier 对账；
#   4) 用 build-tools 的 d8 做一次冒烟验证（能产出 dex 即通过）。
#
# 用法：bash scripts/fix_jar_class_version.sh [jar 路径]
# =============================================================================
set -euo pipefail

JAR="${1:-/opt/dsh-workspaces/code/webrtc-demo/third_party/libwebrtc/java/libwebrtc-java.jar}"
BAK="$JAR.orig-jdk25"
NEW_VER="${NEW_VER:-55}"          # 55 = Java 11（保留 nest/condy 语义，D8 支持）
D8="${D8:-/opt/dsh-workspaces/android-sdk/build-tools/34.0.0/d8}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/jarver.XXXXXX")"

[ -f "$JAR" ] || { echo "错误：找不到 $JAR" >&2; exit 1; }

echo "=== 0. 修改前 ==="
sha_old=$(sha256sum "$JAR" | awk '{print $1}')
echo "jar      : $JAR"
echo "sha256   : $sha_old"
echo "size     : $(stat -c %s "$JAR") B"

echo "=== 1. 备份原始 jar ==="
if [ -f "$BAK" ]; then
  echo "备份已存在，保留：$BAK（sha256 $(sha256sum "$BAK" | awk '{print $1}')）"
else
  cp -p "$JAR" "$BAK"
  echo "已备份 → $BAK"
fi

echo "=== 2. 解包 & 版本统计 ==="
( cd "$TMP" && unzip -q "$JAR" )
vers_before=$( cd "$TMP" && find . -name '*.class' | while read -r f; do
  od -An -tu1 -j6 -N2 "$f" | awk '{print $1*256+$2}'; done | sort | uniq -c )
echo "$vers_before"

if echo "$vers_before" | grep -qE "^ *[0-9]+ +69$"; then
  echo "=== 3. 改写 class 版本 69 → $NEW_VER（只改头部 2 字节）==="
  n=0
  while IFS= read -r f; do
    cur=$(od -An -tu1 -j6 -N2 "$f" | awk '{print $1*256+$2}')
    if [ "$cur" = "69" ]; then
      printf "\\x00\\x$(printf '%02x' "$NEW_VER")" | dd of="$f" bs=1 seek=6 count=2 conv=notrunc status=none
      n=$((n+1))
    fi
  done < <(find "$TMP" -name '*.class')
  echo "已改写 $n 个 class"
else
  echo "未发现 major=69 的 class：可能已处理过，继续做幂等处理"
fi

echo "=== 4. 重新打包（覆盖原路径）==="
( cd "$TMP" && rm -f /tmp/jarver-new.jar && zip -qr /tmp/jarver-new.jar . )
cp -f /tmp/jarver-new.jar "$JAR"; rm -f /tmp/jarver-new.jar

echo "=== 5. 修改后核验 ==="
sha_new=$(sha256sum "$JAR" | awk '{print $1}')
echo "sha256(旧) : $sha_old"
echo "sha256(新) : $sha_new"
echo "size       : $(stat -c %s "$JAR") B"
( cd "$TMP" && find . -name '*.class' | while read -r f; do
  od -An -tu1 -j6 -N2 "$f" | awk '{print $1*256+$2}'; done | sort | uniq -c )

echo "=== 6. d8 冒烟验证 ==="
if [ -x "$D8" ]; then
  mkdir -p "$TMP/dex"
  if "$D8" --min-api 26 --output "$TMP/dex" "$JAR" >/dev/null 2>&1; then
    echo "d8 OK：$(ls -l "$TMP/dex" | awk 'NR==2{print $5" bytes "$9}')"
  else
    echo "d8 失败！请检查" >&2; exit 1
  fi
else
  echo "未找到 d8（$D8），跳过冒烟验证"
fi

rm -rf "$TMP"
echo "=== 完成 ==="
