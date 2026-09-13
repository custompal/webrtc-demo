package com.example.webrtcdemo.nativebridge

// ============================================================================
// 表 A-3：`NativeNatDetector`（doc/14 §6.4，2 个方法，逐字冻结）
// C++ 侧自带探测线程；结果经表 B-1（NativeCallbacks.onNatTypeDetected）回调。
// ============================================================================

/**
 * RFC5780 NAT 探测 JNI 门面（§6.4）。
 */
object NativeNatDetector {

    /**
     * `(Ljava/lang/String;IJ)V` —— `(stunHost, stunPort, timeoutMs)`。
     *
     * **立即返回**（探测在 C++ 自有线程进行）；结果经
     * [NativeCallbacks.onNatTypeDetected] 回调；重复调用先取消失败的上一次。
     *
     * @param stunHost STUN 服务器主机（来自 `created`/`joined` 下发的 `stunUrl`）。
     * @param stunPort STUN 端口，通常 3478。
     * @param timeoutMs 单次探测超时毫秒。
     */
    @JvmStatic
    external fun nativeDetect(stunHost: String, stunPort: Int, timeoutMs: Long)

    /** `()V` —— 取消并 join 探测线程（幂等）。 */
    @JvmStatic
    external fun nativeCancel()
}
