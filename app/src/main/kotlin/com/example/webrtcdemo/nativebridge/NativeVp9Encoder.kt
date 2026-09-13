package com.example.webrtcdemo.nativebridge

import java.nio.ByteBuffer

// ============================================================================
// 表 A-2：`NativeVp9Encoder`（doc/14 §6.3，9 个方法，逐字冻结）
// ----------------------------------------------------------------------------
// C++ 侧 `JNI_OnLoad` 用 `RegisterNatives` 按「类名 + 方法名 + 签名」注册，
// 因此**类名、方法名、参数类型与顺序三者逐字不可改**。
// 状态码见 §6.6（= org.webrtc.VideoCodecStatus 的数值）。
//
// 可见性说明：见 NativeLog.kt 顶部「受控偏离」注释（internal 会被 Kotlin 名字修饰）。
// ============================================================================

/**
 * 自研 VP9 编码器的 JNI 门面（§6.3）。
 *
 * 句柄所有权（§6.7）：`nativeCreate` 分配、`nativeRelease` 释放，Kotlin 只能调用一次；
 * 失效句柄上的任何调用返回 [STATUS_UNINITIALIZED]，**不得崩溃**。
 */
object NativeVp9Encoder {

    // ---- 状态码（§6.6，与 org.webrtc.VideoCodecStatus.getNumber() 一致）----
    const val STATUS_OK = 0
    const val STATUS_NO_OUTPUT = 1
    const val STATUS_ERROR = -1
    const val STATUS_LEVEL_EXCEEDED = -2
    const val STATUS_MEMORY = -3
    const val STATUS_ERR_PARAMETER = -4
    const val STATUS_ERR_SIZE = -5
    const val STATUS_TIMEOUT = -6
    const val STATUS_UNINITIALIZED = -7
    const val STATUS_FALLBACK_SOFTWARE = -13

    /** 编码器实现名（§6.6 冻结；verifier 用它核对 `outbound-rtp.encoderImplementation`）。 */
    const val IMPL_NAME = "SelfVp9Libvpx"

    /** `()J` —— 返回 `Vp9Encoder*` 句柄（>0）；失败返回 0。 */
    @JvmStatic
    external fun nativeCreate(): Long

    /**
     * `(JIIIIIII)I` —— `(handle, width, height, startBitrateBps, maxBitrateBps, maxFramerate, numSpatialLayers, numTemporalLayers)`
     *
     * 宽高向上对齐到偶数；`numSpatialLayers` **只接受 1**（§5.6），其它值返回
     * [STATUS_ERR_PARAMETER]。
     */
    @JvmStatic
    external fun nativeInit(
        handle: Long,
        width: Int,
        height: Int,
        startBitrateBps: Int,
        maxBitrateBps: Int,
        maxFramerate: Int,
        numSpatialLayers: Int,
        numTemporalLayers: Int,
    ): Int

    /**
     * `(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I`
     *
     * `(handle, y, u, v, width, height, strideY, strideU, strideV, captureTimeNs, rotationDegrees, requestKeyFrame)`
     *
     * 三个 ByteBuffer 必须为 **direct**（否则返回 [STATUS_ERR_PARAMETER]）；
     * C++ 只在本次调用内读取平面并**同步拷贝**。
     */
    @JvmStatic
    external fun nativeEncode(
        handle: Long,
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        width: Int,
        height: Int,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        captureTimeNs: Long,
        rotationDegrees: Int,
        requestKeyFrame: Boolean,
    ): Int

    /**
     * `(JLjava/nio/ByteBuffer;[I)I` —— 把最近一帧编码结果拷入 `dst`。
     *
     * `outMeta` 长度 ≥6：`[0]=width,[1]=height,[2]=isKeyFrame(0/1),[3]=spatialIndex,
     * [4]=temporalIndex,[5]=qp(-1=未知)`。
     *
     * @return 拷贝字节数（>0）、无待取帧 0、出错 -1。
     */
    @JvmStatic
    external fun nativeCopyEncodedFrame(handle: Long, dst: ByteBuffer, outMeta: IntArray): Int

    /** `(J)I` —— 待取帧字节数（无则 0），用于按需扩容 `dst`。 */
    @JvmStatic
    external fun nativeGetEncodedFrameSize(handle: Long): Int

    /**
     * `(J[IIIII)I` —— `(handle, layerBitratesBps, numSpatialLayers, numTemporalLayers, totalBitrateBps, framerateFps)`
     *
     * 数组长度必须 = `S*T`，索引 `s*T+t`；长度不符返回 [STATUS_ERR_PARAMETER]。
     */
    @JvmStatic
    external fun nativeSetRates(
        handle: Long,
        layerBitratesBps: IntArray,
        numSpatialLayers: Int,
        numTemporalLayers: Int,
        totalBitrateBps: Int,
        framerateFps: Int,
    ): Int

    /** `(J)I` —— 下一帧强制关键帧。 */
    @JvmStatic
    external fun nativeRequestKeyFrame(handle: Long): Int

    /** `(J)I` —— 释放句柄；**幂等**（重复调用仍返回 [STATUS_OK]）。 */
    @JvmStatic
    external fun nativeRelease(handle: Long): Int

    /** `()Ljava/lang/String;` —— 固定返回 `"SelfVp9Libvpx"`。 */
    @JvmStatic
    external fun nativeGetImplName(): String
}
