#!/usr/bin/env bash
# =============================================================================
# build_app.sh — t10 宿主机整包构建脚本（**草稿**：环境与产物校验已实现，构建步骤已就绪）
#
# 契约依据：doc/14-interface-contract.md
#   §4.2  自有 native 库名冻结为 webrtcdemo_native（libwebrtcdemo_native.so），
#         **禁止**链接 libwebrtc.a / libjingle_peerconnection_so.so（只有 JNI 边界）
#   §4.3  libjingle_peerconnection_so.so 由 t10 从 AAR 抽出 → app/src/main/jniLibs/arm64-v8a/
#   §3.5  JDK 17 / ANDROID_HOME / ANDROID_NDK_HOME=…/ndk/26.1.10909125
#   §3.3  Gradle 8.7（wrapper）、AGP 8.5.2、Kotlin 2.0.21
#   §8    信令服务为单二进制 signaling/（deploy 用 /opt/signaling/signaling）
#
# 运行位置：**宿主机**（容器内无 JDK/SDK，无法构建）
#   ssh … root@172.21.0.219 -p 5766 'bash /opt/dsh-workspaces/code/webrtc-demo/scripts/build_app.sh'
#
# 用法：
#   bash scripts/build_app.sh              # 完整流程（校验 → 抽 so → Kotlin 编译 → gradle → go → 验 APK）
#   bash scripts/build_app.sh --check-only # 只做前置校验，不构建（t7/t8/t9 未完成时用）
#   bash scripts/build_app.sh --skip-go    # 跳过 Go 二进制
#
# 阶段总览：
#   0 环境(<WS>/env.sh)  1 工具链版本  2 t5 产物(aarch64 核验)  3 t7/t8 源码
#   4 契约硬约束(§4.2)   5 抽 libjingle so→jniLibs(§4.3)        6 local.properties
#   6.5【早失败】:app:compileDebugKotlin -PwebrtcDemo.skipNative=true   ← 先暴露 Kotlin 错误
#   7 assembleDebug      8 Go 二进制   9 APK 解包核验   10 属主归一(判据=受版控树；**最后执行**)
#   ※ 任一前置阶段 FAIL 时不进入后续构建（保留早期错误，不掩盖）
#
# 环境约定（由 <WS>/env.sh 提供；见 reports/04-env-install.md 附录 A2）：
#   GRADLE_USER_HOME=<WS>/.gradle-home    TMPDIR=<WS>/tmp     （禁止写 $HOME/容器不可写路径）
# =============================================================================
set -euo pipefail

WS=/opt/dsh-workspaces
PROJ="$WS/code/webrtc-demo"
APP="$PROJ/app"
TP="$PROJ/third_party"
JNILIBS="$APP/src/main/jniLibs/arm64-v8a"
LOG_DIR="$PROJ/reports/logs"
TS="$(date +%Y%m%d-%H%M%S)"
LOG="$LOG_DIR/build_app-$TS.log"

CHECK_ONLY=0; SKIP_GO=0
for a in "$@"; do
  case "$a" in
    --check-only) CHECK_ONLY=1 ;;
    --skip-go)    SKIP_GO=1 ;;
    *) echo "未知参数: $a" >&2; exit 2 ;;
  esac
done

mkdir -p "$LOG_DIR" "$JNILIBS"
exec > >(tee -a "$LOG") 2>&1

ok(){ printf '  \033[32mOK\033[0m   %s\n' "$*"; }
warn(){ printf '  \033[33mWARN\033[0m %s\n' "$*"; }
fail(){ printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILED=1; }
FAILED=0
hdr(){ printf '\n===== %s =====\n' "$*"; }

hdr "0. 环境（必须由 env.sh 提供）"
# shellcheck disable=SC1090
. "$WS/env.sh"
echo "  JAVA_HOME        = $JAVA_HOME"
echo "  ANDROID_HOME     = $ANDROID_HOME"
echo "  ANDROID_NDK_HOME = $ANDROID_NDK_HOME"
echo "  GRADLE_USER_HOME = ${GRADLE_USER_HOME:-<unset>}"
echo "  TMPDIR           = ${TMPDIR:-<unset>}"
java -version 2>&1 | head -1
[ -z "${GRADLE_USER_HOME:-}" ] && fail "GRADLE_USER_HOME 未设置（应指向 <WS>/.gradle-home）"
[ -z "${TMPDIR:-}" ] && fail "TMPDIR 未设置（应指向 <WS>/tmp）"

hdr "1. 工具链版本（契约 §3.3/§3.5）"
jc=$(javac -version 2>&1 | awk '{print $2}')
echo "  javac  = $jc"; case "$jc" in 17*) ok "JDK 17";; *) fail "JDK 非 17";; esac
cm=$(cmake --version | head -1 | awk '{print $3}'); echo "  cmake  = $cm"
awk -v v="$cm" 'BEGIN{split(v,a,"."); exit !(a[1]>3 || (a[1]==3 && a[2]>=22))}' \
  && ok "cmake ≥3.22" || fail "cmake <3.22"
