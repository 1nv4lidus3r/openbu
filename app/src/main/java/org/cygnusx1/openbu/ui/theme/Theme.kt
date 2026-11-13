package org.cygnusx1.openbu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * BamMate look: true-black (OLED) backgrounds with blue accents, modelled on the Bambuddy web
 * UI. The scheme is fixed, so it ignores the device's light/dark mode and dynamic colors.
 */
private val Blue = Color(0xFF3B82F6)
private val BlueLight = Color(0xFF60A5FA)
private val BlueDeep = Color(0xFF1E3A8A)

private val BamMateColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueDeep,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = BlueLight,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF172554),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFE5E7EB),
    surface = Color.Black,
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1B1B20),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceTint = Blue,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0E),
    surfaceContainer = Color(0xFF111115),
    surfaceContainerHigh = Color(0xFF16161B),
    surfaceContainerHighest = Color(0xFF1B1B20),
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF26262D),
    error = Color(0xFFEF5350),
    onError = Color.White,
)

@Composable
fun OpenbuTheme(
    // Kept so existing call sites compile. The BamMate theme is always dark.
    @Suppress("UNUSED_PARAMETER") overrideDeviceTheme: Boolean = false,
    customBackgroundColor: Int? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (customBackgroundColor != null) {
        // The old direct-connection dashboard lets you pick a per-printer background.
        val bg = Color(customBackgroundColor)
        BamMateColors.copy(background = bg, surface = bg)
    } else {
        BamMateColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
