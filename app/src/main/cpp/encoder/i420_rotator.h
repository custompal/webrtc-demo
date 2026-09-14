// ============================================================================
// encoder/i420_rotator.h —— I420 像素旋转（t46；补齐 doc/14:518/:635 的旋转语义）
// ----------------------------------------------------------------------------
// 为什么需要（现状与契约的差距）：
//   doc/14:518 要求「`g_w`/`g_h`：InitEncode 传入（对齐到偶数；**rotation 90/270 时交换**）」，
//   doc/14:635 规定 rotation 取值 `0|90|180|270`（非法按 0 处理并记 WARN）。
//   但本工程编码器此前**只做校验 + WARN**（原 `vp9_encoder.cpp:419-426`），既不交换尺寸、
//   也不旋转像素；而 VP9 码流**不携带 CVO/rotation 元数据**，远端解码器无从校正朝向
//   （t39 只修了本地预览侧：`FrameNormalizer` 保留 `frame.rotation`）。
//   ⇒ 仅交换尺寸而不旋转像素会得到**错乱图像**，因此必须"像素旋转 + 尺寸交换"一起做。
//
// 语义（与 org.webrtc 渲染器一致）：
//   `VideoFrame.getRotation()` 表示"为显示为正立，需要**顺时针**旋转的角度"；
//   `VideoFrameDrawer` 用 `renderMatrix.preRotate(frame.getRotation())` 应用它。
//   因此本文件把像素按**顺时针 rotation** 角度烘进 I420（0/90/180/270）：
//     R=90 ：dst(x', y') = src(x = y', y = h-1-x')，输出尺寸 = (h, w)
//     R=180：dst(x', y') = src(w-1-x', h-1-y')，输出尺寸 = (w, h)
//     R=270：dst(x', y') = src(x = w-1-y', y = x')，输出尺寸 = (h, w)
//   色度按 2x2 下采样对齐（(x/2, y/2) → (x'/2, y'/2)）。
//
// 设计约束：
//   1. **header-only**（不改 CMakeLists、不新增编译单元，降低集成风险）；
//   2. 不依赖 Android / libvpx / STL 容器 ⇒ 可在宿主用 NDK clang 以 freestanding 方式
//      离线编译执行（见 `encoder/i420_rotator_host_test.cpp`）；
//   3. 不抛异常（-fno-exceptions）、不分配内存（缓冲区由调用方提供）。
// ============================================================================
#pragma once

#if defined(I420_ROTATOR_FREESTANDING)
// 离线宿主自测模式：不引入任何标准库头（容器内无 libc 头文件）
using uint8_t = unsigned char;
using size_t = unsigned long;
#else
#include <cstddef>
#include <cstdint>
#endif

