package app.pixlink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pixlink.link.LinkService
import app.pixlink.pairing.PairingDiscovery
import app.pixlink.ui.onboarding.SearchingPulse
import kotlinx.coroutines.launch

private sealed interface CodeState {
    data object Typing : CodeState
    data object Searching : CodeState
    data class Choose(val pcs: List<PairingDiscovery.PcAd>) : CodeState
    data class Problem(val message: String) : CodeState
}

/** Type the 5-digit code shown on the PC. The phone then finds that PC on the Wi-Fi and pairs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEntryScreen(onBack: () -> Unit, onPairStarted: (pcName: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<CodeState>(CodeState.Typing) }
    val focus = remember { FocusRequester() }

    fun pair(pc: PairingDiscovery.PcAd) {
        LinkService.pair(ctx, pc.pairingUri(code))
        onPairStarted(pc.name)
    }

    fun search() {
        state = CodeState.Searching
        scope.launch {
            val pcs = runCatching { PairingDiscovery.discover(ctx) }.getOrDefault(emptyList())
            state = when (pcs.size) {
                0 -> CodeState.Problem("Couldn't find a PC showing a code. Make sure Tethr Link is open on your PC and both are on the same Wi-Fi.")
                1 -> { pair(pcs.first()); CodeState.Searching }
                else -> CodeState.Choose(pcs)
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Enter code") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                "Type the 5-digit code shown in Tethr Link on your PC",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            // One hidden text field drives five boxes.
            BasicTextField(
                value = code,
                onValueChange = { v ->
                    val digits = v.filter(Char::isDigit).take(5)
                    code = digits
                    if (state is CodeState.Problem) state = CodeState.Typing
                    if (digits.length == 5) search()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = state is CodeState.Typing || state is CodeState.Problem,
                modifier = Modifier.focusRequester(focus),
                decorationBox = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(5) { i -> DigitBox(code.getOrNull(i), active = i == code.length) }
                    }
                },
            )

            AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "codeState") { s ->
                when (s) {
                    CodeState.Typing -> Text(
                        "The code changes every few minutes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CodeState.Searching -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SearchingPulse(size = 96.dp)
                        Text("Looking for your PC…", style = MaterialTheme.typography.bodyLarge)
                    }
                    is CodeState.Choose -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Which PC?", style = MaterialTheme.typography.titleMedium)
                        s.pcs.forEach { pc ->
                            Card(onClick = { pair(pc) }, modifier = Modifier.fillMaxWidth()) {
                                ListItem(
                                    leadingContent = { Icon(Icons.Outlined.Computer, null) },
                                    headlineContent = { Text(pc.name) },
                                    supportingContent = { Text(pc.host) },
                                )
                            }
                        }
                    }
                    is CodeState.Problem -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        TextButton(onClick = { code = ""; state = CodeState.Typing; focus.requestFocus() }) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DigitBox(digit: Char?, active: Boolean) {
    val border by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "border",
    )
    // Each digit "pops" as it's typed.
    val scale by animateFloatAsState(if (digit != null) 1f else 0.92f, spring(dampingRatio = 0.45f), label = "pop")
    Box(
        Modifier
            .size(54.dp, 66.dp)
            .scale(scale)
            .border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit?.toString() ?: "", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    }
}
