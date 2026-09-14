#!/bin/bash
# =============================================================================
# build_java_sdk_with_jni.sh — 生成"完整"的 libwebrtc Java SDK jar（t23 修复）
#
# 背景（真机缺陷）
#   t5 交付的 libwebrtc-java.jar 只取了 out/Release-arm64/lib.java/sdk/android/libwebrtc.jar
#   （453 类），其中 **不含任何 jni_zero 生成的 *Jni 绑定实现类**（`*Jni.class` = 0）。
#   真机执行 PeerConnectionFactory.initialize(...) 抛
#   NoClassDefFoundError: org.webrtc.PeerConnectionFactoryJni → "WebRTC 引擎初始化失败"。
#
# 根因
#   libwebrtc.jar 由 GN 的 dist_jar 经 zip.py 打包，输入是
#   `@FileArg(gen/sdk/android/libwebrtc.build_config.json:dist_classpath)`，
#   而 jni_zero 生成的 `generated_*_jni_java` 是**传递依赖**，被 direct_deps_only 排除
#   ⇒ 这些 *Jni 类从未进入 libwebrtc.jar。
#
# 本脚本（幂等、可复跑）：
#   1. 校验 --release 修补已生效（compile_java.py / turbine.py 实测值必须为 17）
#   2. 取交付 jar 的原有 453 类（**原字节不变**）
#   3. 合并 48 个 *Jni 绑定类：
#        - 45 个取自构建系统自身的 `obj/sdk/android/generated_*_jni_java.javac.jar`
#        - 3 个（LoggingJni/CommonApisJni/JniZeroJni）构建系统未编译，由本脚本用
#          **各模块自己的 compliment.jar 作 classpath** 编译产出（见下方说明）
#   4. 合并 `org/jni_zero/GEN_JNI`：各 compliment.jar 里是**分模块的部分声明**，
#      本脚本取**并集**合成一个完整的 GEN_JNI（否则 *Jni 类运行期必 NoClassDefFoundError）
#   5. 以 STORED 方式（原始 jar 即 `--no-compress`）确定性重新打包
#   6. 跑 scripts/check_jar_link_integrity.py 做链路完整性检查
#
# 说明（取舍理由，任务要求写明）
#   * 选择 merge 而非重跑全量 t5：*Jni 类必须来自构建系统自身产物；已在树内编译好的
#     45 个直接复用，未编译的 3 个按**同一个 jni_zero codegen 的源**就地编译，避免
#     重跑整条链路。
#   * 3 个未编译类的原因：构建系统里 `generated_logging_jni_java.javac.jar` 是**空 zip**，
#     且 `LoggingJni/CommonApisJni/JniZeroJni.class` 在整个 out/ 树中不存在 —— 其
#     GEN_JNI 声明只出现在各自的 compliment.jar（`rtc_base/base_java_jni_java.compliment.jar`、
#     `third_party/jni_zero/generate_jni_java.compliment.jar`），因此用它们作 classpath 编译。
#
# 用法：
#   bash scripts/build_java_sdk_with_jni.sh [--apply]
#     默认只构建到 <WS>/tmp/jni-merge/ 下的 staging 目录（不覆盖交付物）
#     --apply 才把结果写回 third_party/libwebrtc/java/libwebrtc-java.jar 并同步 AAR
# =============================================================================
set -uo pipefail

WS=${WS:-/opt/dsh-workspaces}
BUILD=${BUILD:-$WS/webrtc-build}
SRC=${SRC:-$BUILD/src}
OUT=${OUT:-$SRC/out/Release-arm64}
TP=${TP:-$WS/code/webrtc-demo/third_party}
SCRIPTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DELIVERED_JAR=$TP/libwebrtc/java/libwebrtc-java.jar
AAR=${AAR:-$TP/libwebrtc/java/libwebrtc-arm64.aar}
SO=${SO:-$TP/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so}
# 基线 jar：t16/t17 的 453 类 v61 jar（**不是** DELIVERED_JAR，否则重跑时守恒校验会自比自）
BASELINE=${BASELINE:-$TP/libwebrtc/java/libwebrtc-java.jar.v61-java17}
# 打包时间戳归一（保证逐字节可复现）；默认 2026-01-01T00:00:00Z
SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1767225600}
export SOURCE_DATE_EPOCH
STAGE=${STAGE:-$WS/tmp/jni-merge}
JAVAC=${JAVAC:-$SRC/third_party/jdk/current/bin/javac}   # javac 25（配 --release 17）
JAVAP=${JAVAP:-$SRC/third_party/jdk/current/bin/javap}
APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