echo "  ninja  = $(ninja --version)"
echo "  gradlew 版本（wrapper 8.7）:"; grep -m1 distributionUrl "$PROJ/gradle/wrapper/gradle-wrapper.properties" || fail "缺 gradle wrapper"

hdr "2. t5 产物就位检查（缺失则 t8/t10 阻塞，契约 §4.3/§4.4）"
AAR="$TP/libwebrtc/java/libwebrtc-arm64.aar"
JAR="$TP/libwebrtc/java/libwebrtc-java.jar"
SO_TP="$TP/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so"
VPX_A="$TP/libvpx/lib/libvpx.a"
VPX_INC="$TP/libvpx/include/vpx"
# --- 先做一次"可自愈"的补齐：jar 缺失但 AAR 在 → 抽 classes.jar（契约 §4.3 定义 jar == AAR 内 classes.jar）
#     （放在判定之前，避免明明能自愈却先记 FAIL；t5 的 extract 亦会产出该 jar，但手工打 AAR 路径下可能只留 AAR）
if [ ! -f "$JAR" ] && [ -f "$AAR" ]; then
  tmpj=$(mktemp -d "$TMPDIR/aarjar.XXXXXX")
  if unzip -o -q "$AAR" classes.jar -d "$tmpj" && [ -f "$tmpj/classes.jar" ]; then
    cp -f "$tmpj/classes.jar" "$JAR"; ok "自 AAR 补齐 libwebrtc-java.jar（classes.jar）"
  else
    warn "AAR 内未找到 classes.jar，无法补齐 jar"
  fi
  rm -rf "$tmpj"
fi
for f in "$AAR" "$JAR" "$SO_TP" "$VPX_A"; do
  if [ -f "$f" ]; then ok "$(ls -l "$f" | awk '{print $5" bytes  "$9}')"; else fail "缺失: $f"; fi
done
[ -d "$VPX_INC" ] && ok "$VPX_INC（$(ls "$VPX_INC" | wc -l) 个头文件）" || fail "缺失: $VPX_INC"
# 架构核验：必须是 aarch64
for f in "$SO_TP" "$VPX_A"; do
  [ -f "$f" ] || continue
  case "$f" in
    *.a)
      # 静态库：file(1) 对 ar 归档只报 "current ar archive"，必须用 readelf 逐成员看 Machine
      RE="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
      if [ -x "$RE" ]; then
        machines=$( "$RE" -h "$f" 2>/dev/null | awk -F: '/Machine:/{gsub(/^ +/,"",$2); print $2}' | sort -u | tr '\n' ',' )
        case "$machines" in
          *AArch64*) ok "aarch64(成员 Machine): $f → ${machines%,}" ;;
          *)         fail "非 aarch64: $f → ${machines:-<无法识别>}" ;;
        esac
      else
        warn "缺少 llvm-readelf，跳过 .a 架构核验: $f"
      fi
      ;;
    *)
      file "$f" | grep -q aarch64 && ok "aarch64: $f" || fail "非 aarch64: $(file -b "$f")"
      ;;
  esac
done
# jar 内容核验：org/webrtc 类必须存在（D1：官方 Java SDK）
if [ -f "$JAR" ]; then
  n_webrtc=$(unzip -l "$JAR" 2>/dev/null | grep -c 'org/webrtc/' || true)
  [ "${n_webrtc:-0}" -gt 0 ] && ok "jar 内 org/webrtc 类条目 = $n_webrtc" \
    || fail "jar 内未发现 org/webrtc/ 类（Java SDK 不完整）"
fi

# --- [P-10] 对象真实性断言：判据必须落在 live 精确路径上（禁止通配/归档件）-------------
# 事故（2026-09-14，本队第 4 次"判定前未核实真实对象"形态问题）：预检对 jar 用
#   `libwebrtc-java.jar*` 通配取值，命中历史归档 `libwebrtc-java.jar.v61-java17`
#   （mtime 2026-09-13 20:00:41、*Jni=0）⇒ 误判"t23 未落位"，而 live jar 当时已是
#   mtime 11:05:10 / dc5f8919… / 508 类 / *Jni=48。教训：用受限模式做存在性/完备性判断前，
#   必须先证明"取到的对象就是判据对象"。本断言把"对象是谁"变成硬判据。
for spec in "JAR:$JAR" "AAR:$AAR" "SO_TP:$SO_TP" "VPX_A:$VPX_A"; do
  nm=${spec%%:*}; p=${spec#*:}
  case "$p" in
    *'*'*|*'?'*|*'['*|*.orig|*.orig-build|*.orig-jdk25|*.v55-java11|*.v61-java17|*.pre-t23|*.prev-v61)
      fail "$nm 指向通配或归档副本（禁止用作判据对象）: $p" ;;
    *)
      [ -f "$p" ] && ok "$nm 使用精确 live 路径（无通配/无归档后缀）: $p" || true ;;
  esac
