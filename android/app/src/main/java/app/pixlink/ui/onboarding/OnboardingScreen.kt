package app.pixlink.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.pixlink.notifications.NotificationBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private const val PAGES = 4

/** First-run welcome: what Pixlink does, the permissions it needs, then linking. */
@Composable
fun OnboardingScreen(
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    onGrantPermissions: () -> Unit,
    onSkip: () -> Unit,
) {
    val pager = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            if (pager.currentPage < PAGES - 1) TextButton(onClick = onSkip) { Text("Skip") }
            else Spacer(Modifier.height(48.dp))
        }

        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            // Pages fade and shrink slightly as they slide away.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val offset = pager.offsetFor(page).absoluteValue.coerceIn(0f, 1f)
                    alpha = 1f - offset * 0.6f
                    scaleX = 1f - offset * 0.08f
                    scaleY = 1f - offset * 0.08f
                },
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    0 -> WelcomePage()
                    1 -> FeaturesPage(visible = pager.currentPage == 1)
                    2 -> PermissionsPage(onGrantPermissions)
                    else -> LinkPage(onScan, onEnterCode)
                }
            }
        }

        // Dots + Next
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(PAGES) { i ->
                    val selected = pager.currentPage == i
                    val width by animateDpAsState(if (selected) 28.dp else 8.dp, spring(), label = "dot")
                    val color by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor",
                    )
                    Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
                }
            }
            if (pager.currentPage < PAGES - 1) {
                Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Text(if (pager.currentPage == 0) "Get started" else "Next")
                }
            }
        }
    }
}

private fun PagerState.offsetFor(page: Int) = (currentPage - page) + currentPageOffsetFraction

@Composable
private fun WelcomePage() {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        LinkIllustration()
        Text(
            "Your Pixel,\non your PC",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Read and reply to texts, take calls, grab photos and use your phone's screen without picking it up.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private data class Feature(val icon: ImageVector, val title: String, val text: String)

private val features = listOf(
    Feature(Icons.Outlined.ChatBubbleOutline, "Texts and notifications", "See them on your PC and reply right there."),
    Feature(Icons.Outlined.Call, "Calls", "Dial, answer and talk through your PC."),
    Feature(Icons.Outlined.Photo, "Photos and files", "Your gallery and files, a drag away."),
    Feature(Icons.Outlined.PhoneAndroid, "Your screen", "Use your phone's apps with your mouse."),
    Feature(Icons.Outlined.Wifi, "Hotspot", "Get your PC online with one click."),
    Feature(Icons.Outlined.Lock, "Private", "Direct and encrypted. No cloud, no account."),
)

@Composable
private fun FeaturesPage(visible: Boolean) {
    // Reveal the features one by one the first time this page comes into view.
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        for (i in shown until features.size) { delay(90); shown = i + 1 }
    }
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Everything in one place", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        features.forEachIndexed { i, f ->
            AnimatedVisibility(
                visible = i < shown,
                enter = fadeIn() + slideInVertically { it / 2 },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(f.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Column {
                        Text(f.title, style = MaterialTheme.typography.titleMedium)
                        Text(f.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsPage(onGrantPermissions: () -> Unit) {
    val ctx = LocalContext.current
    // Re-checked whenever the user comes back from a settings screen.
    val perms = app.pixlink.ui.rememberPermissionState(ctx)
    val notifAccess = perms.notifications
    val core = perms.callsContacts
    val photos = perms.photos
    var dialog by remember { mutableStateOf(false) }
    if (dialog) app.pixlink.ui.SpecialAccessDialog(app.pixlink.ui.SpecialAccess.Notifications, ctx) { dialog = false }

    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("A few permissions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Pixlink only uses these to send things to your own PC. You can change them anytime.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        PermissionRow(Icons.Outlined.Notifications, "Notification access", "To show notifications and reply to texts. You'll find Pixlink in the list.", notifAccess) {
            dialog = true
        }
        PermissionRow(Icons.Outlined.Call, "Calls and contacts", "To call from your PC", core, onGrantPermissions)
        PermissionRow(Icons.Outlined.Photo, "Photos", "To browse your gallery on your PC", photos, onGrantPermissions)
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, text: String, done: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "perm",
    )
    Card(onClick = onClick, enabled = !done, colors = CardDefaults.cardColors(containerColor = container, disabledContainerColor = container)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (done) "Allowed" else "Not allowed yet",
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun LinkPage(onScan: () -> Unit, onEnterCode: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SearchingPulse(size = 120.dp)
        Text("Link your PC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Open Pixlink on your Windows PC. It shows a QR code and a 5-digit code. Use either one.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan QR code")
        }
        FilledTonalButton(onClick = onEnterCode, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.Dialpad, null); Spacer(Modifier.width(10.dp)); Text("Enter 5-digit code")
        }
    }
}
