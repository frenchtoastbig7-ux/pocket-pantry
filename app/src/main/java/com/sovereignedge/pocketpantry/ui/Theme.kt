package com.sovereignedge.pocketpantry.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    MaterialTheme(colorScheme = colors, content = content)
}
