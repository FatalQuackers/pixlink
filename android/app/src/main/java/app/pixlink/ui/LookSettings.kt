package app.pixlink.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pixlink.pairing.PairedStore

/** Settings → Look: light/dark/system and an accent colour (or your wallpaper's colours). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSettings() {
    val ctx = LocalContext.current
    val store = remember { PairedStore(ctx) }
    var theme by remember { mutableStateOf(store.theme) }
    var accent by remember { mutableStateOf(store.accent) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                FilterChip(
                    selected = theme == key,
                    onClick = { theme = key; store.theme = key },
                    label = { Text(label) },
                    leadingIcon = if (theme == key) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                )
            }
        }
        Text("Accent colour", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.all.forEach { (key, label) ->
                val selected = accent == key
                val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(
                                Accents.seed(key)?.let { Brush.linearGradient(listOf(it, it)) }
                                    ?: Brush.sweepGradient(listOf(Color(0xFF3D7BFF), Color(0xFF32C48D), Color(0xFFFFC107), Color(0xFFE91E63), Color(0xFF3D7BFF)))
                            )
                            .border(ring, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { accent = key; store.accent = key },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            selected -> Icon(Icons.Outlined.Check, null, tint = Color.White)
                            key == "dynamic" -> Icon(Icons.Outlined.Wallpaper, null, tint = Color.White)
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
