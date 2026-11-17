package com.example.gleannote.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 视觉基线（对齐 M3 Expressive 的 foundational 强度）：
 * 保留 PRD 既定的深蓝主色与墨绿辅助色，补齐表面层级、描边与扩展状态色，
 * 让浅色与深色两套方案共用同一组语义槽位，界面不再出现裸 Color(0x...) 常量。
 */

/* ---------- 品牌色 ---------- */
private val BrandBlue = Color(0xFF185FA5)
private val BrandBlueInverse = Color(0xFF9CC5F0)
private val BrandBlueContainerLight = Color(0xFFD6E6F7)
private val BrandBlueContainerDark = Color(0xFF1B4A78)
private val BrandGreen = Color(0xFF0F6E56)
private val BrandGreenInverse = Color(0xFF7FD3B6)
private val BrandGreenContainerLight = Color(0xFFD3EBE2)
private val BrandGreenContainerDark = Color(0xFF0E5140)

/* ---------- 浅色方案：暖白底 + 低饱和表面层级 ---------- */
private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainerLight,
    onPrimaryContainer = Color(0xFF0A2E52),
    secondary = BrandGreen,
    onSecondary = Color.White,
    secondaryContainer = BrandGreenContainerLight,
    onSecondaryContainer = Color(0xFF04352A),
    tertiary = Color(0xFF6B5B95),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E1F5),
    onTertiaryContainer = Color(0xFF2A1F45),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color.White,
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEEF0F3),
    onSurfaceVariant = Color(0xFF5F5E5A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFEDF0F4),
    surfaceContainerHighest = Color(0xFFE7EAEF),
    /* 功能性描边：与白底对比 4.21:1，满足 WCAG 非文本 3:1；原 #9AA0A6 仅 2.64:1 */
    outline = Color(0xFF767C85),
    /* 装饰性分隔线：M3 定义为 decorative，不承担识别功能，保持轻量避免视觉噪音 */
    outlineVariant = Color(0xFFDDE1E6),
    error = Color(0xFFA32D2D),
    onError = Color.White,
    errorContainer = Color(0xFFFBE4E4),
    onErrorContainer = Color(0xFF5C1414),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2C3138),
    inverseOnSurface = Color(0xFFF2F4F7),
    inversePrimary = BrandBlueInverse
)

/* ---------- 深色方案：深灰蓝底，主色提亮以保证 AA 对比 ---------- */
private val DarkColors = darkColorScheme(
    primary = BrandBlueInverse,
    onPrimary = Color(0xFF04223D),
    primaryContainer = BrandBlueContainerDark,
    onPrimaryContainer = BrandBlueContainerLight,
    secondary = BrandGreenInverse,
    onSecondary = Color(0xFF03301F),
    secondaryContainer = BrandGreenContainerDark,
    onSecondaryContainer = BrandGreenContainerLight,
    tertiary = Color(0xFFC5B8E8),
    onTertiary = Color(0xFF2A1F45),
    tertiaryContainer = Color(0xFF423561),
    onTertiaryContainer = Color(0xFFE8E1F5),
    background = Color(0xFF12161C),
    onBackground = Color(0xFFE6E9EE),
    surface = Color(0xFF1A1F26),
    onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF262C34),
    onSurfaceVariant = Color(0xFFB6BCC5),
    surfaceContainerLowest = Color(0xFF0E1218),
    surfaceContainerLow = Color(0xFF171C23),
    surfaceContainer = Color(0xFF1F252D),
    surfaceContainerHigh = Color(0xFF252B34),
    surfaceContainerHighest = Color(0xFF2C333C),
    outline = Color(0xFF6E7681),
    outlineVariant = Color(0xFF353C45),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6E9EE),
    inverseOnSurface = Color(0xFF1A1F26),
    inversePrimary = BrandBlue
)

/*
 * 扩展语义色：M3 的 ColorScheme 没有 success / warning / 取景叠加层，
 * 这三类在本项目里反复出现（离线横幅、额度提示、快门控件），抽成语义槽避免各处硬编码。
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /* 取景器叠加在实时画面上，不随主题反转，否则暗色下会看不清 */
    val cameraControl: Color,
    val cameraControlDisabled: Color,
    val cameraOnOverlay: Color,
    val cameraScrim: Color
)

private val LightExtended = ExtendedColors(
    success = Color(0xFF0F6E56),
    onSuccess = Color.White,
    successContainer = Color(0xFFD3EBE2),
    onSuccessContainer = Color(0xFF04352A),
    warning = Color(0xFF854F0B),
    onWarning = Color.White,
    warningContainer = Color(0xFFFAEEDA),
    onWarningContainer = Color(0xFF3D2404),
    cameraControl = Color(0xFF1F2328),
    cameraControlDisabled = Color(0xFFB4B2A9),
    cameraOnOverlay = Color.White,
    cameraScrim = Color(0x99000000)
)

private val DarkExtended = ExtendedColors(
    success = Color(0xFF7FD3B6),
    onSuccess = Color(0xFF03301F),
    successContainer = Color(0xFF0E5140),
    onSuccessContainer = Color(0xFFD3EBE2),
    warning = Color(0xFFF2C07A),
    onWarning = Color(0xFF3D2404),
    warningContainer = Color(0xFF4A3211),
    onWarningContainer = Color(0xFFFAEEDA),
    cameraControl = Color(0xFF1F2328),
    cameraControlDisabled = Color(0xFFB4B2A9),
    cameraOnOverlay = Color.White,
    cameraScrim = Color(0xCC000000)
)

private val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/* 便捷访问器：extendedColors().warning */
@Composable
@ReadOnlyComposable
fun extendedColors(): ExtendedColors = LocalExtendedColors.current

/*
 * 字阶：M3 默认 Typography 的字重偏轻，标题与正文区分度不足。
 * 这里把标题统一提到 SemiBold、正文行高放宽到约 1.5 倍，提升长文阅读舒适度。
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
)

/* 形状：统一圆角梯度，卡片与弹窗比默认 M3 略收，避免「廉价感」 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/*
 * 动态配色：Android 12+ 跟随壁纸生成方案，但把 primary / secondary 换回品牌色，
 * 保证不同设备上仍有统一的品牌识别；低版本回退到固定方案。
 */
private fun ColorScheme.withBrandAccent(dark: Boolean): ColorScheme = if (dark) {
    copy(
        primary = BrandBlueInverse,
        onPrimary = Color(0xFF04223D),
        primaryContainer = BrandBlueContainerDark,
        onPrimaryContainer = BrandBlueContainerLight,
        secondary = BrandGreenInverse,
        onSecondary = Color(0xFF03301F),
        secondaryContainer = BrandGreenContainerDark,
        onSecondaryContainer = BrandGreenContainerLight
    )
} else {
    copy(
        primary = BrandBlue,
        onPrimary = Color.White,
        primaryContainer = BrandBlueContainerLight,
        onPrimaryContainer = Color(0xFF0A2E52),
        secondary = BrandGreen,
        onSecondary = Color.White,
        secondaryContainer = BrandGreenContainerLight,
        onSecondaryContainer = Color(0xFF04352A)
    )
}

@Composable
fun GleanNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(context).withBrandAccent(dark = true)
        dynamicColor && supportsDynamic -> dynamicLightColorScheme(context).withBrandAccent(dark = false)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtended else LightExtended
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
