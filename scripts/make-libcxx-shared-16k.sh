#!/usr/bin/env bash
# ============================================================================
# make-libcxx-shared-16k.sh —— 生成 16 KB 页对齐（p_align=0x4000）的 libc++_shared.so
# ----------------------------------------------------------------------------
# 为什么需要（背景，详见 reports/07-native-dev.md §15）：
#   Android 15+ 的 **16 KB 页**设备要求所有 `.so` 的 ELF LOAD 段 `p_align=0x4000`，
#   否则 `dlopen` 失败。NDK **26.1.10909125（契约 §3.1 冻结）**自带的
#   `libc++_shared.so` 是 4 KB 对齐（0x1000）；而本项目自有库
#   `libwebrtcdemo_native.so` 动态依赖它 ⇒ 整包在 16 KB 设备上无法加载。
#
# 方案（t24 裁定 = 方案 b，**零契约变更**）：
#   用**同一个冻结 NDK** 自带的静态库 (libc++_static.a + libc++abi.a) 自链接一个
#   同 soname 的 `libc++_shared.so`，仅把 `max-page-size` 抬到 16384。
#   实测导出符号与 NDK 官方件**完全一致（2358，缺失 0）**，因此可安全替换随包件。
#   被否决的替代：改 `c++_static`（改契约 §3.1/§4.1 的 STL 冻结值）、升级 NDK（契约级）。
#
# 产物（**被 .gitignore 的 `*.so` 忽略，不入库**；脚本本身入库）：
#   app/src/main/jniLibs/arm64-v8a/libc++_shared.so   ← strip 后，供 AGP 打包
#
# 用法（宿主机或容器内均可，只要有该 NDK）：
#   bash scripts/make-libcxx-shared-16k.sh              # 生成并落位 + 自校验
#   bash scripts/make-libcxx-shared-16k.sh --check-only # 只校验已落位文件
#   WORKSPACE_ROOT=/data/dsh/home/workspace bash scripts/make-libcxx-shared-16k.sh
#
# 依赖：bash、sha256sum、awk/sed、该 NDK 的 clang++/llvm-ar/llvm-strip/llvm-*。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(cd "$SCRIPT_DIR/.." && pwd)"                    # <WS>/code/webrtc-demo
WS="${WORKSPACE_ROOT:-$(cd "$PROJ/../.." && pwd)}"      # 工作区根（含 android-sdk/）
NDK="${ANDROID_NDK_HOME:-$WS/android-sdk/ndk/26.1.10909125}"

# NDK 版本必须与契约 §3.1 冻结值一致（换版本会让下面的输入 sha256 失配而报错）
NDK_REQUIRED="26.1.10909125"
# 输入（冻结 NDK 自带静态库）sha256 —— 变了说明工具链被动过，必须人工复核
LIBXX_STATIC_SHA="6ca7a759b2742bba79654923b0f410c946e1967e744e1442d1e72ab1045534e8"
LIBXXABI_SHA="5e1a821b418e1e58c1d1cab03da08b63fcb30fb60341a5576faef5d3cf775358"
# 官方（4 KB）随包件 sha256 —— 仅用于导出符号对照
OFFICIAL_SHARED_SHA="4e843755cda12ed65cd2b450be720b122d6657b24b690bf32de74fdc3f529447"
# 输出（自链接）sha256 —— 用于可复现性自校验（lld 输出对本输入是确定的）
OUT_RAW_SHA="32cb92c2517205020298771c60cf8537cfac551863f75f4da095ba547d7f9109"
OUT_STRIPPED_SHA="c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36"
EXPECTED_EXPORTS=2358
EXPECTED_ALIGN="0x4000"

DEST_DIR="$PROJ/app/src/main/jniLibs/arm64-v8a"
DEST="$DEST_DIR/libc++_shared.so"

CHECK_ONLY=0
[ "${1:-}" = "--check-only" ] && CHECK_ONLY=1

BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android"
CLANGXX="$BIN/aarch64-linux-android26-clang++"
STRIP="$BIN/llvm-strip"
NM="$BIN/llvm-nm"
READELF="$BIN/llvm-readelf"

die() { echo "FAIL: $*" >&2; exit 1; }
ok()  { echo "  ok  : $*"; }

echo "== NDK: $NDK"
[ -x "$CLANGXX" ] || die "找不到 $CLANGXX（NDK 未安装？）"
revision="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties" | head -1)"
echo "== NDK revision: $revision（要求 $NDK_REQUIRED）"
[ "$revision" = "$NDK_REQUIRED" ] || die "NDK 版本不是冻结值 $NDK_REQUIRED（不自行升级，见报告 §15.3 方案 c）"

