package com.example.webrtcdemo.model

// ============================================================================
// NAT 类型（doc/14 §6.6 / §8.2：字符串必须与 doc/09 §3.7 枚举完全一致）
// ============================================================================

/**
 * NAT 类型枚举。
 *
 * @property wire 与信令 `natType` 字段及 native 回调**逐字一致**的字符串（§6.6）。
 * @property label 中文展示名。
 */
enum class NatType(val wire: String, val label: String) {
    OPEN("Open", "开放（无 NAT）"),
    FULL_CONE("FullCone", "Full-Cone"),
    RESTRICTED_CONE("RestrictedCone", "Restricted Cone"),
    PORT_RESTRICTED_CONE("PortRestrictedCone", "Port Restricted Cone"),
    SYMMETRIC("Symmetric", "Symmetric NAT"),
    UNKNOWN("Unknown", "未知"),
    ;

    companion object {
        /**
         * 从线上字符串解析（大小写不敏感）。
         *
         * @param wire 信令/native 传入的枚举字符串，可为 null。
         * @return 匹配项；无法识别返回 [UNKNOWN]（不抛异常）。
         */
        fun fromWire(wire: String?): NatType =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: UNKNOWN    }
}
