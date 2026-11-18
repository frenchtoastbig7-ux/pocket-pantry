package com.sovereignedge.pocketpantry.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5E3E),
    secondary = Color(0xFF8A6A1F),
    tertiary = Color(0xFFB4572A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD3A8),
    secondary = Color(0xFFF2E8C9),
    tertiary = Color(0xFFF0A274),
)

/** Softer, larger corners than the Material defaults: cards and sheets read as rounded tiles. */
private val PantryShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Tighter, bolder display and title styles for the large collapsing headers and hero numbers. */
private val PantryTypography = Typography().let { base ->
    base.copy(
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Whether the app should draw dark for [themeMode] ("system", "light" or "dark"). */
@Composable
fun isDarkTheme(themeMode: String): Boolean = when (themeMode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

/** Material You: follows the Pixel wallpaper palette; the fixed greens are only a fallback. */
@Composable
fun PocketPantryTheme(themeMode: String = "system", dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val context = LocalContext.current
    val colors = when {
        dynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, shapes = PantryShapes, typography = PantryTypography, content = content)
}
