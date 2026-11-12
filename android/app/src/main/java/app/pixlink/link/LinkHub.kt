package app.pixlink.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

sealed interface LinkState {
    data object NotPaired : LinkState
    data object Disconnected : LinkState
    data class Connecting(val pcName: String) : LinkState
    data class Connected(val pcName: String) : LinkState
    data class Error(val message: String) : LinkState
}

/**
 * App-wide access point for the live connections. A phone can be linked to several PCs
 * at once (work and home): events such as notifications go to every connected PC, while
 * replies go back to the PC that asked.
 */
object LinkHub {
    private val clients = ConcurrentHashMap<String, LinkClient>()

    private val _states = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    /** Connection state per PC id. */
    val states: StateFlow<Map<String, LinkState>> = _states.asStateFlow()

    private val _pairing = MutableStateFlow<LinkState?>(null)
    /** The pairing in progress (null when not pairing). */
    val pairing: StateFlow<LinkState?> = _pairing.asStateFlow()

    /** Why each PC's last connection ended, shown on the home screen to help troubleshoot. */
    val lastDisconnectReason = ConcurrentHashMap<String, String>()

    internal fun setState(pcId: String, s: LinkState) = _states.update { it + (pcId to s) }
    internal fun clearState(pcId: String) = _states.update { it - pcId }
    internal fun setPairing(s: LinkState?) { _pairing.value = s }

    internal fun attach(pcId: String, client: LinkClient) { clients[pcId] = client }
    internal fun detach(pcId: String, client: LinkClient) { clients.remove(pcId, client) }
    internal fun client(pcId: String): LinkClient? = clients[pcId]

    val isConnected get() = _states.value.values.any { it is LinkState.Connected }
    val connectedPcIds: List<String> get() = _states.value.filterValues { it is LinkState.Connected }.keys.toList()

    /** Sends to every connected PC (notifications, battery, media…). */
    fun send(env: Envelope) {
        for ((id, c) in clients) if (_states.value[id] is LinkState.Connected) c.send(env)
    }

    /** Sends to one PC (replies, file transfers, mirroring). */
    fun sendTo(pcId: String, env: Envelope) {
        clients[pcId]?.send(env)
    }
}
