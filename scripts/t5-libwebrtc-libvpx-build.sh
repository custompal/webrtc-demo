#!/bin/bash
# =============================================================================
# t5 — libwebrtc(android arm64) + libvpx(android arm64) 交叉编译
#   作者: webrtc-builder (AgentTeams webrtc-demo)  attempt 1
#   宿主: Ubuntu 24.04 / 4 vCPU / 7.1GiB RAM / 无 swap / 磁盘 69G(可用 ~41G)
#
#   契约: doc/14-interface-contract.md §4.3 / §4.4 / §9.7
#
#   产物（→ /opt/dsh-workspaces/code/webrtc-demo/third_party/）:
#     libwebrtc/java/libwebrtc-arm64.aar                       官方 Java SDK 归档
#     libwebrtc/java/libwebrtc-java.jar                        = AAR 内 classes.jar (org.webrtc.*)
#     libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so
#     libwebrtc/lib/*.a                                        AArch64 静态库（对照/备料）
#     libwebrtc/include/**                                     公开头文件（含 sdk/android）
#     libvpx/lib/libvpx.a + libvpx/include/vpx/*.h             VP9 编码器链接用
#
#   用法: nohup bash <this> <phase> > logs/<phase>.log 2>&1 &
#         phase ∈ gn|aar|libvpx|extract|all
# =============================================================================
set -o pipefail

WS=/opt/dsh-workspaces
BUILD=$WS/webrtc-build
TP=$WS/code/webrtc-demo/third_party
LOGDIR=$BUILD/logs
DEPOT=$BUILD/depot_tools
SRC=$BUILD/src
OUT=out/Release-arm64
LIBVPX_SRC=$BUILD/libvpx-src
LIBVPX_OUT=$BUILD/libvpx-output
PHASE=${1:-all}

mkdir -p "$BUILD" "$LOGDIR" "$TP"
export PATH="$DEPOT:$PATH"
export DEPOT_TOOLS_UPDATE=0
export VPYTHON_BYPASS="manually managed python not supported by chrome operations"
log() { echo "[$(date -u +%FT%TZ)] $*"; }

# 内存/磁盘水位 watchdog：磁盘到 88% 立即杀 ninja，绝不打爆分区
watchdog() {
  while true; do
    local mem disk
    mem=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
    disk=$(df --output=pcent / | tail -1 | tr -dc '0-9')
    echo "$(date -u +%FT%TZ) memAvailMB=$mem diskPct=$disk load=$(cut -d' ' -f1-3 /proc/loadavg)" >> "$LOGDIR/watermark.log"
    if [ "$disk" -ge 88 ]; then log "!!! 磁盘 ${disk}% >= 88%，中止 ninja"; pkill -f "ninja -C"; break; fi
    sleep 60
  done
}
runwatchdog() { watchdog & WATCHDOG_PID=$!; }
stopwatchdog() { [ -n "${WATCHDOG_PID:-}" ] && kill "$WATCHDOG_PID" 2>/dev/null || true; }

# ---------------------------------------------------------------------------
# t17：幂等应用 libwebrtc Java 目标版本补丁（--release 25 -> 17）
#
#   **为什么必须打这个补丁**：本 checkout 把 Java 目标版本硬编码为 25
#   （build/android/gyp/compile_java.py:711 与 turbine.py:110），而 javac 来自
#   third_party/jdk/current（25.0.4.1）。若不改，GN 会产出 major 69 的 class，
#   AGP 8.5.2 的 D8 8.2.2 不支持 -> t10 的 :app:desugarDebugFileDependencies 失败。
#
#   守卫（防上游改动导致修补静默失效）：
#     已是目标值（17）-> 跳过
#     仍是 25        -> 应用补丁
#     既非 25 也非 17 -> **报错退出**（需人工确认，不要盲目 patch）
#
#   ⚠️ 目标值由 captain **终局裁定为 17**（Java 17 / major 61），已冻结：
#       依据：契约 §3 冻结 JDK 17；app 自身 `sourceCompatibility/targetCompatibility/jvmTarget = 17`；
#             AGP 8.5.2 的 D8 8.2.2 支持 major ≤ 61。
#       变更目标值只需改 JAVA_RELEASE_TARGET 与补丁文件名（两者需一致）。
#       （历史：曾短暂改为 11/v55，captain 已裁定作废，见 reports/15 头部与 05 §8.1 哈希链表。）
# ---------------------------------------------------------------------------
JAVA_RELEASE_TARGET=${JAVA_RELEASE_TARGET:-17}
PATCH_DIR_REPO=${PATCH_DIR_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/patches}
JAVA_PATCH=$BUILD/libwebrtc-java-release${JAVA_RELEASE_TARGET}.patch
[ -f "$JAVA_PATCH" ] || JAVA_PATCH=$PATCH_DIR_REPO/libwebrtc-java-release${JAVA_RELEASE_TARGET}.patch

