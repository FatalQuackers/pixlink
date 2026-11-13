package app.pixlink.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pixlink.BuildConfig
import app.pixlink.R
import app.pixlink.pairing.PairedStore
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** "What's new" after an update. The notes ship inside the app (assets/whatsnew.md, from notes/<version>.md). */
object WhatsNew {
    data class Item(val heading: Boolean, val text: String)

    fun shouldShow(ctx: Context): Boolean {
        val store = PairedStore(ctx)
        if (store.whatsNewSeen == BuildConfig.VERSION_CODE) return false
        if (!store.onboardingDone && store.pcs.isEmpty()) { markSeen(ctx); return false } // fresh install
        return load(ctx).isNotEmpty()
    }

    fun markSeen(ctx: Context) { PairedStore(ctx).whatsNewSeen = BuildConfig.VERSION_CODE }

    fun load(ctx: Context): List<Item> = runCatching {
        ctx.assets.open("whatsnew.md").bufferedReader().readLines()
    }.getOrDefault(emptyList()).takeWhile { !it.trim().startsWith("## Install") }.mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() || line.startsWith("<!--") || line.startsWith("# ") -> null
            line.startsWith("#") -> Item(true, line.trimStart('#', ' ').clean())
            line.startsWith("- ") || line.startsWith("* ") -> Item(false, line.drop(2).clean())
            else -> Item(false, line.clean())
        }
    }

    private fun String.clean() = replace("**", "").replace("`", "").replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
}

@Composable
fun WhatsNewScreen(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val items = remember { WhatsNew.load(ctx) }
    val logoScale = remember { Animatable(0.3f) }
    val logoTurn = remember { Animatable(-25f) }
    var shown by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        logoScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) { logoTurn.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessVeryLow)) }
    LaunchedEffect(Unit) {
        delay(350)
        for (i in 1..items.size + 1) { shown = i; delay(90) }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val primary = MaterialTheme.colorScheme.primary
    val confetti = remember { List(42) { Confetti(Random.nextFloat(), Random.nextFloat() * 360f, 0.6f + Random.nextFloat() * 0.8f, Random.nextInt(5)) } }
    val fall = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fall.animateTo(1f, tween(2600, easing = FastOutSlowInEasing)) }
    val colors = listOf(primary, MaterialTheme.colorScheme.tertiary, Color(0xFFFFC107), Color(0xFF4CAF50), Color(0xFFE91E63))

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            // Confetti that bursts from the logo and drifts down once.
            Canvas(Modifier.fillMaxSize()) {
                val t = fall.value
                if (t >= 1f) return@Canvas
                val origin = Offset(size.width / 2, size.height * 0.2f)
                confetti.forEach { c ->
                    val angle = Math.toRadians(c.angle.toDouble())
                    val dist = size.width * 0.55f * c.speed * t
                    val x = origin.x + (cos(angle) * dist).toFloat()
                    val y = origin.y + (sin(angle) * dist).toFloat() + size.height * 0.5f * t * t
                    drawCircle(colors[c.color].copy(alpha = 1f - t), radius = 7f * c.speed, center = Offset(x, y))
                }
            }

            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                    Canvas(Modifier.size(150.dp)) {
                        drawCircle(primary.copy(alpha = 0.25f * (1 - ring)), radius = size.minDimension / 2 * (0.55f + 0.45f * ring))
                    }
                    Box(
                        Modifier.size(92.dp).scale(logoScale.value).rotate(logoTurn.value)
                            .clip(RoundedCornerShape(26.dp)).background(Color(0xFF3D7BFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(92.dp))
                    }
                }
                Text("What's new", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Pixlink ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )

                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.forEachIndexed { i, item ->
                        AnimatedVisibility(shown > i, enter = fadeIn(tween(300)) + slideInVertically(tween(350)) { it / 2 }) {
                            if (item.heading) {
                                Text(item.text, style = MaterialTheme.typography.titleSmall, color = primary, modifier = Modifier.padding(top = 8.dp))
                            } else {
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(primary))
                                    Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(shown > items.size, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it }) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp)) {
                        Text("Continue", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

private class Confetti(val x: Float, val angle: Float, val speed: Float, val color: Int)
