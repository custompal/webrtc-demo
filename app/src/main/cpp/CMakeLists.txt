# ============================================================================
# webrtcdemo_native —— 自研 C++ 层（JNI 桥 + 自研 VP9 编码器 + RFC5780 NAT 探测 + 日志）
# ----------------------------------------------------------------------------
# 权威依据：doc/14-interface-contract.md §4.2（本文件内容冻结）。
# 边界（D1/A1）：**不链接 libwebrtc / libjingle 的任何静态库或共享库**——
#   自研 .so 与官方 Java SDK 之间只有 JNI 边界（契约 §5.1 否决路线 a/b 的理由：
#   同进程两份 libwebrtc + Chromium libc++(std::__Cr) 与 NDK c++_shared 的 ABI 墙）。
#   下方注释里出现的 libwebrtc/libjingle 字样仅为说明，不是链接目标。
# 唯一链接的第三方库：libvpx（t5 产物 third_party/libvpx/lib/libvpx.a）。
# ============================================================================
cmake_minimum_required(VERSION 3.22.1)
project(webrtcdemo_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 冻结（契约 §4.2）：由 Gradle 注入 -DWEBC_THIRD_PARTY=<repo>/third_party；
# 无注入时回退相对路径 <cpp>/../../../../third_party（即仓库根 third_party）。
if(NOT DEFINED WEBC_THIRD_PARTY)
  set(WEBC_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
endif()
set(LIBVPX_PATH ${WEBC_THIRD_PARTY}/libvpx)

# ---- libvpx（t5 交叉编译产物，arm64 + 同 NDK + --enable-pic）----------------
add_library(vpx STATIC IMPORTED)
set_target_properties(vpx PROPERTIES
  IMPORTED_LOCATION ${LIBVPX_PATH}/lib/libvpx.a)
target_include_directories(vpx INTERFACE ${LIBVPX_PATH}/include)

# ---- 自研 native 库 --------------------------------------------------------
# 库名冻结：libwebrtcdemo_native.so（Kotlin: System.loadLibrary("webrtcdemo_native")）
add_library(webrtcdemo_native SHARED
  jni/jni_bridge.cpp
  jni/callback_bridge.cpp
  jni/native_log_jni.cpp
  jni/vp9_encoder_jni.cpp
  jni/nat_detector_jni.cpp
  log/native_log.cpp
  encoder/vp9_encoder.cpp
  encoder/layer_bitrate_allocator.cpp
  nat/stun_client.cpp
  nat/nat_detector.cpp
  util/jni_util.cpp)

target_include_directories(webrtcdemo_native PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${LIBVPX_PATH}/include)

# -fvisibility=hidden：只导出 JNI_OnLoad（JNIEXPORT 自带 default 可见性）
# -fno-exceptions / -fno-rtti：与 libwebrtc 风格一致（ADR-007），且可显著减小代码体积
target_compile_options(webrtcdemo_native PRIVATE
  -fvisibility=hidden
  -fno-exceptions
  -fno-rtti
  -Wall
  -Wextra
  -Wno-unused-parameter)

target_link_options(webrtcdemo_native PRIVATE
  -Wl,--gc-sections
  -Wl,--exclude-libs,ALL
  # 16 KB 页兼容（t24 / doc-16KB）：NDK r26 的 aarch64 链接器默认
  # max-page-size=0x1000(4 KB)，而 Android 15+ 的 16 KB 页设备要求
  # ELF LOAD 段 p_align=0x4000，否则 dlopen 直接失败。
  # 实测（NDK 26.1.10909125）：不加 → p_align=0x1000；加 → p_align=0x4000。
  # 注意：本选项只解决**本库**；同目录打包的 libc++_shared.so（NDK r26 预编译
  # 件）仍是 0x1000，整包达标方案见 reports/07-native-dev.md §15。
  -Wl,-z,max-page-size=16384)

# log = __android_log_print（D6 要求同时落 logcat）；android = NDK 基础库
target_link_libraries(webrtcdemo_native vpx log android)