java_release_value() {   # $1 = 文件路径；打印紧跟 --release 之后的实测版本号
  # 两段式，覆盖两种上游写法（示例以目标值 17 表示）：
  #   A) 同行：javac_cmd = ['--release', '17']        -> turbine.py
  #   B) 次行：'--release',\n ... '17',                -> compile_java.py
  # 必须跳过注释行，否则会误取 LINT.*Change 注释里的数字。
  local f=$1 n v
  n=$(grep -n -- "--release" "$f" 2>/dev/null | head -1 | cut -d: -f1)
  [ -z "$n" ] && return 0
  v=$(sed -n "${n}p" "$f" | sed "s/.*--release//" | grep -oE "'[0-9]+'" | head -1 | tr -dc '0-9')
  if [ -z "$v" ]; then
    v=$(sed -n "$((n+1)),$((n+6))p" "$f" | grep -vE "^[[:space:]]*#" \
        | grep -oE "^[[:space:]]*'[0-9]+',?[[:space:]]*$" | head -1 | tr -dc '0-9')
  fi
  echo "$v"
}

ensure_java_release_patch() {
  local g="$SRC/build/android/gyp"
  local cj="$g/compile_java.py" tb="$g/turbine.py"
  local T="$JAVA_RELEASE_TARGET"
  log "== t17: 校验 Java 目标版本补丁（目标值=$T）=="
  if [ ! -f "$cj" ] || [ ! -f "$tb" ]; then
    log "FATAL t17: 未找到 $cj 或 $tb（源码树缺失？）"; return 1
  fi
  local a b
  a=$(java_release_value "$cj"); b=$(java_release_value "$tb")
  log "t17: 当前实测 --release -> compile_java.py='$a'  turbine.py='$b'"

  # ① 已是目标态
  if [ "$a" = "$T" ] && [ "$b" = "$T" ]; then
    log "t17: 已是 $T -> **跳过**应用补丁（幂等）"
    return 0
  fi

  # ② 任一文件处于「既非 25 也非目标值」= 上游改动/异常 -> 拒绝盲目修补
  local bad=""
  [ "$a" != "25" ] && [ "$a" != "$T" ] && bad="compile_java.py='$a'"
  [ "$b" != "25" ] && [ "$b" != "$T" ] && bad="$bad turbine.py='$b'"
  if [ -n "$bad" ]; then
    log "FATAL t17: 检测到非预期值：$bad —— **既非 25 也非 $T**，拒绝盲目修补。"
    log "FATAL t17: 上游可能已改动该处；请人工复核 scripts/patches/libwebrtc-java-release${T}.patch。"
    return 1
  fi

  # ③ 需要修补：优先整包应用补丁；若只有一个文件是 25（另一个已是目标值），
  #     整包 patch 会因 hunk 已应用而报错，故按文件逐项处理（幂等）。
  if [ "$a" = "25" ] && [ "$b" = "25" ] && [ -f "$JAVA_PATCH" ]; then
    log "t17: 两文件均为 25 -> 应用整包补丁 $JAVA_PATCH"
    ( cd "$SRC" && patch -p1 --forward < "$JAVA_PATCH" ) 2>&1 | tail -6
  else
    log "t17: 混合状态（cj='$a' tb='$b'）-> 按文件逐项就地修补"
    [ "$a" = "25" ] && sed -i "s/^        '25',$/        '${T}',/" "$cj" && log "t17: 已就地修补 compile_java.py -> ${T}"
    [ "$b" = "25" ] && sed -i "s/javac_cmd = \['--release', '25'\]/javac_cmd = ['--release', '${T}']/" "$tb" && log "t17: 已就地修补 turbine.py -> ${T}"
  fi

  a=$(java_release_value "$cj"); b=$(java_release_value "$tb")
  log "t17: 应用后实测 --release -> compile_java.py='$a'  turbine.py='$b'"
  if [ "$a" != "$T" ] || [ "$b" != "$T" ]; then
    log "FATAL t17: 修补后仍非 $T（a='$a' b='$b'）"; return 1
  fi
  # 与入库补丁对账：确保就地修补与补丁语义一致
  if [ -f "$JAVA_PATCH" ] && command -v patch >/dev/null 2>&1; then
    log "t17: 与入库补丁对账（--dry-run 逆应用应无差异）"
    ( cd "$SRC" && patch -p1 --dry-run --reverse < "$JAVA_PATCH" ) >/dev/null 2>&1 \
      && log "t17: ✅ 当前文件状态与入库补丁一致" \
      || log "WARN t17: 当前文件与入库补丁不完全一致，请人工核对（可能为混合路径修补）"
  fi
  log "t17: 修补完成（结果 17）"
  return 0
}

