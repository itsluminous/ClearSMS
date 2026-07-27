package app.clearsms.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.clearsms.domain.model.ThemeMode

/**
 * Curated fallback palette (teal primary / indigo secondary) used when
 * Material You dynamic color is unavailable (pre-Android 12) or disabled.
 */
private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF006A60),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF74F8E5),
        onPrimaryContainer = Color(0xFF00201C),
        secondary = Color(0xFF4355B9),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDEE0FF),
        onSecondaryContainer = Color(0xFF00105C),
        tertiary = Color(0xFF3B6939),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBCF0B4),
        onTertiaryContainer = Color(0xFF002204),
        background = Color(0xFFF4FBF8),
        onBackground = Color(0xFF161D1B),
        surface = Color(0xFFF4FBF8),
        onSurface = Color(0xFF161D1B),
        surfaceVariant = Color(0xFFDAE5E1),
        onSurfaceVariant = Color(0xFF3F4946),
        outline = Color(0xFF6F7976),
    )

private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFF53DBC9),
        onPrimary = Color(0xFF003731),
        primaryContainer = Color(0xFF005048),
        onPrimaryContainer = Color(0xFF74F8E5),
        secondary = Color(0xFFBAC3FF),
        onSecondary = Color(0xFF08218A),
        secondaryContainer = Color(0xFF293CA0),
        onSecondaryContainer = Color(0xFFDEE0FF),
        tertiary = Color(0xFFA1D399),
        onTertiary = Color(0xFF0A390F),
        tertiaryContainer = Color(0xFF235024),
        onTertiaryContainer = Color(0xFFBCF0B4),
        background = Color(0xFF0E1513),
        onBackground = Color(0xFFDDE4E1),
        surface = Color(0xFF0E1513),
        onSurface = Color(0xFFDDE4E1),
        surfaceVariant = Color(0xFF3F4946),
        onSurfaceVariant = Color(0xFFBEC9C5),
        outline = Color(0xFF899390),
    )

/** App theme: Material You dynamic color on Android 12+, teal/indigo fallback otherwise. */
@Composable
fun ClearSmsTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkScheme
            else -> LightScheme
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ClearSmsTypography,
        shapes = ClearSmsShapes,
        content = content,
    )
}
