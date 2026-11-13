package app.pixlink

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pixlink.features.PhoneRinger
import app.pixlink.link.LinkService
import app.pixlink.pairing.PairedStore
import app.pixlink.power.PowerMode
import android.net.Uri
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.pixlink.pairing.PairingInfo
import app.pixlink.ui.AppFilterScreen
import app.pixlink.ui.CodeEntryScreen
import app.pixlink.ui.HomeScreen
import app.pixlink.ui.LinkingScreen
import app.pixlink.ui.onboarding.OnboardingScreen
import app.pixlink.ui.PairScreen
import app.pixlink.ui.PixlinkTheme
import app.pixlink.ui.PowerModeScreen
import app.pixlink.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_SEND_FILES = "app.pixlink.SEND_FILES"
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** "Send files" (home screen, widget): pick files, then hand them to the share screen. */
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        startActivity(
            Intent(this, app.pixlink.share.ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun pickFilesToSend() = filePicker.launch(arrayOf("*/*"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleDeepLink(intent)
        if (PairedStore(this).pcs.isNotEmpty()) LinkService.start(this)

        setContent {
            PixlinkTheme {
                Surface(Modifier.fillMaxSize()) {
                    var whatsNew by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(app.pixlink.ui.WhatsNew.shouldShow(this@MainActivity)) }
                    if (!PixlinkApp.isSupportedDevice) NotSupported() else AppNav()
                    // After an update: the changelog, once.
                    androidx.compose.animation.AnimatedVisibility(
                        whatsNew,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 1.08f),
                    ) {
                        app.pixlink.ui.WhatsNewScreen(onDone = { app.pixlink.ui.WhatsNew.markSeen(this@MainActivity); whatsNew = false })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        PowerMode.refresh()
    }

    /** Copied something on the phone? Opening Pixlink sends it to your PC (Android only allows this in front). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && PairedStore(this).sendClipboardOnOpen) app.pixlink.clipboard.ClipboardSync.sendCurrent(this, quiet = true)
    }

    fun requestSmsPermissions() {
        permissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS))
    }

    /** A pixlink://pair link opened from any QR scanner app, or "stop ringing" from the notification. */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action == PhoneRinger.ACTION_STOP) { PhoneRinger.stop(this); return }
        if (intent?.action == ACTION_SEND_FILES) { intent.action = null; pickFilesToSend(); return }
        val data = intent?.data?.toString() ?: return
        if (data.startsWith("pixlink://pair")) LinkService.pair(this, data)
    }

    fun requestCorePermissions() {
        permissions.launch(
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        )
    }

    @androidx.compose.runtime.Composable
    private fun AppNav() {
        val nav = rememberNavController()
        val store = remember { PairedStore(this) }
        val start = if (!store.onboardingDone && store.pcs.isEmpty()) "onboarding" else "home"

        fun goHome() {
            store.onboardingDone = true
            nav.navigate("home") { popUpTo(0) { inclusive = true } }
        }
        fun goLinking(pcName: String, from: String) =
            nav.navigate("linking/${Uri.encode(pcName)}") { popUpTo(from) { inclusive = true } }

        NavHost(nav, startDestination = start) {
            composable("onboarding") {
                OnboardingScreen(
                    onScan = { nav.navigate("pair") },
                    onEnterCode = { nav.navigate("code") },
                    onGrantPermissions = ::requestCorePermissions,
                    onSkip = ::goHome,
                )
            }
            composable("home") {
                HomeScreen(
                    onPair = { nav.navigate("pair") },
                    onPowerMode = { nav.navigate("power") },
                    onSettings = { nav.navigate("settings") },
                    onActivity = { nav.navigate("activity") },
                    onGrantPermissions = ::requestCorePermissions,
                    onGrantSms = ::requestSmsPermissions,
                )
            }
            composable("activity") { app.pixlink.ui.ActivityScreen(onBack = { nav.popBackStack() }) }
            composable("pair") {
                PairScreen(
                    onScanned = { uri ->
                        LinkService.pair(this@MainActivity, uri)
                        goLinking(PairingInfo.parse(uri)?.pcName ?: "your PC", "pair")
                    },
                    onEnterCode = { nav.navigate("code") { popUpTo("pair") { inclusive = true } } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("code") {
                CodeEntryScreen(onBack = { nav.popBackStack() }, onPairStarted = { name -> goLinking(name, "code") })
            }
            composable("linking/{name}") { entry ->
                LinkingScreen(
                    pcName = entry.arguments?.getString("name") ?: "your PC",
                    onDone = ::goHome,
                    onRetry = { nav.navigate("code") { popUpTo("linking/{name}") { inclusive = true } } },
                )
            }
            composable("power") { PowerModeScreen(onBack = { nav.popBackStack() }) }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() }, onChooseApps = { nav.navigate("apps") })
            }
            composable("apps") { AppFilterScreen(onBack = { nav.popBackStack() }) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun NotSupported() {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pixlink is made for Pixel phones", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "This phone isn't supported yet.",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
