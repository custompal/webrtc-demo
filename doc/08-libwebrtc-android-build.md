# 08 — libwebrtc Android arm64 编译完整指南

> 本文档供 dsh agent 在云主机上自动执行编译。所有命令均为可直接复制的 shell 命令。

## 1. 环境要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Ubuntu 22.04 LTS x86_64（云主机） |
| 内存 | 最低 16GB，推荐 32GB |
| 磁盘 | 最低 100GB 可用空间 |
| 网络 | 需访问 Google 源（googlesource.com）；若被墙需配代理 |
| Python | 3.x（系统自带即可） |
| Git | 2.20+ |

**前提**：Android 开发编译**仅在 Linux 上支持** [$TRAE_REF](https://webrtc.github.io/webrtc-org/native-code/android/)。

## 2. 安装 depot_tools

```bash
# 创建工作目录
mkdir -p ~/webrtc-build
cd ~/webrtc-build

# 克隆 depot_tools
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git

# 加入 PATH（写入 ~/.bashrc 永久生效）
echo 'export PATH=$HOME/webrtc-build/depot_tools:$PATH' >> ~/.bashrc
source ~/.bashrc

# 验证
gclient --version
```

## 3. 拉取 libwebrtc 源码（Android 配置）

```bash
cd ~/webrtc-build

# fetch Android 配置的 webrtc 源码
# --nohooks 先跳过 hooks，减少首次失败
fetch --nohooks webrtc_android

# 同步依赖（含 Android SDK/NDK ~8GB）
# 这一步耗时最长（30-60 分钟，取决于网络）
gclient sync
```

**产物**：`~/webrtc-build/src/` 目录，总大小约 16GB（含 Android SDK+NDK）[$TRAE_REF](https://webrtc.github.io/webrtc-org/native-code/android/)。

### 3.1 可选：切换到特定稳定分支

```bash
cd ~/webrtc-build/src
# 查看可用分支
git branch -r | grep branch-heads
# 切换到 M120（示例）
git checkout -b m120 branch-heads/5735
# 重新同步
gclient sync --nohooks
gclient runhooks
```

## 4. 安装 Android 编译依赖

```bash
cd ~/webrtc-build/src

# 安装 Linux 系统依赖（仅需一次）
sudo ./build/install-build-deps.sh

# 安装 Android 额外依赖
sudo ./build/install-build-deps-android.sh
```

## 5. GN 生成构建文件（Release arm64）

```bash
cd ~/webrtc-build/src

# 生成 Release 配置
gn gen out/Release-arm64 --args='\
target_os="android" \
target_cpu="arm64" \
is_debug=false \
is_component_build=false \
rtc_include_tests=false \
treat_warnings_as_errors=false \
use_custom_libcxx=false \
is_clang=true \
use_sysroot=true'
```

**参数说明** [$TRAE_REF](https://webrtc.github.io/webrtc-org/native-code/android/)：

| 参数 | 值 | 说明 |
|---|---|---|
| `target_os` | `"android"` | 目标平台 Android |
| `target_cpu` | `"arm64"` | ARM 64 位 |
| `is_debug` | `false` | Release 构建 |
| `is_component_build` | `false` | 静态库而非动态组件 |
| `rtc_include_tests` | `false` | 不编译测试（省时间） |
| `treat_warnings_as_errors` | `false` | 警告不视为错误 |
| `use_custom_libcxx` | `false` | 使用系统 libc++ |
| `is_clang` | `true` | 使用 Clang 编译器 |
| `use_sysroot` | `true` | 使用 sysroot 交叉编译 |

## 6. Ninja 编译

```bash
cd ~/webrtc-build/src

# 编译（耗时 1-3 小时，取决于机器配置）
ninja -C out/Release-arm64

# 如果只需要核心库（更快）：
# ninja -C out/Release-arm64 libwebrtc
```

## 7. 产物提取

编译成功后，需要从 `out/Release-arm64/` 中提取 `.a` 静态库和头文件。

### 7.1 提取静态库

```bash
# 主产物
ls -la ~/webrtc-build/src/out/Release-arm64/obj/libwebrtc.a
ls -la ~/webrtc-build/src/out/Release-arm64/obj/libwebrtc_common.a

# 创建本地产物目录
mkdir -p ~/webrtc-build/output/lib
mkdir -p ~/webrtc-build/output/include

# 拷贝静态库
cp ~/webrtc-build/src/out/Release-arm64/obj/libwebrtc.a ~/webrtc-build/output/lib/
cp ~/webrtc-build/src/out/Release-arm64/obj/libwebrtc_common.a ~/webrtc-build/output/lib/

# 可能还有其他依赖库，全部拷贝
find ~/webrtc-build/src/out/Release-arm64/obj -name "*.a" \
  -exec cp {} ~/webrtc-build/output/lib/ \;
```

### 7.2 提取头文件

```bash
# libwebrtc API 头文件位于 src/ 目录下
# 只需提取 api/ 下的公开接口
cp -r ~/webrtc-build/src/api ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/rtc_base ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/modules ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/common_types.h ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/pc ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/media ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/call ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/sdk/media ~/webrtc-build/output/include/ 2>/dev/null || true
cp -r ~/webrtc-build/src/system_wrappers ~/webrtc-build/output/include/
cp -r ~/webrtc-build/src/absl ~/webrtc-build/output/include/ 2>/dev/null || true
```

### 7.3 打包为 tar

```bash
cd ~/webrtc-build
tar czf webrtc-android-arm64.tar.gz -C output .

# 查看大小
ls -lh webrtc-android-arm64.tar.gz
```

## 8. 回传到 Windows 开发机

```bash
# 在 Windows 开发机执行
scp root@<CLOUD_IP>:~/webrtc-build/webrtc-android-arm64.tar.gz \
  e:\code\project\webrt-demo\third_party\

# 解压到 third_party/libwebrtc/
cd e:\code\project\webrt-demo\third_party
mkdir -p libwebrtc
tar xzf webrtc-android-arm64.tar.gz -C libwebrtc --strip-components=0
```

**产物结构**：
```
third_party/libwebrtc/
├── lib/
│   ├── libwebrtc.a
│   ├── libwebrtc_common.a
│   └── ... (其他 .a)
└── include/
    ├── api/
    ├── rtc_base/
    ├── modules/
    ├── pc/
    ├── media/
    ├── call/
    └── system_wrappers/
```

## 9. libvpx VP9 编解码库交叉编译

libvpx 是 VP9 编解码的底层库，自研编码器需链接它。

### 9.1 前置：Android NDK

```bash
# libwebrtc 源码已自带 NDK，路径如下：
export NDK_ROOT=~/webrtc-build/src/third_party/android_toolchain
export TOOLCHAIN=$NDK_ROOT/toolchain
export SYSROOT=$NDK_ROOT/sysroot
export API=21  # Android 5.0+ (API 21)
```

### 9.2 克隆 libvpx 源码

```bash
cd ~/webrtc-build
git clone https://chromium.googlesource.com/webm/libvpx libvpx-src
cd libvpx-src
```

### 9.3 配置 arm64 交叉编译

```bash
# 设置 NDK 工具链前缀
export CROSS_PREFIX=aarch64-linux-android-
export NDK_PATH=~/webrtc-build/src/third_party/android_toolchain

# 配置 libvpx
./configure \
  --target=arm64-android-gcc \
  --sdk-path=$NDK_PATH \
  --enable-vp9 \
  --enable-vp9-encoder \
  --enable-vp9-decoder \
  --disable-vp8-encoder \
  --disable-vp8-decoder \
  --enable-static \
  --disable-shared \
  --disable-examples \
  --disable-tools \
  --disable-docs \
  --disable-unit-tests \
  --disable-runtime-cpu-detect \
  --enable-pic \
  --prefix=$HOME/webrtc-build/libvpx-output

make -j$(nproc)
make install
```

### 9.4 产物

```bash
# 静态库
ls -la ~/webrtc-build/libvpx-output/lib/libvpx.a
# 头文件
ls -la ~/webrtc-build/libvpx-output/include/vpx/
#   vpx_codec.h  vpx_encoder.h  vpx_decoder.h  vpx_image.h  vpx_frame_buffer.h
```

### 9.5 回传 libvpx

```bash
# 在 Windows 开发机执行
scp -r root@<CLOUD_IP>:~/webrtc-build/libvpx-output \
  e:\code\project\webrt-demo\third_party\libvpx\
```

**产物结构** [$TRAE_REF](https://github.com/denghe/libvpx_prebuilt)：
```
third_party/libvpx/
├── lib/
│   └── libvpx.a
└── include/
    └── vpx/
        ├── vpx_codec.h
        ├── vpx_encoder.h
        ├── vpx_decoder.h
        ├── vpx_image.h
        └── vpx_frame_buffer.h
```

## 10. 常见错误与处理

| 错误 | 原因 | 解决 |
|---|---|---|
| `fetch: command not found` | depot_tools 未加入 PATH | `export PATH=$HOME/webrtc-build/depot_tools:$PATH` |
| `gclient sync` 卡住 / 超时 | Google 源被墙 | 配代理：`export http_proxy=...` 或用 VPN |
| `install-build-deps-android.sh` 权限不足 | 需要 root | `sudo ./build/install-build-deps-android.sh` |
| `ninja: error: loading 'build.ninja'` | GN 未生成成功 | 重新 `gn gen`，检查 args 参数 |
| 编译 OOM (Out of Memory) | 内存不足 | `ninja -j4`（限制并行数） |
| `libvpx configure: error: C compiler test failed` | NDK 路径不对 | 确认 `--sdk-path` 指向正确 NDK 路径 |
| `gn gen` 报 `unknown argument` | 参数名变更 | 查 `gn args --list out/Release-arm64` |

## 11. 幂等性与断点续编

- `ninja -C out/Release-arm64` 天然支持增量编译，中断后重跑只编译未完成的 target
- `gclient sync` 可安全重跑，会跳过已同步的仓库
- `gn gen` 可安全重跑，会覆盖之前的生成文件
- **清理重来**（仅必要时）：`rm -rf out/Release-arm64 && gn gen out/Release-arm64 --args='...'`

## 12. 完整脚本（可一次性执行）

将以下脚本保存为 `scripts/build_libwebrtc.sh`，在云主机执行：

```bash
#!/bin/bash
set -e

WORKSPACE=~/webrtc-build
CLOUD_IP=${1:-"127.0.0.1"}

echo "===== 1. 安装 depot_tools ====="
if [ ! -d "$WORKSPACE/depot_tools" ]; then
  git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git "$WORKSPACE/depot_tools"
fi
export PATH=$WORKSPACE/depot_tools:$PATH

echo "===== 2. 拉取 libwebrtc 源码 ====="
cd "$WORKSPACE"
if [ ! -d "src" ]; then
  fetch --nohooks webrtc_android
  gclient sync
fi

echo "===== 3. 安装依赖 ====="
cd "$WORKSPACE/src"
sudo ./build/install-build-deps.sh <<< "y"
sudo ./build/install-build-deps-android.sh

echo "===== 4. GN 生成 ====="
gn gen out/Release-arm64 --args='\
target_os="android" target_cpu="arm64" is_debug=false \
is_component_build=false rtc_include_tests=false \
treat_warnings_as_errors=false use_custom_libcxx=false \
is_clang=true use_sysroot=true'

echo "===== 5. Ninja 编译 ====="
ninja -C out/Release-arm64

echo "===== 6. 提取产物 ====="
mkdir -p "$WORKSPACE/output/lib" "$WORKSPACE/output/include"
find out/Release-arm64/obj -name "*.a" -exec cp {} "$WORKSPACE/output/lib/" \;
cp -r api rtc_base modules pc media call system_wrappers "$WORKSPACE/output/include/"
[ -d absl ] && cp -r absl "$WORKSPACE/output/include/"
[ -f common_types.h ] && cp common_types.h "$WORKSPACE/output/include/"
[ -d sdk/media ] && cp -r sdk/media "$WORKSPACE/output/include/" || true

echo "===== 7. 打包 ====="
cd "$WORKSPACE"
tar czf webrtc-android-arm64.tar.gz -C output .

echo "===== 8. 编译 libvpx ====="
if [ ! -d "libvpx-src" ]; then
  git clone https://chromium.googlesource.com/webm/libvpx libvpx-src
fi
cd libvpx-src
NDK_PATH="$WORKSPACE/src/third_party/android_toolchain"
make distclean 2>/dev/null || true
./configure \
  --target=arm64-android-gcc \
  --sdk-path=$NDK_PATH \
  --enable-vp9 --enable-vp9-encoder --enable-vp9-decoder \
  --disable-vp8-encoder --disable-vp8-decoder \
  --enable-static --disable-shared \
  --disable-examples --disable-tools --disable-docs --disable-unit-tests \
  --disable-runtime-cpu-detect --enable-pic \
  --prefix=$WORKSPACE/libvpx-output
make -j$(nproc)
make install

echo "===== 9. 打包 libvpx ====="
cd "$WORKSPACE"
tar czf libvpx-android-arm64.tar.gz -C libvpx-output .

echo "===== 完成 ====="
echo "产物: $WORKSPACE/webrtc-android-arm64.tar.gz"
echo "产物: $WORKSPACE/libvpx-android-arm64.tar.gz"
```

## 参考来源

- [WebRTC 官方 Android 编译文档](https://webrtc.github.io/webrtc-org/native-code/android/)
- [WebRTC 官方开发文档](https://webrtc.github.io/webrtc-org/native-code/development/)
- [libvpx 预编译参考](https://github.com/denghe/libvpx_prebuilt)
- [libvpx Android 交叉编译参考](https://github.com/rjmangubat23/Vp9-build-for-PjSip-Android)
