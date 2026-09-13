package com.example.webrtcdemo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// ============================================================================
// Material3 主题。跟随系统深色模式，配色常量见 Color.kt。
// 用法（MainActivity / t8 的界面）：
//     WebRtcDemoTheme { ... }
// ============================================================================

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = BluePrimaryContainer,
    error = StatusError,
)

private val DarkColors = darkColorScheme(
    primary = BluePrimaryContainer,
    onPrimary = BlueOnPrimaryContainer,
    primaryContainer = BluePrimary,
    onPrimaryContainer = BlueOnPrimary,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    error = StatusError,
)

/**
 * 应用主题。
 *
 * @param darkTheme 是否使用深色配色，默认跟随系统（[isSystemInDarkTheme]）。
 * @param content 主题作用域内的 Compose 内容。
 */
@Composable
fun WebRtcDemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
