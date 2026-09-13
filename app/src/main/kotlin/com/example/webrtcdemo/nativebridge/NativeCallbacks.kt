package com.example.webrtcdemo.nativebridge

import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.nat.NatTypeRepository

// ============================================================================
// 表 B-1：`NativeCallbacks`（doc/14 §6.5，C++ → Kotlin，2 个方法，逐字冻结）
// ----------------------------------------------------------------------------
// 这两个方法是 **C++ 调 Java**：`JNI_OnLoad` 里 `FindClass + GetStaticMethodID` 缓存，
// 因此**类名、包名、方法名、签名四者逐字不可改**，且必须 `@JvmStatic`
// （GetStaticMethodID 要求静态）+ ProGuard 保留（`nativebridge.**`）。
//
// 线程模型（§6.5）：由 native 探测线程调用（调用前 AttachCurrentThread）。
// 因此本层**不直接碰 UI**，只更新线程安全的 StateFlow（Compose 在主线程观察），
// 并立刻把日志交给 log/Log.kt 落一行（§6.5 要求）。
//
// 已取消的旧回调面（§6.5）：onIceCandidate / onIceConnectionChange /
// onCandidatePairChanged / onStatsReport / onBitrateChanged / onRemoteVideoReady
// 一律**不再跨 JNI**，改由 Kotlin 侧 org.webrtc 直接获得（§7.4）。
// ============================================================================

/**
 * C++ → Kotlin 回调入口（§6.5）。**禁止**在本类内新增方法（契约冻结 2 个）。
 */
object NativeCallbacks {

    private const val TAG_NAT = "nat"

    /**
     * `(Ljava/lang/String;Ljava/lang/String;)V` —— NAT 探测结果。
     *
     * @param natType §6.6 枚举字符串（`Open`/`FullCone`/`RestrictedCone`/`PortRestrictedCone`/`Symmetric`/`Unknown`）。
     * @param detail 证据串（≤512 字节 ASCII，如 `mapped=1.2.3.4:54321;testI=ok;...`）。
     */
    @JvmStatic
    fun onNatTypeDetected(natType: String, detail: String) {
        AppLog.i(TAG_NAT, "nat_done", mapOf("nat" to natType, "detail" to detail))
        NatTypeRepository.onNativeResult(natType, detail)
    }

    /**
     * `(ILjava/lang/String;Ljava/lang/String;)V` —— 低频事件日志。
     *
     * **仅低频事件**（`nat_start` / `nat_done` / `encoder_libvpx_init` / `encoder_fallback`），
     * 禁止逐帧调用（§6.5）。
     *
     * @param level 级别 0..5（§6.6），与 [com.example.webrtcdemo.log.LogLevel.code] 一致。
     * @param tag 模块 tag（§9.1 白名单）。
     * @param message 单行消息。
     */
    @JvmStatic
    fun onLogEvent(level: Int, tag: String, message: String) {
        AppLog.fromNative(level, tag, message)
    }
}
