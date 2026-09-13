// 根构建脚本：只声明插件版本（apply false），真正 apply 在 :app 模块内。
// 版本矩阵见 settings.gradle.kts 顶部注释（用户拍板 D2）。
//
// 注意：Kotlin 2.0 起 Compose 编译器由独立 Gradle 插件
// `org.jetbrains.kotlin.plugin.compose` 提供（不再使用 composeOptions.kotlinCompilerExtensionVersion）。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 的 Compose 编译器插件：版本必须与 Kotlin 版本一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // 信令消息序列化（doc/09 冻结协议使用 kotlinx.serialization，见 app/build.gradle.kts）
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
