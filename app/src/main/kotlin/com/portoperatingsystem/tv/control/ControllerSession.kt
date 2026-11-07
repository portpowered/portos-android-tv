package com.portoperatingsystem.tv.control

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.portoperatingsystem.tv.protocol.command.TransportAction
import com.portoperatingsystem.tv.state.MediaSessionListener
import com.portoperatingsystem.tv.state.Playback
import com.portoperatingsystem.tv.state.currentPosition
import com.portoperatingsystem.tv.state.mostRelevant
import com.portoperatingsystem.tv.state.playbackOf

/** [PlayerSession] over an Android [MediaController]; seeking goes through its `TransportControls`. */
internal class ControllerSession(private val controller: MediaController) : PlayerSession {
    private val state: PlaybackState? get() = controller.playbackState

    override val playback: Playback? get() = playbackOf(state)

    override val supportsSeek: Boolean get() = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L

    override val positionMillis: Long?
        get() = state?.let {
            currentPosition(
                it.position,
                it.lastPositionUpdateTime,
                it.playbackSpeed,
                SystemClock.elapsedRealtime(),
                playbackOf(it) == Playback.PLAYING
            )
        }

    override val durationMillis: Long?
        get() = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }

    override fun seekTo(positionMillis: Long) = controller.transportControls.seekTo(positionMillis)
}

/**
 * The active media sessions, most recent first, or null when notification-listener access is not granted (Android
 * hides other apps' sessions without it).
 */
internal fun activePlayerSessions(context: Context): List<PlayerSession>? =
    activeControllers(context)?.map(::ControllerSession)

/**
 * This app's own session when it is the one a person means ([mostRelevant], the same pick seeking and the state
 * reports use), else null: another app's session is in use, or media access is not granted.
 */
internal fun ownRelevantController(context: Context): MediaController? = activeControllers(context)
    ?.let { controllers -> mostRelevant(controllers) { playbackOf(it.playbackState) } }
    ?.takeIf { it.packageName == context.packageName }

/** Runs [action] on [controller] through its `TransportControls`. */
internal fun transport(controller: MediaController, action: TransportAction) {
    val controls = controller.transportControls
    when (action) {
        TransportAction.PLAY -> controls.play()
        TransportAction.PAUSE -> controls.pause()
        TransportAction.STOP -> controls.stop()
        TransportAction.NEXT -> controls.skipToNext()
        TransportAction.PREVIOUS -> controls.skipToPrevious()
    }
}

private fun activeControllers(context: Context): List<MediaController>? = try {
    context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(context, MediaSessionListener::class.java))
} catch (_: SecurityException) {
    null
}