# 固定 -j2（4 核=2 物理核 + 5GiB available；-j3 峰值易 OOM）
pick_jobs() { echo 2; }

# --- GN 参数（已对源码逐项核验声明存在，见 reports/05）----------------------
#  rtc_include_tests=false     不编测试
#  rtc_build_examples=false    不编 examples
#  rtc_build_tools=false       不编工具
#  symbol_level=0              省磁盘/链接内存
#  rtc_dlog_always_on=true     → -DDLOG_ALWAYS_ON，release 下开启 RTC_DLOG(LS_VERBOSE)
#                               （日志要求 D6；代价远小于 is_debug=true）
#  rtc_disable_logging         **刻意不设置** → 取默认 false（契约 §4.3 红线：不得为 true）
#
#  【为什么必须显式不设 use_custom_libcxx —— 因果链，后人会反复问（契约 §4.2/§4.3 C18）】
#   Android 目标下 use_custom_libcxx 默认为 true ⇒ libwebrtc 的 .a/.so **静态链入 Chromium
#   自带 libc++**（其 std 类型带 `std::__Cr` 内联命名空间，与 NDK c++_shared 的 `std::__ndk1`
#   不是同一套 ABI）。而自有 native 库 libwebrtcdemo_native.so 用 NDK c++_shared 编译
#   （契约 §4.2/§4.4 冻结）。
#   ⇒ 若把 libwebrtc.a 链进自有 .so，同一进程会出现**两份 libc++、两套 std 类型**；
#     跨 .so 传 std::string / rtc::scoped_refptr 属未定义行为，并放大 APK 体积。
#   ⇒ 这正是"A1 注入路线 + 自有 .so **禁止**链接 libwebrtc.a、二者只保留 JNI 边界"的根因，
#     也是本脚本默认 BUILD_WEBRTC_A=0（静态库仅可选、不参与链接）的依据。
GN_ARGS='target_os="android" target_cpu="arm64" is_debug=false is_component_build=false rtc_include_tests=false rtc_build_examples=false rtc_build_tools=false treat_warnings_as_errors=false is_clang=true use_sysroot=true symbol_level=0 rtc_dlog_always_on=true'

phase_gn() {
  log "== gn gen =="
  log "args: $GN_ARGS"
  cd "$SRC" || return 1
  # t17：Java 目标版本补丁必须在任何 Java target 编译前落地
  ensure_java_release_patch || { log "FATAL gn 阶段：Java 补丁校验失败"; return 1; }
  # depot_tools 的 gn wrapper 需要 bootstrap 且常找不到二进制；
  # 直接用 buildtools 预编译 gn（v2562）更可靠
  local GN="$SRC/buildtools/linux64/gn"
  [ -x "$GN" ] || GN=gn
  log "gn=$GN ($("$GN" --version 2>&1 | tail -1))"
  "$GN" gen "$OUT" --args="$GN_ARGS" 2>&1 | tail -25 || { log "FATAL gn gen 失败"; return 1; }
  "$GN" args "$OUT" --list --short > "$LOGDIR/gn-args-resolved.txt" 2>&1 || true
  log "== gn gen 完成 =="
}

