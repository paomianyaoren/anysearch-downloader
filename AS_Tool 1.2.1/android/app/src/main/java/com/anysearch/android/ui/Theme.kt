package com.anysearch.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 夸克式亮色：纯白背景 + 中性灰容器 + 夸克蓝主色（去掉 M3 默认的白里透粉/紫）
private val QuarkWhite = Color(0xFFFFFFFF)
private val QuarkGray = Color(0xFFF2F3F5)
private val QuarkText = Color(0xFF1A1A1A)
private val QuarkSubText = Color(0xFF666666)
private val QuarkLine = Color(0xFFE2E4E8)
private val QuarkBlue = Color(0xFF2E7CF6)

private val LightColors = lightColorScheme(
    primary = QuarkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F1FF),
    onPrimaryContainer = Color(0xFF1B4F9C),
    secondary = Color(0xFF34B76D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F7EC),
    onSecondaryContainer = Color(0xFF1B7A46),
    background = QuarkWhite,
    onBackground = QuarkText,
    surface = QuarkWhite,
    onSurface = QuarkText,
    surfaceVariant = QuarkGray,
    onSurfaceVariant = QuarkSubText,
    surfaceTint = QuarkWhite,
    outline = QuarkLine,
    outlineVariant = QuarkLine,
    error = Color(0xFFE5484D),
    onError = Color.White,
    surfaceContainerLowest = QuarkWhite,
    surfaceContainerLow = QuarkWhite,
    surfaceContainer = QuarkWhite,
    surfaceContainerHigh = QuarkGray,
    surfaceContainerHighest = QuarkGray,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFF81C995),
)

@Composable
fun AnySearchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