done
# 基线指纹（判据对象 = 上述精确路径；供"修复前/后"对比与报告留痕）
if [ -f "$JAR" ]; then
  printf '  [基线指纹] jar sha256=%s  size=%s  mtime=%s\n' \
    "$(sha256sum "$JAR" | awk '{print $1}')" "$(stat -c %s "$JAR")" \
    "$(stat -c %y "$JAR" | cut -c1-19)"
fi
if [ -f "$AAR" ]; then
  printf '  [基线指纹] aar sha256=%s  size=%s  mtime=%s\n' \
    "$(sha256sum "$AAR" | awk '{print $1}')" "$(stat -c %s "$AAR")" \
    "$(stat -c %y "$AAR" | cut -c1-19)"
fi
# --- jni_zero 绑定形态判据（路线 A；captain 2026-09-14 口径）-----------------------------
# .so 导出的是 Java_J_N_<hash>（jni_zero short/proxy 静态符号绑定，非 kMethods 注册表形态），
# 因此 Java 侧必须含 J.N（哈希 native 持有类）+ 转发层 GEN_JNI；缺任一件 ⇒ 真机首次
# native 调用 UnsatisfiedLinkError / NoClassDefFoundError，APK 即白编。
if [ -f "$JAR" ]; then
  jl=$(unzip -Z1 "$JAR" 2>/dev/null || true)   # 先读入变量，避免 pipefail 下 grep -q 触发 SIGPIPE 假失败
  n_jn_cls=$(grep -cx 'J/N.class' <<<"$jl" || true)
  [ "${n_jn_cls:-0}" -gt 0 ] && ok "jar 含 J/N.class（jni_zero short/proxy native 持有类）" \
    || fail "jar 缺 J/N.class ⇒ .so 的 Java_J_N_* 无法绑定（真机首次 native 调用会 UnsatisfiedLinkError）"
  n_gen_cls=$(grep -cx 'org/jni_zero/GEN_JNI.class' <<<"$jl" || true)
  [ "${n_gen_cls:-0}" -gt 0 ] && ok "jar 含 org/jni_zero/GEN_JNI.class" \
    || fail "jar 缺 org/jni_zero/GEN_JNI.class"
  n_jni_cls=$(grep -c 'Jni\.class$' <<<"$jl" || true)
  ok "jar 内 *Jni.class = ${n_jni_cls:-0}"
  if command -v javap >/dev/null 2>&1; then
    n_gen_native=$(javap -p -classpath "$JAR" org.jni_zero.GEN_JNI 2>/dev/null | grep -c ' native ' || true)
    [ "${n_gen_native:-0}" = "0" ] && ok "GEN_JNI 为纯转发层（static native = 0）" \
      || fail "GEN_JNI 含 ${n_gen_native:-?} 个 native 声明 ⇒ 编译期 stub 形态，与 .so 的 Java_J_N_* 不匹配"
    n_jn_native=$(javap -p -classpath "$JAR" 'J.N' 2>/dev/null | grep -c ' native ' || true)
    ok "J.N 哈希 native 声明数 = ${n_jn_native:-0}"
  else
    warn "无 javap，跳过 GEN_JNI/J.N 形态核验"
  fi
fi

hdr "3. t7/t8 源码就位检查"
[ -f "$APP/src/main/cpp/CMakeLists.txt" ] && ok "app/src/main/cpp/CMakeLists.txt" || fail "缺 CMakeLists.txt"
[ -d "$APP/src/main/kotlin" ] && ok "app/src/main/kotlin/（$(find "$APP/src/main/kotlin" -name '*.kt' | wc -l) 个 .kt）" || warn "缺 kotlin 源码"
[ -f "$APP/build.gradle.kts" ] && ok "app/build.gradle.kts" || fail "缺 app/build.gradle.kts"
[ -f "$PROJ/settings.gradle.kts" ] && ok "settings.gradle.kts" || fail "缺 settings.gradle.kts"

hdr "4. 契约硬约束自检（§4.2：native 库名 / 禁止链接 libwebrtc.a）"
CML="$APP/src/main/cpp/CMakeLists.txt"
if [ -f "$CML" ]; then
  add_library_line=$(grep -nE 'add_library\(' "$CML" | head -3)
  echo "  add_library: $add_library_line"
  grep -qE 'add_library\(\s*webrtcdemo_native\s+SHARED' "$CML" \
    && ok "库名 = webrtcdemo_native (SHARED)" || fail "库名不是 webrtcdemo_native / 非 SHARED"
  if grep -nE 'libwebrtc\.a|libjingle_peerconnection_so' "$CML"; then
    fail "CMakeLists 出现 libwebrtc.a / libjingle_peerconnection_so 链接（违反 §4.2）"
  else
    ok "未链接 libwebrtc.a / libjingle_peerconnection_so（符合 §4.2）"
  fi
  grep -qE 'System\.loadLibrary\("webrtcdemo_native"\)' -r "$APP/src/main/kotlin" 2>/dev/null \
    && ok 'Kotlin NativeLoader: System.loadLibrary("webrtcdemo_native")' \
    || warn '未找到 System.loadLibrary("webrtcdemo_native")'