log() { echo "[$(date -u +%FT%TZ)] $*"; }
die() { log "FATAL: $*"; exit 1; }

[ -x "$JAVAC" ] || die "找不到 javac: $JAVAC"
[ -f "$BASELINE" ] || die "找不到基线 jar（453 类 v61）: $BASELINE（可用 BASELINE=<path> 指定）"
rm -rf "$STAGE"; mkdir -p "$STAGE/classes" "$STAGE/cp" "$STAGE/gen"

# ---------------------------------------------------------------------------
log "== 步骤 1/7：校验 --release 修补已生效 =="
CJ=$SRC/build/android/gyp/compile_java.py
TB=$SRC/build/android/gyp/turbine.py
cj=$(grep -oE "^        '[0-9]+',$" "$CJ" | head -1 | tr -dc '0-9')
tb=$(grep -oE "javac_cmd = \['--release', '[0-9]+'\]" "$TB" | head -1 | grep -oE "'[0-9]+'" | tr -dc '0-9')
log "   compile_java.py:711 = '$cj' ；turbine.py:110 = '$tb'"
[ "$cj" = "17" ] && [ "$tb" = "17" ] || die "修补未生效（期望 17，实测 cj=$cj tb=$tb）；请先跑 t5-build.sh patch"
log "   ✅ --release 17 修补已生效"

# ---------------------------------------------------------------------------
log "== 步骤 2/7：解出基线 jar 的原有 453 类（原字节） =="
log "   基线 = $BASELINE"
(cd "$STAGE/classes" && unzip -oq "$BASELINE")
base_n=$(find "$STAGE/classes" -name '*.class' | wc -l)
log "   原类数 = $base_n"
[ "$base_n" = "453" ] || die "基线 jar 不是 453 类（实测 $base_n）—— 拒绝以错误基线合并"
# 记录基线类清单，供收尾校验"原有类逐字节未变"
(cd "$STAGE/classes" && find . -name '*.class' | sed 's|^\./||' | LC_ALL=C sort) > "$STAGE/original_classes.txt"

# ---------------------------------------------------------------------------
log "== 步骤 3/7：收集构建系统已编译的 *Jni 绑定类（-n：绝不覆盖原有类） =="
: > "$STAGE/jni_from_build.txt"
for j in "$OUT"/obj/sdk/android/generated_*_jni_java.javac.jar; do
  [ -f "$j" ] || continue
  n=$(unzip -Z1 "$j" 2>/dev/null | grep -c 'Jni\.class$')
  [ "$n" = "0" ] && { log "   (空 jar 跳过) $(basename "$j")"; continue; }
  (cd "$STAGE/classes" && unzip -oq -n "$j")
  unzip -Z1 "$j" 2>/dev/null | grep 'Jni\.class$' >> "$STAGE/jni_from_build.txt"
  log "   + $(basename "$j")  ($n 个 *Jni)"
done
sort -u "$STAGE/jni_from_build.txt" -o "$STAGE/jni_from_build.txt"
log "   取自构建系统的 *Jni 唯一数 = $(wc -l < "$STAGE/jni_from_build.txt")"

# ---------------------------------------------------------------------------
log "== 步骤 4/7：编译构建系统未产出的 3 个 *Jni（用各自模块的 compliment.jar） =="
# 4a) LoggingJni -> rtc_base/base_java_jni_java.compliment.jar
# 4b) CommonApisJni/JniZeroJni -> third_party/jni_zero/generate_jni_java.compliment.jar
compile_one() {  # $1=源文件 $2=额外 classpath
  local f=$1 extra=$2
  "$JAVAC" --release 17 -nowarn -encoding UTF-8 \
      -cp "$STAGE/classes:$extra" -d "$STAGE/classes" "$f" 2>&1 | tail -3
}
LOGGING_SRC=$(find "$OUT/gen" -path '*input_srcjars*' -name 'LoggingJni.java' | head -1)
LOG_JNI_COMP=$OUT/obj/rtc_base/base_java_jni_java.compliment.jar
if [ -n "$LOGGING_SRC" ] && [ -f "$LOG_JNI_COMP" ]; then
  compile_one "$LOGGING_SRC" "$LOG_JNI_COMP" && log "   + LoggingJni 编译完成" || log "   ⚠️ LoggingJni 编译失败"
