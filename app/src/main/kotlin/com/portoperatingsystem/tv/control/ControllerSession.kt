package com.portoperatingsystem.tv.control

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.portoperatingsystem.tv.state.MediaSessionListener
import com.portoperatingsystem.tv.state.Playback
import com.portoperatingsystem.tv.state.currentPosition
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
internal fun activePlayerSessions(context: Context): List<PlayerSession>? = try {
    context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(context, MediaSessionListener::class.java))
        .map(::ControllerSession)
} catch (_: SecurityException) {
    null
}
