package app.pixlink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.pixlink.pairing.PairedStore

/** Accent colours offered in Settings → Look ("dynamic" = your wallpaper colours). */
object Accents {
    val all = listOf(
        "dynamic" to "Wallpaper",
        "blue" to "Blue",
        "teal" to "Teal",
        "green" to "Green",
        "orange" to "Orange",
        "pink" to "Pink",
        "purple" to "Purple",
    )

    fun seed(name: String): Color? = when (name) {
        "blue" -> Color(0xFF3D7BFF)
        "teal" -> Color(0xFF00897B)
        "green" -> Color(0xFF2E7D32)
        "orange" -> Color(0xFFEF6C00)
        "pink" -> Color(0xFFD81B60)
        "purple" -> Color(0xFF7B1FA2)
        else -> null
    }
}

@Composable
fun PixlinkTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PairedStore(ctx) }
    val version by PairedStore.themeVersion.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val (theme, accent) = remember(version) { store.theme to store.accent }
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> systemDark }

    val seed = Accents.seed(accent)
    val scheme = when {
        seed == null && dark -> dynamicDarkColorScheme(ctx)
        seed == null -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = lighten(seed, 0.35f), onPrimary = Color(0xFF0B1220),
            primaryContainer = darken(seed, 0.45f), onPrimaryContainer = lighten(seed, 0.75f),
            secondary = lighten(seed, 0.5f), secondaryContainer = darken(seed, 0.6f), onSecondaryContainer = lighten(seed, 0.8f),
            tertiary = lighten(seed, 0.45f),
        )
        else -> lightColorScheme(
            primary = seed, onPrimary = Color.White,
            primaryContainer = lighten(seed, 0.8f), onPrimaryContainer = darken(seed, 0.6f),
            secondary = darken(seed, 0.15f), secondaryContainer = lighten(seed, 0.86f), onSecondaryContainer = darken(seed, 0.6f),
            tertiary = darken(seed, 0.25f),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
private fun lighten(c: Color, t: Float) = mix(c, Color.White, t)
private fun darken(c: Color, t: Float) = mix(c, Color.Black, t)
