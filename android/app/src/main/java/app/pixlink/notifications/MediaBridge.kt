package app.pixlink.notifications

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import kotlinx.serialization.json.put

/**
 * "Now playing" on the PC: whatever is playing on the phone (Spotify, YouTube Music, podcasts…)
 * with play/pause/next/previous. Needs notification access, which Pixlink already has.
 */
object MediaBridge {
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = report()
        override fun onPlaybackStateChanged(state: PlaybackState?) = report()
        override fun onSessionDestroyed() = pickActive()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickActive() }

    fun start(context: Context) = main.post {
        appContext = context.applicationContext
        val m = context.getSystemService(MediaSessionManager::class.java)
        manager = m
        val component = ComponentName(context, NotificationBridge::class.java)
        runCatching { m.addOnActiveSessionsChangedListener(sessionsListener, component, main) }
        pickActive()
    }

    fun stop() = main.post {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** Follows the session that's playing, or else the most recent one. */
    private fun pickActive() {
        val ctx = appContext ?: return
        val sessions = runCatching {
            manager?.getActiveSessions(ComponentName(ctx, NotificationBridge::class.java))
        }.getOrNull().orEmpty()
        val next = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        report()
    }

    fun report() {
        val c = controller
        val meta = c?.metadata
        val appName = c?.packageName?.let { pkg ->
            val pm = appContext?.packageManager
            runCatching { pm?.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        LinkHub.send(msg("media.state") {
            put("active", c != null && meta != null)
            put("playing", c?.playbackState?.state == PlaybackState.STATE_PLAYING)
            meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { put("title", it) }
            (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
                ?.let { put("artist", it) }
            appName?.let { put("app", it) }
        })
    }

    fun action(name: String) = main.post {
        val t = controller?.transportControls ?: return@post
        when (name) {
            "play" -> t.play()
            "pause" -> t.pause()
            "toggle" -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
        }
    }
}
