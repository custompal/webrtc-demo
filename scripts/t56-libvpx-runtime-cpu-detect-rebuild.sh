#!/bin/bash
# t56: 重建 arm64 libvpx —— 开启运行时 CPU 探测（修 SIGILL：SVE 编译期直连）
WS=/opt/dsh-workspaces
REPO=$WS/code/webrtc-demo
S=$WS/webrtc-build/t56-libvpx-src
OUT=$WS/webrtc-build/t56-libvpx-output
EV=$WS/webrtc-build/t56-evidence
NDK=$WS/android-sdk/ndk/26.1.10909125
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=21
say() { echo "[$(date -u +%FT%TZ)] $*"; }
mkdir -p "$EV" "$OUT" "$WS/tmp/t56"
say "t56 start; NDK=$NDK"
df -h "$WS" | tail -1
# 归档旧证据（重建前）
cp -f "$WS/webrtc-build/libvpx-src/vpx_config.h" "$EV/old-vpx_config.h" 2>/dev/null && say "archived old vpx_config.h"
cp -f "$WS/webrtc-build/libvpx-src/vp9_rtcd.h"   "$EV/old-vp9_rtcd.h"   2>/dev/null && say "archived old vp9_rtcd.h"
cp -f "$REPO/third_party/libvpx/lib/libvpx.a"    "$WS/tmp/t56/libvpx.a.pre-t56-e280b11b" && say "archived old libvpx.a -> $WS/tmp/t56/libvpx.a.pre-t56-e280b11b"
sha256sum "$WS/tmp/t56/libvpx.a.pre-t56-e280b11b"
cd "$S" || { echo FATAL_NOSRC; exit 1; }
export CHOST=aarch64-linux-android
export CROSS=llvm-
export CC="$TC/bin/aarch64-linux-android${API}-clang"
export CXX="$TC/bin/aarch64-linux-android${API}-clang++"
export AR="$TC/bin/llvm-ar"
export AS="$TC/bin/clang"
export LD="$TC/bin/ld.lld"
export STRIP="$TC/bin/llvm-strip"
export LD_LIBRARY_PATH="$TC/lib"
say "CC=$CC"
say "== configure (NEW: --enable-runtime-cpu-detect --disable-sve --disable-sve2) =="
./configure --target=arm64-android-gcc \
    --enable-vp9 --enable-vp9-encoder --enable-vp9-decoder \
    --disable-vp8-encoder --disable-vp8-decoder \
    --enable-static --disable-shared --disable-examples --disable-tools \
    --disable-docs --disable-unit-tests --enable-runtime-cpu-detect \
    --disable-sve --disable-sve2 --enable-pic \
    --prefix="$OUT"
RC=$?
say "CONFIGURE_RC=$RC"
if [ $RC -ne 0 ]; then say FATAL_CONFIGURE; exit 1; fi
say "== new config evidence =="
grep -n "CONFIG_RUNTIME_CPU_DETECT\|HAVE_SVE\b\|HAVE_SVE2\|HAVE_NEON_DOTPROD\|HAVE_NEON_I8MM" vpx_config.h
say "-- vp9_rtcd.h block_error --"
grep -n "define vp9_block_error" vp9_rtcd.h | head -4
say "-- rtcd sve bindings count --"
grep -c "define .*_sve$" vp9_rtcd.h || true
say "== make -j4 =="
make -j4
RC=$?
say "MAKE_RC=$RC"
if [ $RC -ne 0 ]; then say FATAL_MAKE; exit 1; fi
say "== make install =="
make install
say "INSTALL_RC=$?"
ls -l "$OUT/lib/libvpx.a"
find "$OUT" -name 'vpx_config.h' -printf 'installed config: %p %s\n' 2>/dev/null
cp -f "$S/vpx_config.h" "$EV/new-vpx_config.h"
cp -f "$S/vp9_rtcd.h"   "$EV/new-vp9_rtcd.h"
cp -f "$S/vpx_dsp_rtcd.h" "$EV/new-vpx_dsp_rtcd.h" 2>/dev/null
cp -f "$S/vpx_scale_rtcd.h" "$EV/new-vpx_scale_rtcd.h" 2>/dev/null
say "t56 end"