else
  log "   ⚠️ 缺 LoggingJni 源或其 compliment：$LOGGING_SRC / $LOG_JNI_COMP"
fi
JZ_COMP=$OUT/obj/third_party/jni_zero/generate_jni_java.compliment.jar
for nm in CommonApisJni JniZeroJni; do
  f=$(find "$OUT/gen" -path '*input_srcjars*' -name "$nm.java" | head -1)
  if [ -n "$f" ] && [ -f "$JZ_COMP" ]; then
    compile_one "$f" "$JZ_COMP" && log "   + $nm 编译完成" || log "   ⚠️ $nm 编译失败"
  else
    log "   ⚠️ 缺 $nm 源或其 compliment"
  fi
done

# ---------------------------------------------------------------------------
log "== 步骤 4b/7：补齐 compliment.jar 中其它缺失的 org/** 类（-n：绝不覆盖原有类；排除 android/** 平台桩） =="
mapfile -t COMPJARS < <(find "$OUT/obj" -name '*.compliment.jar' 2>/dev/null | sort)
for j in "${COMPJARS[@]}"; do
  (cd "$STAGE/classes" && unzip -oq -n "$j" 'org/*' -x 'org/jni_zero/GEN_JNI.class' 2>/dev/null) || true
done
log "   合并后 class 数 = $(find "$STAGE/classes" -name '*.class' | wc -l)"

# ---------------------------------------------------------------------------
log "== 步骤 5/7：合成完整 GEN_JNI（各 compliment.jar 里是分模块部分声明，取并集） =="
: > "$STAGE/genjni_raw.txt"
for j in "${COMPJARS[@]}"; do
  [ -f "$j" ] || continue
  rm -rf "$STAGE/cp"; mkdir -p "$STAGE/cp"
  unzip -oq "$j" org/jni_zero/GEN_JNI.class -d "$STAGE/cp" 2>/dev/null || continue
  [ -f "$STAGE/cp/org/jni_zero/GEN_JNI.class" ] || continue
  "$JAVAP" -p "$STAGE/cp/org/jni_zero/GEN_JNI.class" 2>/dev/null \
    | grep -E '^\s+public static native' \
    | sed 's/^\s*public static native //; s/;[[:space:]]*$//' >> "$STAGE/genjni_raw.txt"
