package kr.sleepsense.app.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.sleepsense.core.Playback
import kr.sleepsense.core.TargetSnapshot
import java.util.UUID

data class MediaChoice(val snapshot: TargetSnapshot, val label: String, val packageName: String)
data class MediaUi(val connected: Boolean = false, val choices: List<MediaChoice> = emptyList())

class MediaRepository(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    val component = ComponentName(context, MediaAccessListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val tracked = linkedMapOf<MediaSession.Token, Tracked>()
    private val mutable = MutableStateFlow(MediaUi())
    val state = mutable.asStateFlow()
    private var subscribed = false
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { reconcile(it.orEmpty()) }

    private inner class Tracked(val controller: MediaController) {
        val id = UUID.randomUUID().toString()
        var mediaGeneration = 0L
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(controller.packageName, 0)
            ).toString()
        }.getOrDefault(controller.packageName)
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                // Do not read titles. Any observed item change invalidates old undo ownership.
                mediaGeneration++
                publish()
            }
            override fun onSessionDestroyed() {
                tracked.remove(controller.sessionToken)
                controller.unregisterCallback(this)
                publish()
            }
        }
        fun snapshot(): TargetSnapshot {
            val playback = controller.playbackState
            val actions = playback?.actions ?: 0
            return TargetSnapshot(
                id,
                when (playback?.state) {
                    PlaybackState.STATE_PLAYING -> Playback.PLAYING
                    PlaybackState.STATE_PAUSED -> Playback.PAUSED
                    else -> Playback.OTHER
                },
                actions and PlaybackState.ACTION_PAUSE != 0L,
                actions and PlaybackState.ACTION_PLAY != 0L,
                mediaGeneration,
            )
        }
    }

    fun accessGranted() = notifications.isNotificationListenerAccessGranted(component)
    fun connect() {
        if (!accessGranted()) { disconnect(); return }
        try {
            if (!subscribed) {
                manager.addOnActiveSessionsChangedListener(listener, component, handler)
                subscribed = true
            }
            reconcile(manager.getActiveSessions(component))
        } catch (_: SecurityException) { disconnect() }
    }

    fun refresh() { if (mutable.value.connected) connect() }

    fun disconnect() {
        if (subscribed) runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
        subscribed = false
        tracked.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        tracked.clear()
        mutable.value = MediaUi()
    }

    private fun reconcile(controllers: List<MediaController>) {
        if (!accessGranted()) { disconnect(); return }
        val tokens = controllers.map { it.sessionToken }.toSet()
        tracked.keys.filter { it !in tokens }.forEach { token ->
            tracked.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }
        controllers.forEach { controller ->
            if (controller.sessionToken !in tracked) {
                val entry = Tracked(controller)
                tracked[controller.sessionToken] = entry
                controller.registerCallback(entry.callback, handler)
            }
        }
        publish()
    }

    private fun publish() {
        mutable.value = MediaUi(subscribed && accessGranted(), tracked.values.mapNotNull { entry ->
            runCatching { MediaChoice(entry.snapshot(), entry.label, entry.controller.packageName) }.getOrNull()
        }.sortedByDescending { it.snapshot.playback == Playback.PLAYING })
    }

    fun snapshot(id: String): TargetSnapshot? {
        if (!accessGranted() || !mutable.value.connected) return null
        return tracked.values.firstOrNull { it.id == id }?.let { runCatching { it.snapshot() }.getOrNull() }
    }

    fun label(id: String): String? = tracked.values.firstOrNull { it.id == id }?.label

    fun pause(id: String, generation: Long): Boolean = control(id, generation, play = false)
    fun play(id: String, generation: Long): Boolean = control(id, generation, play = true)

    private fun control(id: String, generation: Long, play: Boolean): Boolean {
        if (!accessGranted() || !mutable.value.connected) return false
        val entry = tracked.values.firstOrNull { it.id == id } ?: return false
        return runCatching {
            val current = entry.snapshot()
            if (current.mediaGeneration != generation) return false
            if (play) {
                if (current.playback != Playback.PAUSED || !current.canPlay) return false
                entry.controller.transportControls.play()
            } else {
                if (current.playback != Playback.PLAYING || !current.canPause) return false
                entry.controller.transportControls.pause()
            }
            true
        }.getOrDefault(false)
    }
}