verify() {  # 校验一个 libc++_shared.so 是否满足全部 16 KB 前置条件
  local f="$1"
  [ -f "$f" ] || die "文件不存在: $f"
  local aligns soname exports missing
  aligns="$("$READELF" -l "$f" | awk '/^  LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
  soname="$("$READELF" -d "$f" | awk '/SONAME/{print $NF}' | tr -d '[]')"
  exports="$("$NM" -D --defined-only "$f" | wc -l)"
  # 与官方件的导出符号对照：官方有而本件缺失的数量必须为 0
  missing="$(comm -23 <("$NM" -D --defined-only "$SYS/libc++_shared.so" | awk '{print $3}' | sort -u) \
                      <("$NM" -D --defined-only "$f" | awk '{print $3}' | sort -u) | wc -l)"
  echo "  p_align(LOAD): ${aligns:-<none>}"
  echo "  SONAME       : ${soname:-<none>}"
  echo "  导出符号数   : $exports（期望 $EXPECTED_EXPORTS）"
  echo "  官方有而缺失 : $missing（期望 0）"
  echo "  sha256       : $(sha256sum "$f" | awk '{print $1}')"
  [ "$aligns" = "$EXPECTED_ALIGN " ] || die "p_align 不是 $EXPECTED_ALIGN：$aligns"
  [ "$soname" = "libc++_shared.so" ] || die "SONAME 不是 libc++_shared.so：$soname"
  [ "$exports" = "$EXPECTED_EXPORTS" ] || die "导出符号数不是 $EXPECTED_EXPORTS：$exports"
  [ "$missing" = "0" ] || die "相对官方件缺失 $missing 个导出符号"
}

if [ "$CHECK_ONLY" = "1" ]; then
  echo "== 只校验已落位文件: $DEST"
  verify "$DEST"
  echo "CHECK_OK"
  exit 0
fi

echo "== 校验输入静态库（冻结 NDK 自带）"
for pair in "libc++_static.a:$LIBXX_STATIC_SHA" "libc++abi.a:$LIBXXABI_SHA" "libc++_shared.so:$OFFICIAL_SHARED_SHA"; do
  name="${pair%%:*}"; want="${pair##*:}"
  got="$(sha256sum "$SYS/$name" | awk '{print $1}')"
  if [ "$got" != "$want" ]; then
    die "$name sha256 失配：got=$got want=$want（工具链被动过？请人工复核后再跑）"
  fi
  ok "$name sha256 匹配"
done

TMPD="$(mktemp -d "${TMPDIR:-/tmp}/libcxx16k.XXXXXX")"
trap 'rm -rf "$TMPD"' EXIT

echo "== 自链接（-Wl,-z,max-page-size=16384）"
"$CLANGXX" -shared -fuse-ld=lld -nostdlib++ \
  -Wl,-soname,libc++_shared.so -Wl,-z,max-page-size=16384 \
  -Wl,--whole-archive "$SYS/libc++_static.a" "$SYS/libc++abi.a" -Wl,--no-whole-archive \
  -lc -lm -ldl -o "$TMPD/libc++_shared.raw.so"
raw_sha="$(sha256sum "$TMPD/libc++_shared.raw.so" | awk '{print $1}')"
echo "  未 strip sha256: $raw_sha"
[ "$raw_sha" = "$OUT_RAW_SHA" ] || die "未 strip 产物 sha256 与预期不符（got=$raw_sha want=$OUT_RAW_SHA）"

echo "== strip（AGP 打包前会 strip，这里预先 strip 以便与 APK 内件逐一对应）"
"$STRIP" --strip-unneeded -o "$TMPD/libc++_shared.so" "$TMPD/libc++_shared.raw.so"
strip_sha="$(sha256sum "$TMPD/libc++_shared.so" | awk '{print $1}')"
echo "  strip 后 sha256: $strip_sha"
[ "$strip_sha" = "$OUT_STRIPPED_SHA" ] || die "strip 后 sha256 与预期不符（got=$strip_sha want=$OUT_STRIPPED_SHA）"

echo "== 落位: $DEST"
mkdir -p "$DEST_DIR"
cp -f "$TMPD/libc++_shared.so" "$DEST"
chmod 644 "$DEST"

echo "== 自校验"
verify "$DEST"
echo
echo "RESULT: OK"
echo "  script          = scripts/make-libcxx-shared-16k.sh"
echo "  输入 sha256     = libc++_static.a=$LIBXX_STATIC_SHA libc++abi.a=$LIBXXABI_SHA"
echo "  输出 sha256     = raw=$OUT_RAW_SHA stripped=$OUT_STRIPPED_SHA"
echo "  落位路径        = $DEST"
