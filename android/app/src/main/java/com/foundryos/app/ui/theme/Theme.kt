package com.foundryos.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FoundryColors {
    val Canvas = Color(0xFFFFFFFF)
    val SidebarBg = Color(0xFFF6F6F7)
    val CardBg = Color(0xFFFFFFFF)
    val SoftSurface = Color(0xFFF3F3F5)
    val Border = Color(0xFFE9E9EC)
    val InkPrimary = Color(0xFF111111)
    val InkSecondary = Color(0xFF6B7280)
    val InkMuted = Color(0xFF9CA3AF)
    val GreenBg = Color(0xFFECFDF5)
    val GreenInk = Color(0xFF15803D)
    val GreenSolid = Color(0xFF168A47)
    val LavenderBg = Color(0xFFF5F3FF)
    val LavenderInk = Color(0xFF7C3AED)
    val RedInk = Color(0xFFDC2626)
    val RedBg = Color(0xFFFEF2F2)
    val AmberInk = Color(0xFFD97706)
}

private val LightColors = lightColorScheme(
    primary = FoundryColors.InkPrimary,
    onPrimary = Color.White,
    secondary = FoundryColors.LavenderInk,
    onSecondary = Color.White,
    background = FoundryColors.Canvas,
    onBackground = FoundryColors.InkPrimary,
    surface = FoundryColors.Canvas,
    onSurface = FoundryColors.InkPrimary,
    surfaceVariant = FoundryColors.SidebarBg,
    onSurfaceVariant = FoundryColors.InkSecondary,
    outline = FoundryColors.Border,
    error = FoundryColors.RedInk,
)

private val FoundryTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun FoundryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = FoundryTypography,
        content = content,
    )
}