done
# javap 行形如：void org_webrtc_Logging_log(int, java.lang.String, java.lang.String)
#   ⚠️ **必须保留返回类型**（先前版本误删返回类型 → 632 个编译错误）
# 按「方法名」去重；同名不同签名者保留首见并告警
awk '{
  name=$0; sub(/\(.*/, "", name); n=split(name, a, " "); mname=a[n]
  if (!(mname in seen)) { seen[mname]=$0; print $0 }
  else if (seen[mname] != $0) { print "CONFLICT:" mname > "/dev/stderr" }
}' "$STAGE/genjni_raw.txt" > "$STAGE/genjni_methods.txt" 2> "$STAGE/genjni_conflicts.txt"
nmethods=$(wc -l < "$STAGE/genjni_methods.txt")
log "   合成 GEN_JNI native 方法数 = $nmethods"
[ -s "$STAGE/genjni_conflicts.txt" ] && log "   WARN: 同名不同签名 $(wc -l < "$STAGE/genjni_conflicts.txt") 处（保留首见）"
{
  echo "package org.jni_zero;"
  echo "// 由 build_java_sdk_with_jni.sh 从各模块 compliment.jar 的 GEN_JNI 声明取并集合成。"
  echo "// 目的：真实构建树里 GEN_JNI 只以【分模块部分声明】形式分布于各 compliment.jar，"
  echo "//       而运行期需要一份覆盖全部 native 入口的完整 GEN_JNI。"
  echo "// 注意：javap 输出的形参只有类型没有名字，而 Java 方法声明**必须**有形参名，"
  echo "//       故此处为每个形参补上 argN（仅名字，类型/顺序不变）。"
  echo "public class GEN_JNI {"
  echo "  private GEN_JNI() {}"
} > "$STAGE/GEN_JNI.java"
python3 - "$STAGE/genjni_methods.txt" "$STAGE/GEN_JNI.java" <<'PYEOF'
import sys, re
src, dst = sys.argv[1], sys.argv[2]
out = []
for raw in open(src, encoding="utf-8"):
    line = raw.rstrip("\n").strip()
    if not line:
        continue
    m = re.match(r"^(.*?)\s+([A-Za-z_$][\w$]*)\s*\((.*)\)$", line)
    if not m:
        continue
    ret, name, params = m.group(1), m.group(2), m.group(3)
    named = []
    if params.strip():
        depth = 0
        cur = ""
        parts = []
        for ch in params:            # 按顶层逗号切分（保护泛型里的逗号）
            if ch == "<":
                depth += 1
            elif ch == ">":
                depth -= 1
            if ch == "," and depth == 0:
                parts.append(cur); cur = ""
            else:
                cur += ch
        parts.append(cur)
        for i, p in enumerate(parts):
            named.append("%s arg%d" % (p.strip(), i))
    out.append("  public static native %s %s(%s);" % (ret, name, ", ".join(named)))
