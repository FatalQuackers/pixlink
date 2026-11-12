package app.pixlink.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * The welcome illustration: a phone and a laptop with little packets of light travelling
 * between them along an arc, and a soft pulse around each device.
 */
@Composable
fun LinkIllustration(modifier: Modifier = Modifier, size: Dp = 260.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val t = rememberInfiniteTransition(label = "link")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "flow")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "bob")

    Canvas(modifier.size(size, size * 0.62f)) {
        val w = this.size.width
        val h = this.size.height
        val floatY = sin(bob * 2 * Math.PI).toFloat() * h * 0.015f

        // Phone (left)
        val phoneW = w * 0.20f; val phoneH = h * 0.62f
        val phone = Offset(w * 0.08f, h * 0.22f + floatY)
        drawCircle(primary.copy(alpha = 0.18f * (1 - pulse)), radius = phoneH * (0.55f + 0.35f * pulse), center = phone + Offset(phoneW / 2, phoneH / 2))
        drawRoundRect(surface, phone, Size(phoneW, phoneH), CornerRadius(phoneW * 0.22f))
        drawRoundRect(outline, phone, Size(phoneW, phoneH), CornerRadius(phoneW * 0.22f), style = Stroke(w * 0.012f))
        drawCircle(outline, radius = w * 0.008f, center = phone + Offset(phoneW / 2, phoneH * 0.07f))

        // Laptop (right)
        val lapW = w * 0.40f; val lapH = h * 0.46f
        val lap = Offset(w * 0.54f, h * 0.20f - floatY)
        drawCircle(primary.copy(alpha = 0.14f * (1 - pulse)), radius = lapW * (0.45f + 0.30f * pulse), center = lap + Offset(lapW / 2, lapH / 2))
        drawRoundRect(surface, lap, Size(lapW, lapH), CornerRadius(w * 0.02f))
        drawRoundRect(outline, lap, Size(lapW, lapH), CornerRadius(w * 0.02f), style = Stroke(w * 0.012f))
        drawRoundRect(
            outline, Offset(lap.x - lapW * 0.08f, lap.y + lapH + h * 0.02f),
            Size(lapW * 1.16f, h * 0.05f), CornerRadius(h * 0.03f),
        )

        // Arc between them, with packets travelling along it
        val from = phone + Offset(phoneW + w * 0.02f, phoneH * 0.45f)
        val to = lap + Offset(-w * 0.02f, lapH * 0.55f)
        val arc = Path().apply {
            moveTo(from.x, from.y)
            quadraticTo((from.x + to.x) / 2, h * 0.02f, to.x, to.y)
        }
        drawPath(arc, outline.copy(alpha = 0.18f), style = Stroke(w * 0.008f, cap = StrokeCap.Round))
        val measure = PathMeasure().apply { setPath(arc, false) }
        for (i in 0 until 3) {
            val p = (flow + i / 3f) % 1f
            val pos = measure.getPosition(p * measure.length)
            val fade = sin(p * Math.PI).toFloat()
            drawCircle(primary.copy(alpha = fade), radius = w * 0.018f, center = pos)
        }
    }
}

/** A circle that springs in, then a check mark drawn stroke by stroke. */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val scale = remember { Animatable(0f) }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(250)
        draw.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(primary.copy(alpha = 0.18f), radius = r * scale.value)
        drawCircle(primary, radius = r * 0.78f * scale.value)
        val check = Path().apply {
            moveTo(r * 0.58f, r * 1.02f)
            lineTo(r * 0.88f, r * 1.32f)
            lineTo(r * 1.45f, r * 0.72f)
        }
        val m = PathMeasure().apply { setPath(check, false) }
        val partial = Path()
        m.getSegment(0f, m.length * draw.value, partial, true)
        drawPath(partial, onPrimary, style = Stroke(r * 0.13f, cap = StrokeCap.Round))
    }
}

/** Radar-style rings while the phone looks for the PC. */
@Composable
fun SearchingPulse(modifier: Modifier = Modifier, size: Dp = 140.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "search")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        for (i in 0 until 3) {
            val q = (p + i / 3f) % 1f
            drawCircle(primary.copy(alpha = (1 - q) * 0.5f), radius = r * (0.25f + 0.75f * q), style = Stroke(r * 0.04f))
        }
        drawCircle(primary, radius = r * 0.22f)
    }
}

