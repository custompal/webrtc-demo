// 顶层 Gradle 设置：声明插件仓库、依赖仓库与参与构建的模块。
// 版本矩阵（doc/14 §3.1 冻结，取代 doc/10 §1 的 Kotlin 1.9.22 / AGP 8.3）：
//   JDK 17 + Gradle 8.7 + AGP 8.5.2 + Kotlin 2.0.21 + Compose BOM 2024.10.01
// AGP 8.5.x 要求 Gradle >= 8.7，故 wrapper 固定 8.7（见 gradle/wrapper/gradle-wrapper.properties）。

pluginManagement {
    repositories {
        // Android 官方构件（AGP / androidx）必须先于 mavenCentral 查询
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 只允许在此集中声明仓库，禁止模块内再声明 repositories
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 冻结（doc/14 §4.1）：libwebrtc Java SDK 位于仓库内 third_party/libwebrtc/java/
        // 目录当前尚不存在（t5 未产出），flatDir 指向不存在的目录不会导致 sync 失败。
        flatDir {
            dirs("third_party/libwebrtc/java")
        }
    }
}

rootProject.name = "webrtcdemo"

// 单模块：Android app（Kotlin/Compose 上层 + app/src/main/cpp 下的 JNI native 层）
include(":app")
