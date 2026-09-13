package com.example.webrtcdemo.nativebridge

import com.example.webrtcdemo.log.AppLog

// ============================================================================
// native 库加载（doc/14 §2.1 / §6.1）
// 库名冻结：libwebrtcdemo_native.so → System.loadLibrary("webrtcdemo_native")
// 本文件不引用 org.webrtc，可在 native 产物就绪前独立编译。
// ============================================================================

/**
 * 自有 native 库加载器（`webrtcdemo_native`）。
 *
 * 加载失败**不抛异常**：日志降级为「仅 logcat + 一条 ERROR」，避免在 native 产物
 * 未就绪时直接崩溃（§9.4「未初始化时 native 侧仅落 logcat 并打一次 ERROR，不得崩溃」）。
 */
object NativeLoader {

    /** 冻结库名（§6.1），对应 `libwebrtcdemo_native.so` */
    const val LIBRARY_NAME = "webrtcdemo_native"

    private const val TAG = "main"

    @Volatile
    private var state: State = State.NOT_TRIED

    /** 加载状态 */
    enum class State { NOT_TRIED, LOADED, FAILED }

    /**
     * 幂等加载。首次调用失败后记录状态，后续调用直接返回 false，不重复打日志。
     *
     * @return 是否可用。
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        when (state) {
            State.LOADED -> return true
            State.FAILED -> return false
            State.NOT_TRIED -> Unit
        }
        return try {
            System.loadLibrary(LIBRARY_NAME)
            state = State.LOADED
            AppLog.i(TAG, "native_lib_loaded", mapOf("lib" to LIBRARY_NAME))
            true
        } catch (t: UnsatisfiedLinkError) {
            state = State.FAILED
            AppLog.e(TAG, "native_lib_load_failed", mapOf("lib" to LIBRARY_NAME), t)
            false
        }
    }

    /** 当前是否已加载（供 UI/诊断页显示）。 */
    fun isLoaded(): Boolean = state == State.LOADED
}
