package com.example.webrtcdemo.config

import android.content.Context
import android.content.SharedPreferences
import com.example.webrtcdemo.BuildConfig
import com.example.webrtcdemo.log.AppLog

// ============================================================================
// 应用配置（doc/14 §2.1 / §8.1 / §9.6 / §7.1）
// ----------------------------------------------------------------------------
// 职责：
//   1. 信令 URL：默认取 BuildConfig.SIGNALING_URL（§8.1 冻结 ws://47.238.144.66:8443/ws），
//      诊断页可覆盖并持久化（便于 t12/verifier 联调，§8.1「运行时覆盖」）；
//   2. 日志级别：与 log/Log.kt 的 log_cfg 共用（§9.6）；
//   3. ICE 策略：ALL / RELAY（诊断页开关，§7.4）；
//   4. 编码器对照开关 USE_DEFAULT_ENCODER（§7.1 允许的临时对照实验，必须在报告登记）。
// ============================================================================

/**
 * 应用配置读写（SharedPreferences 封装）。
 */
object AppConfig {

    /** 配置文件（与 §9.6 的日志配置分开，避免互踩）。 */
    const val PREFS_NAME = "app_cfg"

    /**
     * 信令路径（§8.1 冻结：`/ws`；doc/05 §7 与 ADR-006 的 `/signal` 作废）。
     *
     * **这是承重常量，不是装饰**：被 [normalizeSignalingUrl] 用于
     * ① 校验运行时覆盖 URL 的路径是否为冻结值；② 对缺少路径的覆盖值补齐。
     * 删除它会让诊断页的 URL 覆盖校验失效（并连带使契约 §12 V33 的 app 侧断言失去载体）。
     */
    const val SIGNALING_PATH = "/ws"

    private const val TAG = "signaling"

    /** 信令 URL 覆盖键（§8.1）。 */
    const val KEY_SIGNALING_URL = "signaling_url"

    /** ICE 策略键（`ALL` / `RELAY`，§7.4）。 */
    const val KEY_ICE_POLICY = "ice_policy"

    /** 默认编码器对照开关（§7.1，仅用于 doc/03 阶段 3 对照实验）。 */
    const val KEY_USE_DEFAULT_ENCODER = "use_default_encoder"

    /** 弱设备降级开关（§7.2/R4：480x360@24）。 */
    const val KEY_USE_LOW_RES = "use_low_resolution"

    /** 编码实现三态覆盖（t87：`AUTO`（默认）/`SELF`/`DEFAULT`）。 */
    const val KEY_ENCODER_OVERRIDE = "encoder_override"

    /** 自动降级兜底开关（t87：默认 **开启**）。 */
    const val KEY_ENCODER_FALLBACK = "encoder_fallback_enabled"

    /** 编码实现三态：自动（默认）。 */
    const val ENCODER_OVERRIDE_AUTO = "AUTO"

    /** 编码实现三态：强制自研。 */
    const val ENCODER_OVERRIDE_SELF = "SELF"

    /** 编码实现三态：强制默认（硬件优先）。 */
    const val ENCODER_OVERRIDE_DEFAULT = "DEFAULT"

    /** ICE 策略取值：全部候选。 */
    const val ICE_POLICY_ALL = "ALL"

    /** ICE 策略取值：强制中继（复现 RELAY 路径）。 */
    const val ICE_POLICY_RELAY = "RELAY"

    /**
     * 读取信令 URL（§8.1）：SharedPreferences 覆盖值优先，否则用 BuildConfig 冻结默认值。
     *
     * 覆盖值会被 [normalizeSignalingUrl] 校验：**不合法则拒绝并回退默认 URL**，同时打一条 Warn。
     * 这样「诊断页填错地址」不会把整个 Demo 连到不可用端点。
     *
     * @param context 任意 Context。
     */
    fun signalingUrl(context: Context): String {
        val override = prefs(context).getString(KEY_SIGNALING_URL, null)
        if (override.isNullOrBlank()) return BuildConfig.SIGNALING_URL
        val normalized = normalizeSignalingUrl(override)
        if (normalized == null) {
            AppLog.w(
                TAG,
                "signaling_url_rejected",
                mapOf("override" to override, "expected_path" to SIGNALING_PATH),
            )
            return BuildConfig.SIGNALING_URL
        }
        return normalized
    }