fi

hdr "5. 抽出 libjingle_peerconnection_so.so → app/src/main/jniLibs/arm64-v8a/（契约 §4.3）"
if [ -f "$SO_TP" ]; then
  cp -f "$SO_TP" "$JNILIBS/"
  ok "自 third_party 复制"
elif [ -f "$AAR" ]; then
  tmpd=$(mktemp -d "$TMPDIR/aar.XXXXXX")
  unzip -o -q "$AAR" -d "$tmpd"
  src=$(find "$tmpd" -name 'libjingle_peerconnection_so.so' | head -1)
  [ -n "$src" ] && { cp -f "$src" "$JNILIBS/"; ok "自 AAR 抽出: $src"; } || fail "AAR 内无 libjingle_peerconnection_so.so"
  rm -rf "$tmpd"
else
  fail "既无 $SO_TP 也无 $AAR，无法准备 jniLibs"
fi
if [ -f "$JNILIBS/libjingle_peerconnection_so.so" ]; then
  file "$JNILIBS/libjingle_peerconnection_so.so"
  file "$JNILIBS/libjingle_peerconnection_so.so" | grep -q aarch64 || fail "jniLibs 中 so 非 aarch64"
  # ⚠️ 坑 2（captain 17:35 提示 / webrtc-builder §7.1）：必须用 **stripped** 版（12,946,912 B / 757cef8128bf9151…），
  #    t5 曾误取 lib.unstripped/ 的未 strip 版后重打 AAR。这里显式核验哈希前缀与 stripped。
  SO_SHA_PREFIX="${SO_SHA_PREFIX:-757cef8128bf9151}"
  jsha=$(sha256sum "$JNILIBS/libjingle_peerconnection_so.so" | awk '{print $1}')
  echo "    jniLibs so sha256 = $jsha（期望前缀 $SO_SHA_PREFIX）"
  case "$jsha" in
    "$SO_SHA_PREFIX"*) ok "jniLibs so 与 t5 stripped 版哈希一致" ;;
    *) fail "jniLibs so 哈希前缀不匹配（可能是未 strip 的 lib.unstripped 版本），禁止带入 APK" ;;
  esac
  file "$JNILIBS/libjingle_peerconnection_so.so" | grep -q stripped \
    && ok "stripped ✅" || fail "未 stripped（应为 stripped 版）"
  echo "    大小: $(stat -c %s "$JNILIBS/libjingle_peerconnection_so.so") B（期望 12946912）"
fi

hdr "6. local.properties"
# 注意：AGP 8.x 已弃用 ndk.dir（会打 CXX5106 警告），NDK 版本由 app/build.gradle.kts 的
# android.ndkVersion = "26.1.10909125" 指定 —— 与契约 §3.5 一致，故此处只写 sdk.dir。
cat > "$PROJ/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF
ok "已写 $PROJ/local.properties（NDK 由 app/build.gradle.kts ndkVersion 指定）"; cat "$PROJ/local.properties"

if [ "$CHECK_ONLY" = "1" ]; then
  hdr "== --check-only：不执行构建 =="
  [ "$FAILED" = "0" ] && { echo "前置校验全部通过（未构建）"; exit 0; } || { echo "前置校验存在失败项，请先修复"; exit 1; }
fi

# ---------------------------------------------------------------------------
# 6.5 【早失败】Kotlin 编译级检查（android-dev 建议 U4；captain 2026-09-13 指令）
#   目的：让 Kotlin 级错误在耗时更长的 native 编译/打包之前暴露，并为 t14 骨架
#         与 t8 业务代码提供"至少通过 Kotlin 编译"的编译级证据（容器内无法编译）。
#   前置：t5 的 third_party/libwebrtc/java/libwebrtc-java.jar（org.webrtc.*）；
#         jar 未就绪时阶段 2 已 FAIL，此处不会静默跳过。
# ---------------------------------------------------------------------------
hdr "6.5 【早失败】Kotlin 编译检查：:app:compileDebugKotlin"
if [ "$FAILED" != "0" ]; then
  fail "前置阶段已失败，跳过 Kotlin 编译检查（不掩盖错误）"
