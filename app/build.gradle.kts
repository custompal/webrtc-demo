// ============================================================================
// :app 模块构建脚本
// ----------------------------------------------------------------------------
// 文档依据：doc/14-interface-contract.md（唯一权威）§2（目录）、§3.1（版本冻结）、
//           §4.1（Gradle 构建契约）、§6（JNI 类名冻结）、§7.6（Manifest 冻结项）、§8.1（信令端点）。
// 冻结版本矩阵：JDK 17 + Gradle 8.7 + AGP 8.5.2 + Kotlin 2.0.21 + Compose BOM 2024.10.01
//               + NDK 26.1.10909125 + CMake 3.22.1 + 仅 arm64-v8a + c++_shared。
// 目录约定（D3）：Kotlin 源码根 src/main/kotlin/，包名 com.example.webrtcdemo，
//                 native 源码根 src/main/cpp/（t7 负责，本文件只引用其 CMakeLists）。
//
// 附加开关（不改变默认可复现行为，仅供骨架阶段排障；详见 reports/08-android-dev.md §8）：
//   -PwebrtcDemo.skipNative=true   跳过 externalNativeBuild：当 t7 的
//                                  src/main/cpp/CMakeLists.txt 尚未就绪时，
//                                  仍可单独验证 Kotlin/Compose 层能否编译。
//   ⚠️ 属性名**大小写敏感**：Gradle 把 `-P` 之后的部分作为属性名**原样**注册，
//      而本脚本读取的是 `findProperty("webrtcDemo.skipNative")`（**小写 w**）。
//      写成 `-PwebrtcDemo.skipNative` 不会命中，而且**不报错**（静默退化为默认 false，
//      于是 externalNativeBuild 照常执行）——这正是 t10 早失败阶段最容易踩的坑。
// ============================================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 必需：Compose 编译器由独立插件提供，版本 = Kotlin 版本（doc/14 §3.1）
    id("org.jetbrains.kotlin.plugin.compose")
    // doc/14 §8.2 的信令消息使用 kotlinx.serialization
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 构建开关：属性名必须与文件头注释一致（**小写 w**）。
// 声明放在 `plugins {}` 之后：Gradle 要求 plugins 块位于脚本最前，val 声明前置有风险。
val webrtcDemoSkipNative: Boolean =
    (findProperty("webrtcDemo.skipNative") as String?)?.toBoolean() ?: false

android {
    namespace = "com.example.webrtcdemo"          // D3
    compileSdk = 34
    ndkVersion = "26.1.10909125"                  // 冻结（doc/14 §3.1/§4.1，r26b）

    defaultConfig {
        applicationId = "com.example.webrtcdemo"  // D3
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 仅保留 arm64-v8a（libwebrtc/libvpx 产物为 android arm64，doc/14 §3.1）
        ndk {
            abiFilters += "arm64-v8a"
        }

        // CMake 参数仅在未跳过 native 构建时下发：
        // 跳过时本文件不残留**任何** externalNativeBuild 配置（连参数也不注册），
        // 使 `-PwebrtcDemo.skipNative=true` 能完整跳过 CMake 配置阶段。
        if (!webrtcDemoSkipNative) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",                 // 自有 .so 与 libvpx 必须同 STL
                        "-DANDROID_PLATFORM=android-26",
                        // 冻结（doc/14 §4.2）：third_party 绝对路径由 Gradle 注入，CMake 不再猜相对层级
                        "-DWEBC_THIRD_PARTY=${rootProject.projectDir}/third_party"
                    )
                }
            }
        }

        // 冻结（doc/14 §8.1）：信令端点；运行时可在诊断页用 SharedPreferences 覆盖
        buildConfigField("String", "SIGNALING_URL", "\"ws://47.238.144.66:8443/ws\"")
        // 冻结（doc/14 §4.1）：摄像头朝向默认值
        buildConfigField("String", "LIBCAMERA_FACING", "\"front\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.SIGNALING_URL / LOG_DEFAULT_DEBUG 等
    }

    // native 层：由 t7 提供 app/src/main/cpp/CMakeLists.txt（doc/14 §4.2）
    if (!webrtcDemoSkipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"   // 冻结（doc/14 §3.1）
            }
        }
    }

    packaging {
        jniLibs {
            // 冻结（doc/14 §4.1）
            useLegacyPackaging = false
            // 附加保险（超出 §4.1 原文）：若 AAR 也自带 libc++_shared.so，避免重复打包直接失败。
            // t10 验证 APK 后若确认无重复，可删除本行以恢复严格校验。
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            // AndroidX 常见重复 META-INF 许可文件
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 冻结（doc/14 §4.1 原文使用 kotlinOptions）
    kotlinOptions {
        jvmTarget = "17"
    }

    // Kotlin 源码根 = src/main/kotlin（D3）。同时保留 src/main/java，
    // 供 doc/14 §2 布局中“仅放 libwebrtc AAR 暴露的 org.webrtc 类”的目录使用。
    // 单元测试源集同样显式声明 src/test/kotlin（:app:testDebugUnitTest 会用到）。
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin", "src/main/java")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin", "src/test/java")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "LOG_DEFAULT_DEBUG", "true")
        }
        release {
            // 冻结（doc/14 §4.1）：release 开启混淆与资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "LOG_DEFAULT_DEBUG", "false")
        }
    }
}

dependencies {
    // ==========================================================================
    // libwebrtc 官方 Android Java SDK（org.webrtc.*）——doc/14 §3.1 / §4.1 / §4.3
    // --------------------------------------------------------------------------
    // 契约原文写作 files("../../third_party/libwebrtc/java/libwebrtc-java.jar")，
    // 但该相对路径是相对 :app 项目目录解析的（:app = <repo>/app），
    // "../../third_party" 会落到 <repo>/../third_party（错误）。
    // 正确路径为 <repo>/third_party/libwebrtc/java/libwebrtc-java.jar，
    // 这里用 rootProject.file(...) 消除歧义（已登记为契约缺陷，见 reports/14-android-skeleton.md）。
    // t5 未产出该 jar 时，此处只是空 classpath 条目，不影响 Kotlin 骨架编译。
    // ==========================================================================
    implementation(files(rootProject.file("third_party/libwebrtc/java/libwebrtc-java.jar")))

    // ======================= Compose（BOM 2024.10.01 冻结）=======================
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 附加：通话页需要 Mic / CallEnd / Cameraswitch 等扩展图标集（BOM 统一版本）
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ================= Activity / Navigation / Lifecycle（版本冻结）=================
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // ============ FileProvider（D6 日志导出必需）============
    implementation("androidx.core:core-ktx:1.13.1")

    // ============ 信令 WebSocket（doc/14 §3.1）============
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ============ 序列化（doc/14 §3.1：1.7.3，取代 doc/10 §1 的 1.6.3）============
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ====================== 协程（doc/14 §3.1）======================
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ====================== 单元测试（附加，便于 t8 自测）======================
    testImplementation("junit:junit:4.13.2")

    // 说明：doc/14 §10 裁定 C06 已废止 doc/10 §6.1 的相机库方案（D1），
    // 采集改用 org.webrtc.Camera2Capturer + SurfaceTextureHelper（§7.2），
    // 因此不引入任何 androidx.camera.* 依赖（验收项 V12 要求 0 命中）。
    // 同理不使用 Ktor / Glide / Coil（§3.1）。
}
