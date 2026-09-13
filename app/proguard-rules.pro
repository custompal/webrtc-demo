# ============================================================================
# ProGuard/R8 规则（release 构建用）
# 由 t14 创建。doc/14 §4.1 冻结了 release 构建：isMinifyEnabled = true、
# isShrinkResources = true，因此本文件在 release 下**生效**，规则缺失会直接
# 导致 JNI 注册失败或序列化崩溃。debug 构建不混淆，不受本文件影响。
# ============================================================================

# ---------------------------------------------------------------------------
# JNI 边界（doc/14 §4.1 冻结要求）：C++ 侧 JNI_OnLoad 用 FindClass + RegisterNatives
# 按“类名 + 方法名 + 签名”查找，任何重命名/裁剪都会导致注册失败或回调丢失。
#   类名冻结（doc/14 §6.1）：nativebridge/NativeLog、NativeVp9Encoder、
#                            NativeNatDetector、NativeCallbacks
# ---------------------------------------------------------------------------
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class com.example.webrtcdemo.nativebridge.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 兼容：若 t8 期间仍有类位于 webrtc/ 包下，一并保留
-keep class com.example.webrtcdemo.webrtc.** { *; }

# ---------------------------------------------------------------------------
# kotlinx.serialization：保留 @Serializable 生成的 $$serializer 与 Companion
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.webrtcdemo.**$$serializer { *; }
-keepclassmembers class com.example.webrtcdemo.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.webrtcdemo.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# OkHttp / 可选 TLS 提供方（okhttp 4.x 会探测这些可选依赖，缺失时只需 dontwarn）
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# 崩溃栈可定位：保留源文件名与行号
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