namespace webrtcdemo {

/** 一条 I420 平面（只读视图）。 */
struct I420PlaneView {
  const uint8_t* data = nullptr;  // 首个像素
  int stride = 0;                 // 字节步长（>= 该平面有效宽度）
};

/** 一条 I420 平面（可写视图）。 */
struct I420MutPlaneView {
  uint8_t* data = nullptr;
  int stride = 0;
};

/** I420 帧只读视图（Y/U/V 三平面 + 有效宽高）。 */
struct I420View {
  I420PlaneView y{};
  I420PlaneView u{};
  I420PlaneView v{};
  int width = 0;   // Y 平面有效宽度（偶数）
  int height = 0;  // Y 平面有效高度（偶数）
};

/** I420 帧可写视图。 */
struct I420MutView {
  I420MutPlaneView y{};
  I420MutPlaneView u{};
  I420MutPlaneView v{};
};

/**
 * 规范化 rotation（契约 doc/14:635）：只接受 0/90/180/270，其它值按 0 处理。
 *
 * 调用方（`vp9_encoder.cpp`）对"原值非 0 却被规范化"的情形记一次 WARN。
 */
inline int NormalizeRotationDegrees(int degrees) {
  if (degrees == 90 || degrees == 180 || degrees == 270) {
    return degrees;
  }
  return 0;
}

/** 是否为 90/270 的四分之一转（需要交换宽高）。 */
inline bool IsQuarterTurn(int rotation) {
  return rotation == 90 || rotation == 270;
}

/** 旋转后的宽度：90/270 时交换。 */
inline int RotatedWidth(int width, int height, int rotation) {
  return IsQuarterTurn(rotation) ? height : width;
}

/** 旋转后的高度：90/270 时交换。 */
inline int RotatedHeight(int width, int height, int rotation) {
  return IsQuarterTurn(rotation) ? width : height;
}

/** 旋转后 Y 平面所需字节数（无额外 padding，stride = 旋转后宽度）。 */
inline size_t RotatedYSize(int width, int height, int rotation) {
  const int w = RotatedWidth(width, height, rotation);
  const int h = RotatedHeight(width, height, rotation);
  return static_cast<size_t>(w) * static_cast<size_t>(h);
}

/** 旋转后单个色度平面所需字节数。 */
inline size_t RotatedUvSize(int width, int height, int rotation) {
  const int w = RotatedWidth(width, height, rotation) / 2;
  const int h = RotatedHeight(width, height, rotation) / 2;
  return static_cast<size_t>(w) * static_cast<size_t>(h);
}

/** 旋转后整帧所需字节数（Y + U + V，三平面紧密排列在调用方缓冲区）。 */
inline size_t RotatedTotalSize(int width, int height, int rotation) {
  return RotatedYSize(width, height, rotation) + 2 * RotatedUvSize(width, height, rotation);
}

// ----------------------------------------------------------------------------
// 内部：按 (src_x, src_y) → (dst_x, dst_y) 的映射逐像素搬运一个平面。
// 说明：写成函数模板以外的小工具，避免任何标准库依赖。
// ----------------------------------------------------------------------------
inline void RotatePlane(const I420PlaneView& src, I420MutPlaneView dst, int rotated_width,
                        int rotated_height, int rotation) {
  for (int y = 0; y < rotated_height; ++y) {
    uint8_t* dst_row = dst.data + static_cast<size_t>(y) * static_cast<size_t>(dst.stride);
    for (int x = 0; x < rotated_width; ++x) {
      int src_x = 0;
      int src_y = 0;
      if (rotation == 90) {
        // 顺时针 90°：src_x = y, src_y = h_src - 1 - x（h_src = rotated_width）
        src_x = y;
        src_y = rotated_width - 1 - x;
      } else if (rotation == 180) {
        src_x = rotated_width - 1 - x;
        src_y = rotated_height - 1 - y;
      } else if (rotation == 270) {
        // 顺时针 270°（= 逆时针 90°）：src_x = w_src - 1 - y, src_y = x（w_src = rotated_height）
        src_x = rotated_height - 1 - y;
        src_y = x;
      } else {
        // rotation == 0（不旋转）
        src_x = x;
        src_y = y;
      }
      dst_row[x] = src.data[static_cast<size_t>(src_y) * static_cast<size_t>(src.stride) +
                            static_cast<size_t>(src_x)];
    }
  }
}

/**
 * 把 [src] 按顺时针 [rotation]（已规范化：0/90/180/270）旋转写入 [dst]。
 *
 * @param src       源帧（Y/U/V 三平面；宽高必须为偶数）。
 * @param rotation  已由 [NormalizeRotationDegrees] 规范化。
 * @param dst       目标帧；其 stride 由调用方指定（本工程用"旋转后宽度"紧密排列）。
 *
 * 前置条件（由调用方保证）：`dst` 缓冲区 ≥ [RotatedTotalSize]；
 * `dst.u` 紧随 Y 之后、`dst.v` 紧随 U 之后（若 stride = 旋转后宽度）。
 * 本函数**不分配内存、不抛异常**；参数非法时直接返回（调用方已做前置校验）。
 */
inline void RotateI420(const I420View& src, int rotation, const I420MutView& dst) {
  if (src.width <= 0 || src.height <= 0 || src.y.data == nullptr || src.u.data == nullptr ||
      src.v.data == nullptr || dst.y.data == nullptr || dst.u.data == nullptr ||
      dst.v.data == nullptr) {
    return;
  }
  const int rot_w = RotatedWidth(src.width, src.height, rotation);
  const int rot_h = RotatedHeight(src.width, src.height, rotation);
  RotatePlane(src.y, dst.y, rot_w, rot_h, rotation);
  RotatePlane(src.u, dst.u, rot_w / 2, rot_h / 2, rotation);
  RotatePlane(src.v, dst.v, rot_w / 2, rot_h / 2, rotation);
}

}  // namespace webrtcdemo
