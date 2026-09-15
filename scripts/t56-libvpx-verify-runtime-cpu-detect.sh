#!/bin/bash
# t56 验收脚本（只读）——核验 arm64 libvpx.a 的「运行时 CPU 探测」口径与 SVE 直连残留
#
# 用法:
#   scripts/t56-libvpx-verify-runtime-cpu-detect.sh [libvpx.a] [vpx_config.h] [构建期 rtcd 目录]
# 默认对象 = 交付件 third_party/libvpx/{lib/libvpx.a, include/vpx_config.h}；
# 第 3 个参数给 webrtc-build/t56-libvpx-src（含 vpx_dsp_rtcd.h/vp9_rtcd.h）时，额外核验「直连绑定=0」。
#
# 依赖：NDK 的 llvm-ar / llvm-nm / llvm-objdump（自动在宿主 /opt/dsh-workspaces 与
#       容器 /data/dsh/home/workspace 两种工作区根之间择一）。
#
# 注意：`grep -c` 命中 0 次时仍会打印 "0"（仅退出码非 0），故这里直接取其输出、不加 `|| echo 0`。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="${1:-$ROOT/third_party/libvpx/lib/libvpx.a}"
C="${2:-$ROOT/third_party/libvpx/include/vpx_config.h}"
RT="${3:-}"
if [ -d /opt/dsh-workspaces/android-sdk ]; then WSROOT=/opt/dsh-workspaces; else WSROOT=/data/dsh/home/workspace; fi
NDK="${NDK:-$WSROOT/android-sdk/ndk/26.1.10909125}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
AR="$BIN/llvm-ar"; NM="$BIN/llvm-nm"; OD="$BIN/llvm-objdump"
fail=0
chk() { # 名称 期望 实际
  if [ "$2" = "$3" ]; then printf 'PASS  %-44s = %s\n' "$1" "$3"
  else printf 'FAIL  %-44s = %s (期望 %s)\n' "$1" "$3" "$2"; fail=1; fi
}
echo "对象  : $A"
echo "配置  : $C"
echo "工具  : $AR"
echo "----------------------------------------"
chk "CONFIG_RUNTIME_CPU_DETECT=1" 1 "$(grep -c '^#define CONFIG_RUNTIME_CPU_DETECT 1$' "$C" 2>/dev/null)"
chk "HAVE_SVE=0"                 1 "$(grep -c '^#define HAVE_SVE 0$' "$C" 2>/dev/null)"
chk "HAVE_SVE2=0"                1 "$(grep -c '^#define HAVE_SVE2 0$' "$C" 2>/dev/null)"
chk "SVE 成员数"                  0 "$("$AR" t "$A" 2>/dev/null | grep -ci sve)"
chk "符号中 sve 行数"             0 "$("$NM" "$A" 2>/dev/null | grep -ci sve)"
chk "非分派器直连 *_sve 引用数"   0 "$("$NM" -A "$A" 2>/dev/null | grep ' U ' | grep -ci sve)"
chk "SVE z 寄存器指令数"          0 "$("$OD" -d "$A" 2>/dev/null | grep -cE '[[:space:]]z[0-9]+\.' )"
echo "--- 分派器/探测函数在位（应非空）---"
"$NM" "$A" 2>/dev/null | grep -E ' T (vpx_dsp_rtcd|vp9_rtcd|arm_cpu_caps)' | sed 's/^/      /'
if [ -n "$RT" ] && [ -f "$RT/vpx_dsp_rtcd.h" ]; then
  echo "--- 构建期 rtcd 直连绑定（$RT）---"
  for ext in _sve _neon_i8mm _neon_dotprod; do
    chk "vpx_dsp_rtcd.h 直连 ${ext} 绑定数" 0 "$(grep -c "define .*${ext}\$" "$RT/vpx_dsp_rtcd.h")"
  done
  chk "vp9_rtcd.h 直连 _sve 绑定数" 0 "$(grep -c 'define .*_sve$' "$RT/vp9_rtcd.h")"
fi
echo "----------------------------------------"
if [ $fail -eq 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL"; fi
exit $fail
