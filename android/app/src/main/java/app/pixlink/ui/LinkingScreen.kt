package app.pixlink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pixlink.link.LinkHub
import app.pixlink.link.LinkState
import app.pixlink.ui.onboarding.SearchingPulse
import app.pixlink.ui.onboarding.SuccessCheck
import kotlinx.coroutines.delay

/** Shown while pairing: searching → animated check, or a clear error with Try again. */
@Composable
fun LinkingScreen(pcName: String, onDone: () -> Unit, onRetry: () -> Unit) {
    val link by LinkHub.pairing.collectAsState()
    val finish = { LinkHub.setPairing(null); onDone() }
    val retry = { LinkHub.setPairing(null); onRetry() }

    // Move on by itself shortly after the success animation.
    LaunchedEffect(link) {
        if (link is LinkState.Connected) { delay(2600); finish() }
    }

    Surface(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = when (link) {
                is LinkState.Connected -> "ok"
                is LinkState.Error -> "error"
                else -> "busy"
            },
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() },
            label = "linking",
        ) { phase ->
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                when (phase) {
                    "ok" -> {
                        SuccessCheck()
                        Text("You're linked!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Tethr Link is connected to ${(link as? LinkState.Connected)?.pcName ?: pcName}.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = finish, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
                    }
                    "error" -> {
                        Text("Couldn't link", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            (link as? LinkState.Error)?.message ?: "Something went wrong.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = retry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
                        OutlinedButton(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                    }
                    else -> {
                        SearchingPulse()
                        Text("Linking to $pcName…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Keep Tethr Link open on your PC.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