else
  # 属性名以 t8 实际 app/build.gradle.kts 为准（captain 给的 -PwebrtcDemo.skipNative 与实际大小写不同）
  SKIP_PROP=""
  if grep -q 'webrtcDemo\.skipNative' "$APP/build.gradle.kts" 2>/dev/null; then
    SKIP_PROP="-PwebrtcDemo.skipNative=true"
    ok "使用属性 $SKIP_PROP（来自 app/build.gradle.kts 实际定义）"
  elif grep -q 'webrtcDemo\.skipNative' "$PROJ/build.gradle.kts" "$PROJ/gradle.properties" 2>/dev/null; then
    SKIP_PROP="-PwebrtcDemo.skipNative=true"
    ok "使用属性 $SKIP_PROP"
  else
    # 回退：captain 指令里给的写法（大小写不同），失败时按"属性不识别"如实记录
    SKIP_PROP="-PwebrtcDemo.skipNative=true"
    warn "app/build.gradle.kts 未找到 webrtcDemo.skipNative；回退使用 captain 指令写法 $SKIP_PROP"
  fi
  echo "  命令: ./gradlew :app:compileDebugKotlin --no-daemon $SKIP_PROP"
  klog="$LOG_DIR/kotlin-compile-$TS.log"
  set +e
  ( cd "$PROJ" && ./gradlew --no-daemon :app:compileDebugKotlin $SKIP_PROP ) 2>&1 | tee "$klog" | tail -25
  krc=${PIPESTATUS[0]}
  set -e
  if [ "$krc" = "0" ]; then
    ok "Kotlin 编译通过（:app:compileDebugKotlin）→ 完整日志: $klog"
    # 编译产物存在性佐证
    find "$APP/build" -name '*.class' -path '*debug*' 2>/dev/null | head -3 | sed 's/^/    class: /' || true
  else
    fail "Kotlin 编译失败（exit=$krc）→ 不继续 native 构建；完整原始错误见 $klog"
    grep -nE '^e: |error:|FAILURE|What went wrong|Caused by' "$klog" | head -20 || true
  fi
fi

hdr "7. Android 构建：./gradlew assembleDebug（契约 §3.3/§4.1）"
if [ "$FAILED" != "0" ]; then
  fail "存在失败项（含 Kotlin 编译），跳过 assembleDebug 以保留早期错误"
else
  cd "$PROJ"
  ./gradlew --no-daemon assembleDebug 2>&1 | tail -40 || { fail "gradlew assembleDebug 失败"; }
fi

hdr "8. Go 信令二进制（linux/amd64）"
if [ "$SKIP_GO" = "0" ]; then
  if [ -f "$PROJ/signaling/go.mod" ]; then
    SIG="$PROJ/signaling"
    DEST=""
    had=0
    [ -f "$SIG/signaling" ] && had=1            # go-dev 已交付 t9 二进制，可复用
    [ -f "$SIG/dist/signaling-linux-amd64" ] && had=1
    mkdir -p "$SIG/dist"
    # go-dev 指定的构建方式（与 t9 产出一致，便于比对 sha256）
    ( cd "$SIG" && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
        go build -trimpath -ldflags="-s -w" -o dist/signaling-linux-amd64 . ) \
      && { DEST="$SIG/dist/signaling-linux-amd64"; ok "已构建 $DEST（$(stat -c %s "$DEST") bytes）"; } \
      || fail "go build 失败（signaling/）"
    if [ -n "$DEST" ]; then
      sha256sum "$DEST" | sed 's/^/    sha256: /'
      file "$DEST" | sed 's/^/    /'
      # ---- 与 t9 交付物比对（VCS 戳 + 假 OK 双重防护）--------------------------
      # 背景：Go 会把 vcs=git / vcs.revision / vcs.modified 编进二进制 ⇒ t10 提交后
      # HEAD 前进、vcs.modified 翻转，源码未改也会得到不同 sha256（假失败）。
      # 反向陷阱（go-dev §4.5 对抗测试 + 我已独立复现）：`go version -m` 元数据**不含被编译的代码**，
      #   实测把 server.go 的 Version "0.1.0"→"0.1.1" 后，哈希由 1b333208…43aa 变为 74a37d0e…5ff2，
      #   而元数据**逐行完全相同（差异 0 行）**。因此元数据绝不能单独用于"源码未变"的结论（会假 OK）。
      # 判据（2026-09-13 16:55 修订）：
      #   ① cmp 相同 → OK（同 VCS 态，可复现）
      #   ② cmp 不同 → 用 -buildvcs=false 在 signaling/ 重建两次作**决定性判据**：
      #        · 两次逐字节相同（确定性成立）且 sha256 == NOVCS_BASELINE ⇒ 源码/工具链未变，
      #          哈希变化可确证仅由 VCS 戳引起 → OK
      #        · 不相等 ⇒ 源码/工具链/环境确实变了 → FAIL（不是 WARN：这正是要拦住的假 OK）
      #        · 两次不相同 ⇒ 构建不可确定性 → FAIL（环境不稳定）
      #   ③ 元数据比对仅作**辅助说明**（证明 module/构建参数未变），不单独下结论。
      NOVCS_BASELINE="${NOVCS_BASELINE:-1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa}"
      if [ -f "$SIG/signaling" ]; then
        if cmp -s "$DEST" "$SIG/signaling"; then
          ok "新构建与 t9 交付的 signaling/signaling 逐字节一致（t9 产物可复现）"
          echo "    （vcs.revision=$(go version -m "$DEST" 2>/dev/null | awk -F= '/vcs.revision/{print $2}')）"
        else
          T2="$TMPDIR/gv-novcs-$$"; T3="$TMPDIR/gv-novcs2-$$"
          ( cd "$SIG" && go build -trimpath -ldflags="-s -w" -buildvcs=false -o "$T2" . ) 2>/dev/null || true
          ( cd "$SIG" && go build -trimpath -ldflags="-s -w" -buildvcs=false -o "$T3" . ) 2>/dev/null || true
          h2=$(sha256sum "$T2" 2>/dev/null | awk '{print $1}')
          h3=$(sha256sum "$T3" 2>/dev/null | awk '{print $1}')
          echo "    交付物 sha256      : $(sha256sum "$SIG/signaling" | awk '{print $1}')"
          echo "    本次默认构建 sha256: $(sha256sum "$DEST" | awk '{print $1}')"
          echo "    VCS-free 重建(两次): ${h2:-<构建失败>} / ${h3:-<构建失败>}"
          echo "    VCS-free 基线(期望): $NOVCS_BASELINE"
          echo "    t9 交付物 vcs.revision=$(go version -m "$SIG/signaling" 2>/dev/null | awk -F= '/vcs.revision/{print $2}')"
          echo "    新构建    vcs.revision=$(go version -m "$DEST" 2>/dev/null | awk -F= '/vcs.revision/{print $2}')"
          # 辅助信号（不作判据）
          norm(){ go version -m "$1" 2>/dev/null | sed '1d' | grep -v -e 'vcs=' -e 'vcs\.'; }
          metadiff=$(diff <(norm "$DEST") <(norm "$SIG/signaling") | grep -c '^[<>]' || true)
          echo "    [辅助] 非 vcs 元数据差异行数 = $metadiff（0 不代表源码未变，只说明 module/参数一致）"
          if [ -n "$h2" ] && [ "$h2" = "$h3" ] && [ "$h2" = "$NOVCS_BASELINE" ]; then
            ok "哈希差异已确证仅由 Go VCS 戳引起（VCS-free 基线与 t9 交付基线一致：${h2:0:16}…）—— 避免假失败"
          elif [ -n "$h2" ] && [ "$h2" != "$h3" ]; then
            fail "VCS-free 重建两次结果不一致 ⇒ 构建不可确定性，环境不稳定，需人工排查"
          else
            fail "VCS-free 重建与 t9 交付基线不符 ⇒ 源码/工具链/环境确实发生变化（期望 ${NOVCS_BASELINE:0:16}… 实得 ${h2:0:16}…），需人工核对，不得放行"
          fi
          rm -f "$T2" "$T3"
        fi
      fi
      if [ "$had" = "1" ]; then
        ok "（检测到 t9 已交付的既有二进制，本次为可复现重编）"
      fi
    fi
  else
    warn "无 signaling/go.mod，跳过"
  fi
