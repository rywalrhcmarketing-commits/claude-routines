package pl.jarvis.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import pl.jarvis.app.data.SettingsRepository

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFF81D4FA),
    tertiary = Color(0xFFB3E5FC)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF0288D1),
    tertiary = Color(0xFF039BE5)
)

/**
 * Wysoki kontrast - czysta czerń i biel z nasyconymi akcentami.
 * Dla osób słabowidzących; wyłącza dynamic color, bo tapeta systemowa
 * potrafi obniżyć kontrast.
 */
private val HighContrastDarkScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color.Black,
    secondary = Color(0xFFFFEB3B),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    error = Color(0xFFFF5252),
    onError = Color.Black,
    outline = Color.White
)

private val HighContrastLightScheme = lightColorScheme(
    primary = Color(0xFF00344D),
    onPrimary = Color.White,
    secondary = Color(0xFF4A2800),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color.Black,
    error = Color(0xFFB00020),
    onError = Color.White,
    outline = Color.Black
)

/** Mnożnik rozmiaru tekstu przy włączonych dużych literach. */
private const val LARGE_TEXT_SCALE = 1.30f

/**
 * Motyw aplikacji Jarvis.
 *
 * Domyślnie czyta opcje dostępności (wysoki kontrast, duże litery) z ustawień.
 * Parametry można nadpisać w podglądach Compose.
 */
@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    highContrast: Boolean? = null,
    largeText: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsRepository(context) }

    val useHighContrast = highContrast ?: settings.isHighContrastEnabled()
    val useLargeText = largeText ?: settings.isLargeTextEnabled()

    val colorScheme = when {
        // Wysoki kontrast ma pierwszeństwo przed dynamic color.
        useHighContrast && darkTheme -> HighContrastDarkScheme
        useHighContrast -> HighContrastLightScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val typography = if (useLargeText) {
        remember { Typography().scaledBy(LARGE_TEXT_SCALE) }
    } else {
        MaterialTheme.typography
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}

/**
 * Powiększa wszystkie style tekstu o zadany mnożnik.
 * Wartości nieokreślone (`TextUnit.Unspecified`) zostają nietknięte -
 * mnożenie ich rzuca wyjątkiem.
 */
private fun Typography.scaledBy(factor: Float): Typography = Typography(
    displayLarge = displayLarge.scaledBy(factor),
    displayMedium = displayMedium.scaledBy(factor),
    displaySmall = displaySmall.scaledBy(factor),
    headlineLarge = headlineLarge.scaledBy(factor),
    headlineMedium = headlineMedium.scaledBy(factor),
    headlineSmall = headlineSmall.scaledBy(factor),
    titleLarge = titleLarge.scaledBy(factor),
    titleMedium = titleMedium.scaledBy(factor),
    titleSmall = titleSmall.scaledBy(factor),
    bodyLarge = bodyLarge.scaledBy(factor),
    bodyMedium = bodyMedium.scaledBy(factor),
    bodySmall = bodySmall.scaledBy(factor),
    labelLarge = labelLarge.scaledBy(factor),
    labelMedium = labelMedium.scaledBy(factor),
    labelSmall = labelSmall.scaledBy(factor)
)

private fun TextStyle.scaledBy(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight
)