# 主编译：契约 §4.3 的【硬交付】两个 target。
#   BUILD_WEBRTC_A=1 时才额外编 `webrtc` 静态库（契约明示为**可选**：仅对照/§5.7 备料，
#   且自有 .so 禁止链接 libwebrtc.a）。默认 0 —— 它耗时长、占磁盘大，会挤占硬交付。
BUILD_WEBRTC_A=${BUILD_WEBRTC_A:-0}
phase_ninja() {
  cd "$SRC" || return 1
  # t17：编译 Java target 前再校验一次（幂等；防止有人跳过 gn 阶段直接跑 ninja）
  ensure_java_release_patch || { log "FATAL ninja 阶段：Java 补丁校验失败"; return 1; }
  runwatchdog
  local J; J=$(pick_jobs)
  log "== ninja -j$J sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so =="
  ninja -C "$OUT" -j"$J" -k 0 sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so 2>&1 | tail -40
  local rc=${PIPESTATUS[0]}
  log "Java SDK target rc=$rc"
  if [ "$rc" != 0 ]; then
    log "WARN 失败，-j1 重试一次"
    ninja -C "$OUT" -j1 sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so 2>&1 | tail -30 || true
  fi
  if [ "$BUILD_WEBRTC_A" = "1" ]; then
    log "== ninja -j$J webrtc (可选静态库备料) =="
    ninja -C "$OUT" -j"$J" -k 0 webrtc 2>&1 | tail -30
    log "webrtc(.a) rc=${PIPESTATUS[0]}"
  else
    log "== 跳过可选 target webrtc（BUILD_WEBRTC_A=0；契约 §4.2 禁止链接 libwebrtc.a）=="
  fi
  find "$SRC/$OUT" \( -name '*.gch' -o -name '*.gcda' \) -delete 2>/dev/null || true
  stopwatchdog
  log "== 编译完成；obj=$(du -sh "$SRC/$OUT/obj" 2>/dev/null | cut -f1) =="
}

# libwebrtc 官方 Android Java SDK → AAR
# 说明：官方 tools_webrtc/android/build_aar.py 内部用 `third_party/siso/cipd/siso ninja`
#       （本机 siso 缺 backend.star 会失败），且它会用**自有 GN 参数**另建 aar/<arch> 目录，
#       会丢掉我们的 rtc_dlog_always_on 日志开关。
#       故这里等价地：① 用系统 ninja 编同样的 target ② 手工打 AAR（官方脚本的核心步骤）。
#   target/jar/so 与官方完全一致（见 doc/14 §4.3）。
phase_aar() {
  cd "$SRC" || return 1
  local J; J=$(pick_jobs)
  runwatchdog
  log "== ninja -j$J sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so (AAR 前置) =="
  # 注：裸 ninja 直跑时，最后的 android_static_analysis 校验 action 会因
  #     AUTONINJA_BUILD_ID 未设而失败（它要求经 autoninja/siso 集成），
  #     但该 action **不影响编译与链接产物**。故以【产物是否存在】为准，
  #     而非仅看 ninja 退出码。设置该变量可让校验也通过。
  export AUTONINJA_BUILD_ID="t5-local-$$"
  ninja -C "$OUT" -j"$J" -k 0 sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so 2>&1 | tail -25
  local rc=${PIPESTATUS[0]}
  stopwatchdog
  local JAR="$SRC/$OUT/lib.java/sdk/android/libwebrtc.jar"
  local SO="$SRC/$OUT/libjingle_peerconnection_so.so"
  log "ninja rc=$rc  jar=$([ -f "$JAR" ] && echo 有 || echo 无)  so=$([ -f "$SO" ] && echo 有 || echo 无)"
  if [ ! -f "$JAR" ] || [ ! -f "$SO" ]; then
    log "FATAL AAR 前置 target 失败 rc=$rc jar=$([ -f "$JAR" ] && echo 有 || echo 无) so=$([ -f "$SO" ] && echo 有 || echo 无)"
    return 1
  fi
  [ "$rc" != 0 ] && log "WARN ninja rc=$rc 非 0，但两个产物均已生成（校验类 action 失败，不影响产物）"
  log "jar=$(ls -lh "$JAR"|awk '{print $5}') so=$(ls -lh "$SO"|awk '{print $5}')"
  # 打 AAR：classes.jar + jni/arm64-v8a/libjingle_peerconnection_so.so + AndroidManifest.xml
  local STAGE=$BUILD/aar-stage
  rm -rf "$STAGE"; mkdir -p "$STAGE/jni/arm64-v8a"
  cp -f "$JAR" "$STAGE/classes.jar"
  cp -f "$SO"  "$STAGE/jni/arm64-v8a/"
  local MAN="$SRC/sdk/android/AndroidManifest.xml"
  if [ -f "$MAN" ]; then cp -f "$MAN" "$STAGE/AndroidManifest.xml";
  else printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.webrtc"/>' > "$STAGE/AndroidManifest.xml"; fi
  ( cd "$STAGE" && zip -qr "$BUILD/libwebrtc-arm64.aar" . ) || { log "FATAL zip 失败"; return 1; }
  log "AAR OK: $(ls -lh "$BUILD/libwebrtc-arm64.aar" | awk '{print $5}')  内容: $(unzip -l "$BUILD/libwebrtc-arm64.aar" | tail -1 | awk '{print $2" files"}')"
}