    /**
     * 规范化并校验信令 URL（§8.1：端点路径冻结为 [SIGNALING_PATH]）。
     *
     * 规则：
     *   1. 仅接受 `ws://` / `wss://`；
     *   2. 必须有主机名；缺端口按 scheme 补默认端口（ws→80，wss→443）；
     *   3. 路径为 `/` 或空 → **补齐**为 [SIGNALING_PATH]（方便手填 `ws://host:8443`）；
     *   4. 路径为其它值（含 `/signal`、`/foo/ws`）→ 返回 null（调用方拒绝该覆盖并回退默认）。
     *
     * @param raw 用户输入或持久化的覆盖值。
     * @return 规范化 URL；非法时返回 null（**不抛异常**）。
     */
    fun normalizeSignalingUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val scheme = when {
            trimmed.startsWith("ws://", ignoreCase = true) -> "ws"
            trimmed.startsWith("wss://", ignoreCase = true) -> "wss"
            else -> return null
        }
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val path = uri.path.orEmpty()
        if (path.isNotEmpty() && path != "/" && path != SIGNALING_PATH) return null
        val port = if (uri.port > 0) uri.port else if (scheme == "wss") 443 else 80
        return "$scheme://$host:$port$SIGNALING_PATH"
    }

    /**
     * 写入信令 URL 覆盖值（§8.1：诊断页可改并持久化）。
     *
     * @param url 传空串表示**恢复默认**；非空值需通过 [normalizeSignalingUrl] 校验。
     * @return 是否被接受（false = 非法，未写入，调用方应给出提示）。
     */
    fun setSignalingUrl(context: Context, url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            prefs(context).edit().remove(KEY_SIGNALING_URL).apply()
            return true
        }
        val normalized = normalizeSignalingUrl(trimmed) ?: return false
        prefs(context).edit().putString(KEY_SIGNALING_URL, normalized).apply()
        return true
    }

    /** 当前 ICE 策略（`ALL`/`RELAY`）。 */
    fun icePolicy(context: Context): String =
        prefs(context).getString(KEY_ICE_POLICY, ICE_POLICY_ALL) ?: ICE_POLICY_ALL

    /** 写入 ICE 策略。 */
    fun setIcePolicy(context: Context, policy: String) {
        prefs(context).edit().putString(KEY_ICE_POLICY, policy).apply()
    }

    /**
     * 是否使用弱设备降级采集参数（§7.2/R4：480x360@24）。
     *
     * 生效时机：**下次启动采集时**（通话开始时由 MediaCapture 读取），不做运行中热切换。
     */
    fun useLowResolution(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_LOW_RES, false)

    /** 写入弱设备降级开关。 */
    fun setUseLowResolution(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_LOW_RES, enabled).apply()
    }

    /** 是否强制中继（§7.4 诊断开关）。 */
    fun forceRelay(context: Context): Boolean = icePolicy(context) == ICE_POLICY_RELAY

    /** 是否使用默认（硬件）编码器做对照实验（§7.1，默认关闭）。 */
    fun useDefaultEncoder(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_DEFAULT_ENCODER, false)

    /** 写入默认编码器开关。 */
    fun setUseDefaultEncoder(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_DEFAULT_ENCODER, enabled).apply()
    }

    /**
     * 编码实现三态覆盖（t87 验收第 3 条）。
     *
     * 取值口径与 `EncoderOverrideMode.wire` 一致（`AUTO`/`SELF`/`DEFAULT`）；
     * 本方法只做**字符串**读写，枚举映射交给 `EncoderOverrideMode.fromWire`，
     * 保持 `config` 包不依赖 `encoder` 包（避免配置层被业务类型侵入）。
     *
     * @param context 任意 Context。
     * @return 持久化值；未设置时 [ENCODER_OVERRIDE_AUTO]。
     */
    fun encoderOverride(context: Context): String =
        prefs(context).getString(KEY_ENCODER_OVERRIDE, ENCODER_OVERRIDE_AUTO) ?: ENCODER_OVERRIDE_AUTO

    /**
     * 写入编码实现三态覆盖。
     *
     * 生效时机：AUTO 下的降级判定实时生效；`SELF`/`DEFAULT` 在**下一次创建编码器**时生效
     * （通话中切换由 selector 通道完成，见 `FallbackVideoEncoderSelector`）。
     */
    fun setEncoderOverride(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_ENCODER_OVERRIDE, mode).apply()
    }

    /**
     * 自动降级兜底开关（t87：默认 **开启**）。
     *
     * @param context 任意 Context。
     * @return 是否开启自动兜底。
     */
    fun encoderFallbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENCODER_FALLBACK, true)

    /** 写入自动降级兜底开关。 */
    fun setEncoderFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENCODER_FALLBACK, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