with open(dst, "a", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
    f.write("}\n")
print("   generated methods:", len(out))
PYEOF
"$JAVAC" --release 17 -nowarn -encoding UTF-8 -d "$STAGE/classes" "$STAGE/GEN_JNI.java" 2>&1 | head -8
[ -f "$STAGE/classes/org/jni_zero/GEN_JNI.class" ] && log "   + 合成 GEN_JNI 编译完成" || log "   ⚠️ 合成 GEN_JNI 编译失败"

# ---------------------------------------------------------------------------
log "== 步骤 6/7：以 STORED 方式确定性打包（时间戳归一 ⇒ 逐字节可复现） =="
NEWJAR=$STAGE/libwebrtc-java.jar
rm -f "$NEWJAR"
# 归一化 mtime：否则本轮"新编译/合成"的 4 个类（LoggingJni/CommonApisJni/JniZeroJni/GEN_JNI）
# 会带上构建时刻，导致重跑时**内容相同但 sha256 不同**（实测差异仅此 4 条 zip 时间戳）。
find "$STAGE/classes" -type f -name '*.class' -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +
log "   时间戳已归一为 @$SOURCE_DATE_EPOCH（TZ=UTC 打包）"
( cd "$STAGE/classes" && find . -name '*.class' | sed 's|^\./||' | LC_ALL=C sort \
    | TZ=UTC zip -q -X -0 "$NEWJAR" -@ )
tot=$(find "$STAGE/classes" -name '*.class' | wc -l)
log "   新 jar = $NEWJAR"
log "   class 总数 = $tot ；大小 = $(stat -c%s "$NEWJAR") B"
log "   sha256 = $(sha256sum "$NEWJAR" | cut -d' ' -f1)"
log "   *Jni.class = $(unzip -Z1 "$NEWJAR" | grep -c 'Jni\.class$')"
log "   *Natives.class = $(unzip -Z1 "$NEWJAR" | grep -c 'Natives\.class$')"

# ---------------------------------------------------------------------------
log "== 步骤 6b/7：校验"原有 453 类逐字节未变"（去重、不重排无关内容） =="
ORIG_DIR=$STAGE/orig-check
rm -rf "$ORIG_DIR"; mkdir -p "$ORIG_DIR"
(cd "$ORIG_DIR" && unzip -oq "$DELIVERED_JAR")
same=0; diff=0; miss=0
while IFS= read -r f; do
  if [ ! -f "$STAGE/classes/$f" ]; then miss=$((miss+1)); continue; fi
  if cmp -s "$ORIG_DIR/$f" "$STAGE/classes/$f"; then same=$((same+1)); else diff=$((diff+1)); log "   CHANGED: $f"; fi
done < "$STAGE/original_classes.txt"
log "   原有类：逐字节相同=$same  内容不同=$diff  缺失=$miss"
[ "$diff" = "0" ] && [ "$miss" = "0" ] || log "   ⚠️ 原有类被改动/丢失（应全为 0）"
rm -rf "$ORIG_DIR"

# ---------------------------------------------------------------------------
log "== 步骤 7/7：链路完整性检查 =="
python3 "$SCRIPTS/check_jar_link_integrity.py" "$NEWJAR" > "$STAGE/integrity.txt" 2>&1
rc=$?
tail -25 "$STAGE/integrity.txt"
log "   checker 退出码 = $rc（0=通过；完整输出见 $STAGE/integrity.txt）"

# 版本分布（须全部 ≤ 61）
log "   class 版本分布：$(cd "$STAGE/classes" && find . -name '*.class' | while read -r c; do xxd -p -s6 -l2 "$c"; done | sort | uniq -c | awk '{printf "0x%s×%s ", $2, $1}')"

if [ "$APPLY" = "1" ]; then
  log "== --apply：写回交付 jar，并同步 AAR 内 classes.jar（保持 .so 条目字节不变） =="
  cp -f "$NEWJAR" "$DELIVERED_JAR"
  chown 1000:1000 "$DELIVERED_JAR" 2>/dev/null || true
  JARHASH=$(sha256sum "$DELIVERED_JAR" | cut -d' ' -f1)
  log "   已写回 $DELIVERED_JAR"
  log "   sha256 = $JARHASH"

  if [ -f "$AAR" ]; then
    W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
    SO_BEFORE=$(unzip -p "$AAR" jni/arm64-v8a/libjingle_peerconnection_so.so | sha256sum | cut -d' ' -f1)
    ENTRIES_BEFORE=$(unzip -Z1 "$AAR" | LC_ALL=C sort | tr '\n' ' ')
    cp -f "$AAR" "$W/aar"
    cp -f "$DELIVERED_JAR" "$W/classes.jar"
    touch -h -d "@$SOURCE_DATE_EPOCH" "$W/classes.jar"
    ( cd "$W" && TZ=UTC zip -q -X aar classes.jar )   # 默认 deflate，与 AAR 内 classes.jar 原风格一致
    NEW_AAR_HASH=$(sha256sum "$W/aar" | cut -d' ' -f1)
    CJ_IN=$(unzip -p "$W/aar" classes.jar | sha256sum | cut -d' ' -f1)
    SO_AFTER=$(unzip -p "$W/aar" jni/arm64-v8a/libjingle_peerconnection_so.so | sha256sum | cut -d' ' -f1)
    ENTRIES_AFTER=$(unzip -Z1 "$W/aar" | LC_ALL=C sort | tr '\n' ' ')
    log "   AAR 内 classes.jar sha256 = $CJ_IN（须 = $JARHASH）"
    log "   AAR 内 .so   sha256 = $SO_AFTER（同步前 $SO_BEFORE）"
    log "   AAR 条目 = $ENTRIES_AFTER"
    if [ "$CJ_IN" != "$JARHASH" ]; then log "   ⚠️ AAR classes.jar 与交付 jar 不一致，**拒绝写回 AAR**"; else
      if [ "$SO_AFTER" != "$SO_BEFORE" ]; then log "   ⚠️ AAR 内 .so 被改动，**拒绝写回 AAR**"; else
        if [ "$ENTRIES_AFTER" != "$ENTRIES_BEFORE" ]; then log "   ⚠️ AAR 条目集合变化，**拒绝写回 AAR**"; else
          cp -f "$W/aar" "$AAR"; chown 1000:1000 "$AAR" 2>/dev/null || true
          log "   已同步 AAR：$AAR"
          log "   AAR sha256 = $NEW_AAR_HASH（大小 $(stat -c%s "$AAR") B）"
          log "   ✅ AAR 与 jar 一致性 + .so 字节不变 + 条目集合不变 三项自证通过"
        fi
      fi
    fi
  else
    log "   （未找到 AAR：$AAR，跳过同步）"
  fi
fi
log "DONE（staging: $STAGE；checker rc=$rc）"
exit $rc
