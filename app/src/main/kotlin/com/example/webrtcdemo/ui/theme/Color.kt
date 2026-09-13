package com.example.webrtcdemo.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Compose 配色（Material3）。
// 说明：这里是纯 UI 常量，不依赖 JNI 契约与信令契约，故由 t14 先行落地；
//       t8 若按 doc/14 需要调整，只改本文件即可。
// ============================================================================

// --- 主色（蓝，与自适应图标底色一致）---
val BluePrimary = Color(0xFF0B57D0)
val BlueOnPrimary = Color(0xFFFFFFFF)
val BluePrimaryContainer = Color(0xFFD8E2FF)
val BlueOnPrimaryContainer = Color(0xFF001A41)

// --- 次级/强调色 ---
val TealSecondary = Color(0xFF00696E)
val TealOnSecondary = Color(0xFFFFFFFF)


// --- 通话页背景（视频区域，深色更利于观察画面）---
val CallBackgroundLight = Color(0xFF101418)

// --- 通用中性色 ---
val SurfaceLight = Color(0xFFFDFBFF)
val OnSurfaceLight = Color(0xFF1A1C1E)
val SurfaceDark = Color(0xFF1A1C1E)
val OnSurfaceDark = Color(0xFFE3E2E6)

// --- 状态/错误色 ---
/**
 * 状态与错误提示色（`colorScheme.error`）。
 *
 * 说明：`Theme.kt` 的浅色与深色两套配色**共用本符号**，故取 M3 浅色 `error`（`0xFFB3261E`）
 * 与深色 `onError`（`0xFFF2B8B5`）之间的**折中红** —— 在浅底（首页）与深底（通话页黑底状态面板）
 * 上都保持可读。若日后要分流，拆成 Light/Dark 两个 token 即可。
 */
val StatusError = Color(0xFFE5484D)
