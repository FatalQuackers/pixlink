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
import app.pixlink.link.LinkService
import app.pixlink.pairing.PairedStore
import app.pixlink.power.PowerMode
import app.pixlink.ui.HomeScreen
import app.pixlink.ui.PairScreen
import app.pixlink.ui.PixlinkTheme
import app.pixlink.ui.PowerModeScreen
import app.pixlink.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleDeepLink(intent)
        if (PairedStore(this).pc != null) LinkService.start(this)

        setContent {
            PixlinkTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (!PixlinkApp.isSupportedDevice) NotSupported() else AppNav()
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

    /** A pixlink://pair link opened from any QR scanner app. */
    private fun handleDeepLink(intent: Intent?) {
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
        NavHost(nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onPair = { nav.navigate("pair") },
                    onPowerMode = { nav.navigate("power") },
                    onSettings = { nav.navigate("settings") },
                    onGrantPermissions = ::requestCorePermissions,
                )
            }
            composable("pair") {
                PairScreen(onScanned = { uri ->
                    LinkService.pair(this@MainActivity, uri)
                    nav.popBackStack()
                }, onBack = { nav.popBackStack() })
            }
            composable("power") { PowerModeScreen(onBack = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
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
