package app.pixlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pixlink.R
import app.pixlink.update.AppUpdater

/** Full-screen "Updating Pixlink" with a progress ring, a gently floating logo and the steps. */
@Composable
fun UpdatingScreen(status: AppUpdater.Status) {
    val visible = status is AppUpdater.Status.Downloading || status is AppUpdater.Status.Installing
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut()) {
        val version = when (status) {
            is AppUpdater.Status.Downloading -> status.version
            is AppUpdater.Status.Installing -> status.version
            else -> ""
        }
        val step = if (status is AppUpdater.Status.Installing) 1 else 0
        val target = (status as? AppUpdater.Status.Downloading)?.progress ?: 1f
        val progress by animateFloatAsState(target, tween(400, easing = FastOutSlowInEasing), label = "progress")

        val loop = rememberInfiniteTransition(label = "updating")
        val float by loop.animateFloat(-6f, 6f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
        val glow by loop.animateFloat(0.9f, 1.15f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
        val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
        val primary = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.surfaceVariant

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(150.dp).scale(glow).clip(RoundedCornerShape(50))
                            .background(Brush.radialGradient(listOf(primary.copy(alpha = 0.45f), Color.Transparent))),
                    )
                    Canvas(Modifier.size(200.dp)) {
                        val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(track, 0f, 360f, false, style = stroke)
                        if (step == 0) drawArc(primary, -90f, 360f * progress, false, style = stroke)
                        else drawArc(primary, spin - 90f, 90f, false, style = stroke) // installing: spinning arc
                        // a small dot riding the ring
                        val angle = Math.toRadians((if (step == 0) 360f * progress else spin + 90f).toDouble() - 90.0)
                        val r = size.minDimension / 2
                        drawCircle(primary, 7.dp.toPx(), Offset(center.x + r * kotlin.math.cos(angle).toFloat(), center.y + r * kotlin.math.sin(angle).toFloat()))
                    }
                    Box(
                        Modifier.size(96.dp).graphicsLayer { translationY = float * density }
                            .clip(RoundedCornerShape(28.dp)).background(Color(0xFF3D7BFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The launcher icon is adaptive; draw its vector foreground on the brand colour.
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(96.dp))
                    }
                }
                Text("Updating Pixlink", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Installing version $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (step == 0) "${(progress * 100).toInt()}%" else "Installing…", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepChip("Download", done = step > 0, active = step == 0)
                    StepChip("Install", done = false, active = step == 1)
                    StepChip("Restart", done = false, active = false)
                }
                Text(
                    "Pixlink restarts by itself and reconnects to your PC.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StepChip(label: String, done: Boolean, active: Boolean) {
    val bg = when {
        active -> MaterialTheme.colorScheme.primary
        done -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (done) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = fg)
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}