else
  warn "--skip-go"
fi

hdr "9. APK 产物核验（契约 §4.3/§6）"
APK=$(find "$APP/build/outputs/apk" -name '*.apk' 2>/dev/null | head -1 || true)
if [ -n "$APK" ]; then
  ok "APK: $APK（$(ls -l "$APK" | awk '{print $5}') bytes）"
  echo "  --- APK 内 arm64-v8a .so ---"
  # ⚠️ 修正（t10 实测）：`unzip -l … | grep -q …` 在 `set -o pipefail` 下会因 grep -q 提前退出、
  #    unzip 收到 SIGPIPE(141) 而使管道整体返回非 0 → **随机假 FAIL**（实测 libjingle 通过、
  #    libwebrtcdemo_native 报"缺"，而 unzip -l 明明列出了它）。改为先读入变量再用 here-string 匹配。
  apk_list=$(unzip -l "$APK" 2>/dev/null)
  grep -q 'lib/arm64-v8a' <<<"$apk_list" && grep 'lib/arm64-v8a' <<<"$apk_list" || fail "APK 内无 lib/arm64-v8a/*.so"
  for so in libjingle_peerconnection_so.so libwebrtcdemo_native.so; do
    grep -qF "lib/arm64-v8a/$so" <<<"$apk_list" && ok "APK 含 $so" || fail "APK 缺 $so"
  done
  # --- [P-12] native 交付件对象漂移护栏（native-dev 2026-09-14 请求）------------------------
  # t30 的"193 个 Java_J_N_* 与 Java 侧可绑定"证明是针对 .so = 757cef81… 做的；路线 A 的修复
  #   是**纯 Java 侧**（补 J.N + 换转发 GEN_JNI，不重编 .so）⇒ 新 APK 内该 .so 必须**逐字节不变**，
  #   否则证明对象已漂移、t30 的结论必须重跑（防"假通过"）。libc++_shared 亦应恒不变（16 KB 落位件）。
  SO_EXPECT_JINGLE="757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e"
  SO_EXPECT_CXX="c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36"
  for pair in "libjingle_peerconnection_so.so:$SO_EXPECT_JINGLE" "libc++_shared.so:$SO_EXPECT_CXX"; do
    so_name=${pair%%:*}; so_exp=${pair#*:}
    so_got=$(unzip -p "$APK" "lib/arm64-v8a/$so_name" 2>/dev/null | sha256sum | awk '{print $1}' || true)
    if [ "$so_got" = "$so_exp" ]; then
      ok "APK 内 $so_name sha256 与预期逐字节一致（${so_exp:0:16}…）"
    else
      fail "APK 内 $so_name 对象漂移：期望 ${so_exp:0:16}… 实得 ${so_got:0:16}…（若为 libjingle ⇒ t30 可绑定证明对象已漂移，须重跑证明）"
    fi
  done
  echo "  --- org.webrtc 类是否入 dex ---"
  # ⚠️ 修正（t10 实测）：不能把 APK 直接交给 dexdump（它要 .dex，不是 zip）。必须
  #    先抽出全部 classes*.dex；且 org.webrtc 类未必在 classes.dex（实测落在 classes13.dex）。
  if [ -x "$ANDROID_HOME/build-tools/34.0.0/dexdump" ]; then
    dxd=$(mktemp -d "$TMPDIR/dexchk.XXXXXX")
    ( cd "$dxd" && unzip -q -o "$APK" 'classes*.dex' ) 2>/dev/null || true
    tot_dex=$(ls "$dxd"/classes*.dex 2>/dev/null | wc -l || true)
    # ⚠️ 修正（t10 实测）：`x=$(... | grep ... | wc -l)` 在 `set -o pipefail` 下，若 grep 无匹配
    #    则整个管道返回 1 → 命令替换失败 → `set -e` **直接终止脚本**（实测脚本在阶段 9 中途静默退出，
    #    既不打印阶段 10 也不打印总结）。故所有计数管道一律加 `|| true`。
    n_ow=$( { for f in "$dxd"/classes*.dex; do
                "$ANDROID_HOME/build-tools/34.0.0/dexdump" "$f" 2>/dev/null || true
              done; } | grep -oE 'Lorg/webrtc/[A-Za-z0-9_$]+;' | sort -u | wc -l || true )
    n_app=$( { for f in "$dxd"/classes*.dex; do
                 "$ANDROID_HOME/build-tools/34.0.0/dexdump" "$f" 2>/dev/null || true
               done; } | grep -oE 'Lcom/example/webrtcdemo/[A-Za-z0-9_$/]+;' | sort -u | wc -l || true )
    echo "    dex 文件数=$tot_dex  org.webrtc 唯一类=$n_ow  本项目类=$n_app"
    [ "${n_ow:-0}" -gt 0 ] && ok "dex 含 org.webrtc 类（$n_ow 个）" || fail "dex 未含 org.webrtc 类"
    [ "${n_app:-0}" -gt 0 ] && ok "dex 含本项目类（$n_app 个）" || warn "dex 未含本项目类"
    # 每个 dex 的分布（证明不是只看 classes.dex）
    for f in "$dxd"/classes*.dex; do
      c=$( "$ANDROID_HOME/build-tools/34.0.0/dexdump" "$f" 2>/dev/null | grep -oE 'Lorg/webrtc/' | wc -l || true )
      if [ "${c:-0}" -gt 0 ]; then echo "      $(basename "$f"): org.webrtc 条目 $c"; fi
    done
    # --- [P-11] 路线 A 绑定形态判据（dex 级；captain 2026-09-14 口径）-------------------
    # 独立于 jar 判据的第二道闸：即使 jar 形态正确，也要证明"打包进 APK 的 dex"真的带上了
    # J.N 与转发层 GEN_JNI（R8/D8 收缩或依赖缺失都可能把它们丢掉）。用字节级扫描，规避
    # dexdump 的输出格式差异；计数落到 dex 文件本身，而非只看 classes.dex。
    if command -v python3 >/dev/null 2>&1; then
      dexpat=$(python3 - "$dxd" <<'PYEOF' 2>/dev/null || true
import glob, os, sys
d = sys.argv[1]
need = [("LJ/N;", "jn"), ("Lorg/jni_zero/GEN_JNI;", "genjni"),
        ("Lorg/webrtc/PeerConnectionFactoryJni;", "pcf_jni")]
tot = {k: 0 for _, k in need}
n = 0
for p in sorted(glob.glob(os.path.join(d, "classes*.dex"))):
    n += 1
    b = open(p, "rb").read()
    for s, k in need:
        tot[k] += b.count(s.encode())
print("dexfmt: dex=%d jn=%d genjni=%d pcf_jni=%d"
      % (n, tot["jn"], tot["genjni"], tot["pcf_jni"]))
PYEOF
)
      echo "    $dexpat"
      case "$dexpat" in
        *"jn=0"*)     fail "dex 缺 J/N（路线 A 绑定形态不成立：.so 的 Java_J_N_* 无 Java 侧持有类）" ;;
      esac
      case "$dexpat" in
        *"genjni=0"*) fail "dex 缺 org.jni_zero.GEN_JNI（转发层未打包）" ;;
      esac
      case "$dexpat" in
        *"pcf_jni=0"*) fail "dex 缺 org.webrtc.PeerConnectionFactoryJni（t23 绑定类未打包）" ;;
      esac
    else
      warn "无 python3，跳过 dex 级 J/N 与 GEN_JNI 判据"
    fi
    rm -rf "$dxd"
  else
    warn "dex 检查：可用 dexdump 手工复核"
  fi
  echo "  --- FileProvider / file_paths.xml 打包证据（doc/10 §8 导出日志）---"
  grep -E 'res/xml/file_paths|AndroidManifest' <<<"$apk_list" || warn "未见 file_paths.xml（请用 aapt dump xmltree 复核）"
  # 同样避免 SIGPIPE：先把 aapt2 输出读进变量（`grep -m3` 提前退出会让 aapt2 收到 SIGPIPE → pipefail 误报 WARN）
  mfst=$( "$ANDROID_HOME/build-tools/34.0.0/aapt2" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null || true )
  if grep -q 'androidx\.core\.content\.FileProvider' <<<"$mfst"; then
    ok "AndroidManifest 含 FileProvider 节点"
    grep -i 'authorities' <<<"$mfst" | head -4
  else
    warn "aapt2 未在 AndroidManifest 找到 FileProvider 节点（需人工确认）"
  fi
