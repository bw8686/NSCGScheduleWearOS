package uk.bw86.nscgschedule.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * M3 Expressive Color Scheme for NSCG Schedule
 * High saturation, vibrant colors matching Google Calendar's M3 Expressive design
 */
private val NSCGColorScheme = ColorScheme(
    // Primary colors - Intense vibrant orange (Google Calendar style)
    primary = Color(0xFFFF5722),           // Deep Orange 500 - vibrant and saturated
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFF8A65),   // Lighter orange container
    onPrimaryContainer = Color(0xFF1A0000),
    primaryDim = Color(0xFFE64A19),        // Deep Orange 700
    
    // Secondary colors - Vibrant coral/pink accent
    secondary = Color(0xFFFF6E40),         // Deep Orange A200 - bright and energetic
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFFFF9E80), // Deep Orange A100
    onSecondaryContainer = Color(0xFF1A0000),
    secondaryDim = Color(0xFFFF3D00),      // Deep Orange A400
    
    // Tertiary colors - Bright purple/pink for exams
    tertiary = Color(0xFFE040FB),          // Purple A200 - electric purple
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFFEA80FC),  // Purple A100
    onTertiaryContainer = Color(0xFF1A001A),
    tertiaryDim = Color(0xFFD500F9),       // Purple A400
    
    // Surface colors - Pure black for Wear OS
    surfaceContainer = Color(0xFF000000),   // Pure black
    onSurface = Color(0xFFE6E1E5),
    surfaceContainerLow = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFCAC4D0),

    // Background
    background = Color(0xFF000000),         // Pure black
    onBackground = Color(0xFFE6E1E5),
    
    // Error colors - Bright red
    error = Color(0xFFFF1744),             // Red A400
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFF5252),     // Red A200
    onErrorContainer = Color(0xFF000000),
    
    // Outline
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

@Composable
fun NSCGScheduleTheme(
    content: @Composable () -> Unit
) {
    /**
     * M3 Expressive theme for NSCG Schedule WearOS app
     * Uses Material 3 with vibrant orange primary color
     */
    MaterialTheme(
        colorScheme = NSCGColorScheme,
        content = content
    )
}