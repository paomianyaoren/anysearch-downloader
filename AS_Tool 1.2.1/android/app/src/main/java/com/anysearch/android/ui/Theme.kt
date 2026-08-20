package com.anysearch.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 夸克式配色：纯白背景 + 中性灰容器 + 夸克蓝主色（去掉 M3 默认的白里透粉/紫）；
// 深色模式使用同一套中性灰语言，不再落回 M3 紫系基准色。

private val QuarkBlue = Color(0xFF2E7CF6)

// ============ 亮色 ============
private val LightColors = lightColorScheme(
    primary = QuarkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F1FF),
    onPrimaryContainer = Color(0xFF1B4F9C),
    secondary = Color(0xFF34B76D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F7EC),
    onSecondaryContainer = Color(0xFF1B7A46),
    background = Color.White,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFF666666),
    surfaceTint = Color.White,
    outline = Color(0xFFE2E4E8),
    outlineVariant = Color(0xFFE2E4E8),
    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E3),
    onErrorContainer = Color(0xFF7A1518),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF2F3F5),
    surfaceContainerHighest = Color(0xFFF2F3F5),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE8E9EB),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFF2F3F5),
    inversePrimary = Color(0xFF8AB4F8),
    scrim = Color.Black,
)

// ============ 深色 ============
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A2A5E),
    primaryContainer = Color(0xFF1B3A6B),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF81C995),
    onSecondary = Color(0xFF0A3A21),
    secondaryContainer = Color(0xFF1E4A31),
    onSecondaryContainer = Color(0xFFC8F0D5),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE3E3E5),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE3E3E5),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFB9B9BE),
    surfaceTint = Color(0xFF121212),
    outline = Color(0xFF3D3D42),
    outlineVariant = Color(0xFF3D3D42),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A08),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF1A1A1E),
    surfaceContainer = Color(0xFF1E1E22),
    surfaceContainerHigh = Color(0xFF242428),
    surfaceContainerHighest = Color(0xFF2A2A2E),
    surfaceBright = Color(0xFF3A3A3E),
    surfaceDim = Color(0xFF0E0E10),
    inverseSurface = Color(0xFFE3E3E5),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = QuarkBlue,
    scrim = Color.Black,
)

@Composable
fun AnySearchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