else
  fail "未找到 APK 产物"
fi

hdr "10. 属主归一（**必须在所有产物落盘之后、git 提交之前**执行）"
# 时序（captain 2026-09-13 16:20 口径）：
#   t5 在宿主机以 root 编译，会持续往 gitignored 的 third_party/libwebrtc/**、third_party/libvpx/**
#   写 root 属主的新文件。因此本阶段固定为脚本**最后一环**（stage 9 之后），
#   且判据只聚焦【受版本控制的树】，gitignored 的产物属主**只作记录、不作验收判据**
#   （否则每次 t5 重编都会让"非 1000 项 = 0"变成假失败）。
# 归零范围：受版控树（git ls-files --cached --others --exclude-standard）+ 本脚本/AGP 自有产出
# （jniLibs/、reports/logs/、local.properties、app/build/、app/.cxx/、项目级 .gradle/、.kotlin/）。
# 不主动改 third_party 产物属主（含 gitignored 产物，仅记录）——webrtc-builder 已确认其产物 0 项非 1000。
if [ "$(id -u)" = "0" ]; then
  # 本脚本/AGP 自有的 gitignored 产出，一律归 1000:1000
  # （实测遗漏过 app/.cxx(99 项)、项目级 .gradle/、.kotlin/ —— 见 reports/10-app-build.md §7.7）
  chown -R 1000:1000 "$JNILIBS" "$LOG_DIR" 2>/dev/null || true
  [ -f "$PROJ/local.properties" ] && chown 1000:1000 "$PROJ/local.properties" 2>/dev/null || true
  for d in "$APP/build" "$APP/.cxx" "$PROJ/.gradle" "$PROJ/.kotlin"; do
    [ -e "$d" ] && chown -R 1000:1000 "$d" 2>/dev/null || true
  done
  # 受版控树判据（含将被提交的未跟踪文件，排除 gitignore）
  n_tracked=0; tracked_list=""
  while IFS= read -r f; do
    [ -e "$PROJ/$f" ] || continue
    if [ "$(stat -c %u "$PROJ/$f" 2>/dev/null)" != "1000" ]; then
      n_tracked=$((n_tracked+1)); tracked_list="$tracked_list$f\n"
    fi
  done < <(cd "$PROJ" && git ls-files --cached --others --exclude-standard)
  if [ "$n_tracked" = "0" ]; then
    ok "受版控树属主全部为 1000:1000（判据通过）"
  else
    warn "受版控树仍有 $n_tracked 个非 1000 属主项："; printf "    %b" "$tracked_list"
    warn "尝试自动归零…"; ( cd "$PROJ" && git ls-files --cached --others --exclude-standard -z \
      | xargs -0 -r chown 1000:1000 2>/dev/null ) || true
    n_tracked=$(cd "$PROJ" && for f in $(git ls-files --cached --others --exclude-standard); do
      [ -e "$f" ] && [ "$(stat -c %u "$f")" != "1000" ] && echo x; done | wc -l)
    [ "$n_tracked" = "0" ] && ok "已自动归零，受版控树属主 = 1000:1000" || warn "仍有 $n_tracked 项未归零（请人工处理）"
  fi
  # gitignored 产物：只记录（不作判据）
  n_art=0
  for d in "$TP/libwebrtc" "$TP/libvpx" "$TP/libwebrtc-src" "$TP/libvpx-src"; do
    [ -d "$d" ] && n_art=$((n_art + $(find "$d" ! -uid 1000 2>/dev/null | wc -l)))
  done
  echo "  [记录项·非判据] third_party 下（含 gitignored 产物与 submodule 工作树）非 1000 属主文件数 = $n_art"
  echo "                   （t5 以 root 编译期间会持续产生，属正常；如需容器侧写入再单独 chown）"
else
  warn "非 root 执行，跳过 chown（若以 root 运行请确保受版控树属主为 1000:1000）"
fi

hdr "总结"
echo "  日志: $LOG"
if [ "$FAILED" = "0" ]; then echo "  ✅ 全部通过"; else echo "  ❌ 存在失败项，见上方 FAIL"; fi
exit "$FAILED"
