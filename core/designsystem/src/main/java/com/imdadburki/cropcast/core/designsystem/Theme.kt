package com.imdadburki.cropcast.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.imdadburki.cropcast.core.common.ThemeMode

/** CROPCAST design system (spec §10). Dark-first, Apple-inspired minimal. */
object CropcastColors {
    val Background = Color(0xFF0B0B0D)
    val Surface = Color(0xFF151518)
    val ElevatedSurface = Color(0xFF1D1D22)
    val PrimaryText = Color(0xFFF5F5F7)
    val SecondaryText = Color(0xFF92929B)
    /** Red is used ONLY for recording actions and recording states. */
    val AccentRed = Color(0xFFFF453A)
    val Success = Color(0xFF30D158)
    val Warning = Color(0xFFFFB340)
    val Border = Color(0xFF29292F)
}

private val DarkScheme = darkColorScheme(
    primary = CropcastColors.AccentRed,
    onPrimary = Color.White,
    background = CropcastColors.Background,
    onBackground = CropcastColors.PrimaryText,
    surface = CropcastColors.Surface,
    onSurface = CropcastColors.PrimaryText,
    surfaceVariant = CropcastColors.ElevatedSurface,
    onSurfaceVariant = CropcastColors.SecondaryText,
    outline = CropcastColors.Border,
    secondary = CropcastColors.SecondaryText,
    tertiary = CropcastColors.Success,
    error = CropcastColors.AccentRed,
)

private val LightScheme = lightColorScheme(
    primary = CropcastColors.AccentRed,
    onPrimary = Color.White,
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF0B0B0D),
    surface = Color.White,
    onSurface = Color(0xFF0B0B0D),
    surfaceVariant = Color(0xFFE8E8ED),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD2D2D7),
    secondary = Color(0xFF6E6E73),
    tertiary = Color(0xFF248A3D),
    error = CropcastColors.AccentRed,
)

private val CropcastTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp,
    ),
)

/** Theme mode lives in :core:common (pure enum, no Android dependency). */

@Composable
fun CropcastTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = CropcastTypography,
        content = content,
    )
}
