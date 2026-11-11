package app.pixlink.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LinkState {
    data object NotPaired : LinkState
    data object Disconnected : LinkState
    data class Connecting(val pcName: String) : LinkState
    data class Connected(val pcName: String) : LinkState
    data class Error(val message: String) : LinkState
}

/**
 * App-wide access point for the live connection, so features (notifications,
 * mirroring, calls…) can send messages without knowing about sockets.
 */
object LinkHub {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    @Volatile internal var client: LinkClient? = null

    internal fun setState(s: LinkState) { _state.value = s }

    val isConnected get() = _state.value is LinkState.Connected

    /** Sends a message if connected; silently drops it otherwise. */
    fun send(env: Envelope) {
        client?.send(env)
    }
}