phase_libvpx() {
  log "== libvpx arm64 (NDK standalone toolchain) =="
  [ -f "$LIBVPX_SRC/configure" ] || git clone --depth 1 https://chromium.googlesource.com/webm/libvpx "$LIBVPX_SRC" || return 1
  cd "$LIBVPX_SRC" || return 1
  local NDK; NDK=$(ls -d "$WS"/android-sdk/ndk/* 2>/dev/null | sort -V | tail -1)
  [ -z "$NDK" ] && NDK=$(ls -d "$SRC"/third_party/android_ndk 2>/dev/null | head -1)
  [ -z "$NDK" ] && { log "FATAL 无 NDK"; return 1; }
  local TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
  local API=21
  log "NDK=$NDK"
  # 注意：现代 libvpx 已移除 --sdk-path/armv8-android-gcc，仅支持 arm64-android-gcc
  # + 通过 CHOST/CROSS/CC/CXX/AR/AS/LD/STRIP 提供 NDK standalone toolchain（configure.sh:1169）
  export CHOST=aarch64-linux-android
  export CROSS="llvm-"
  export CC="$TC/bin/aarch64-linux-android${API}-clang"
  export CXX="$TC/bin/aarch64-linux-android${API}-clang++"
  export AR="$TC/bin/llvm-ar"
  export AS="$TC/bin/clang"
  export LD="$TC/bin/ld.lld"
  export STRIP="$TC/bin/llvm-strip"
  export LD_LIBRARY_PATH="$TC/lib"
  log "CC=$CC"
  make distclean >/dev/null 2>&1 || true
  # 【t56 口径修订 · 真机 SIGILL 根因修复（2026-09-15）】
  # 旧参数含 --disable-runtime-cpu-detect：运行时 SIMD 派发退化为**编译期 #define 直连**
  # （vpx_dsp_rtcd.h / vp9_rtcd.h），core encoder TU 因而直接引用 *_sve / *_neon_i8mm /
  # *_neon_dotprod（t56 实测旧件：vp9_rdopt.c.o 直接 `U vp9_block_error_sve`，vpx_dsp_rtcd.h
  # 直连绑定 _neon_dotprod 72 条 / _neon_i8mm 6 条 / _sve 1 条）⇒ 真机 CPU 无 SVE 时首帧关键帧
  # 的 RD 调用即 SIGILL（信号而非 C++ 异常 ⇒ 零日志，恰好停在 encode_vpx_begin 之后）。
  # 现口径（两项一起，理由见 docs 报告 reports/26-libvpx-runtime-cpu-detect.md）：
  #   ① 显式 --enable-runtime-cpu-detect：不依赖 configure 默认值，恢复运行时派发；dotprod/i8mm
  #      经 vpx_ports/aarch64_cpudetect.c 的 __linux__ 分支用 getauxval(AT_HWCAP/AT_HWCAP2) 探测后才选用。
  #   ② 显式 --disable-sve --disable-sve2：SVE/SVE2 在现行 Android 手机上无收益，却是本事故的爆炸半径；
  #      关闭后 HAVE_SVE=HAVE_SVE2=0，产物内 SVE 成员/符号/指令一律为 0，可静态验收。
  ./configure --target=arm64-android-gcc \
      --enable-vp9 --enable-vp9-encoder --enable-vp9-decoder \
      --disable-vp8-encoder --disable-vp8-decoder \
      --enable-static --disable-shared --disable-examples --disable-tools \
      --disable-docs --disable-unit-tests --enable-runtime-cpu-detect \
      --disable-sve --disable-sve2 --enable-pic \
      --prefix="$LIBVPX_OUT" 2>&1 | tail -15
  [ -f Makefile ] || { log "FATAL libvpx configure 失败"; return 1; }
  make -j4 2>&1 | tail -12 || { log "FATAL libvpx make 失败"; return 1; }
  make install 2>&1 | tail -6
  # 【t56 自检】口径不符即失败（不要把带 SVE 直连的库当成功产物放行）
  local SVE_CNT RCD_CNT
  SVE_CNT=$("$TC/bin/llvm-ar" t "$LIBVPX_OUT/lib/libvpx.a" 2>/dev/null | grep -ci sve || true)
  RCD_CNT=$(grep -c '^#define CONFIG_RUNTIME_CPU_DETECT 1$' vpx_config.h 2>/dev/null || true)
  if [ "${SVE_CNT:-1}" != "0" ] || [ "${RCD_CNT:-0}" != "1" ]; then
    log "FATAL libvpx 口径不符（t56）：SVE 成员=$SVE_CNT（期望 0） CONFIG_RUNTIME_CPU_DETECT=$RCD_CNT（期望 1）"
    return 1
  fi
  log "  t56 口径自检通过：SVE 成员=0；CONFIG_RUNTIME_CPU_DETECT=1；HAVE_SVE/SVE2=0"
  log "== libvpx 完成: $(file -b "$LIBVPX_OUT/lib/libvpx.a" 2>/dev/null | cut -c1-40) =="
}

# 产物收集 + 架构核验 + 清单
phase_extract() {
  cd "$SRC" || return 1
  mkdir -p "$TP/libwebrtc/lib" "$TP/libwebrtc/include" "$TP/libwebrtc/java/jni/arm64-v8a" "$TP/libvpx/lib" "$TP/libvpx/include"
  log "== extract =="
  if [ -f "$BUILD/libwebrtc-arm64.aar" ]; then
    cp -f "$BUILD/libwebrtc-arm64.aar" "$TP/libwebrtc/java/libwebrtc-arm64.aar"
    rm -rf "$TP/libwebrtc/java/aar-unpacked"
    (cd "$TP/libwebrtc/java" && mkdir -p aar-unpacked && cd aar-unpacked && unzip -oq ../libwebrtc-arm64.aar) || true
    find "$TP/libwebrtc/java/aar-unpacked" -name 'classes.jar' -exec cp -f {} "$TP/libwebrtc/java/libwebrtc-java.jar" \; 2>/dev/null
    find "$TP/libwebrtc/java/aar-unpacked" -name 'libjingle_peerconnection_so.so' -exec cp -f {} "$TP/libwebrtc/java/jni/arm64-v8a/" \; 2>/dev/null
  fi
  [ -f "$OUT/lib.java/sdk/android/libwebrtc.jar" ] && cp -f "$OUT/lib.java/sdk/android/libwebrtc.jar" "$TP/libwebrtc/java/libwebrtc-java.jar"
  # ⚠️ 必须用 out/ 顶层的【已 strip】so，不能用 lib.unstripped/ 的（后者带调试段、体积大，
  #    且 Android 打包惯例是 stripped 版；see out/lib.unstripped/）。
  if [ -f "$OUT/libjingle_peerconnection_so.so" ]; then
    cp -f "$OUT/libjingle_peerconnection_so.so" "$TP/libwebrtc/java/jni/arm64-v8a/"
  else
    log "WARN 未找到 stripped so，回退 lib.unstripped（体积偏大）"
    find "$OUT" -maxdepth 2 -name 'libjingle_peerconnection_so.so' -exec cp -f {} "$TP/libwebrtc/java/jni/arm64-v8a/" \; 2>/dev/null
  fi
  find "$OUT/obj" -name '*.a' -exec cp -n {} "$TP/libwebrtc/lib/" \; 2>/dev/null
  local H="$TP/libwebrtc/include"
  for d in api rtc_base modules pc media call system_wrappers sdk p2p common_audio common_video logging stats audio video; do
    [ -d "$d" ] && { mkdir -p "$H/$d"; cp -r "$d/." "$H/$d/" 2>/dev/null; }
  done
  [ -f common_types.h ] && cp -f common_types.h "$H/"
  [ -d third_party/abseil-cpp/absl ] && { mkdir -p "$H/third_party"; cp -r third_party/abseil-cpp/absl "$H/third_party/" 2>/dev/null; }
  [ -d third_party/libyuv/include ] && { mkdir -p "$H/third_party/libyuv"; cp -r third_party/libyuv/include "$H/third_party/libyuv/" 2>/dev/null; }
  [ -d "$LIBVPX_OUT/lib" ] && cp -rf "$LIBVPX_OUT/lib/." "$TP/libvpx/lib/" 2>/dev/null
  [ -d "$LIBVPX_OUT/include" ] && cp -rf "$LIBVPX_OUT/include/." "$TP/libvpx/include/" 2>/dev/null
  # 【t56】libvpx 的 make install 不安装 vpx_config.h；把构建期该文件一并入库，
  # 使交付目录自证口径（CONFIG_RUNTIME_CPU_DETECT=1 / HAVE_SVE=HAVE_SVE2=0）。
  [ -f "$LIBVPX_SRC/vpx_config.h" ] && cp -f "$LIBVPX_SRC/vpx_config.h" "$TP/libvpx/include/vpx_config.h"

  {
    echo "### 架构核验 $(date -u +%FT%TZ)"
    echo '```'
    for f in $(find "$TP/libwebrtc/lib" "$TP/libwebrtc/java/jni" "$TP/libvpx/lib" \( -name '*.a' -o -name '*.so' \) 2>/dev/null | sort); do
      printf '%-72s %s\n' "$(basename "$f")" "$(file -b "$f" | cut -c1-55)"
    done
    echo '```'
    echo "### 产物清单 (大小 + 绝对路径)"
    echo '```'
    find "$TP/libwebrtc" "$TP/libvpx" -type f \( -name '*.a' -o -name '*.so' -o -name '*.jar' -o -name '*.aar' \) -printf '%10s  %p\n' 2>/dev/null | sort -k2
    echo '```'
    echo "### 头文件目录"
    echo '```'
    ls "$TP/libwebrtc/include" "$TP/libvpx/include/vpx" 2>/dev/null
    echo '```'
  } > "$LOGDIR/artifact-inventory.md" 2>&1
  chown -R 1000:1000 "$TP/libwebrtc" "$TP/libvpx" 2>/dev/null || true
  cat "$LOGDIR/artifact-inventory.md"
  log "== extract 完成 =="
}

case "$PHASE" in
  gn)      phase_gn ;;
  patch)   ensure_java_release_patch ;;   # t17：可单独验证补丁幂等性
  aar)     phase_aar ;;
  libvpx)  phase_libvpx ;;
  extract) phase_extract ;;
  all)     phase_gn && phase_ninja && phase_aar && phase_libvpx && phase_extract; log "===== ALL DONE =====" ;;
  build)   phase_ninja && phase_aar && phase_extract; log "===== BUILD DONE =====" ;;
  *) echo "用法: $0 {gn|patch|build|ninja|aar|libvpx|extract|all}"; exit 2 ;;
esac
