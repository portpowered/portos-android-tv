package com.portoperatingsystem.tv.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Plays `play-uri` audio. The [MediaSession] makes the playback visible to the platform like any other media app:
 * media keys (play, pause, stop, next, previous) reach it, [com.portoperatingsystem.tv.state.MediaSessionListener]
 * reports its state, and Media3 keeps the service in the foreground, with a notification, while it plays.
 * The service stops itself when playback ends, fails or is stopped.
 */
internal class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(StopWhenDone(::stopSelf))
        session = MediaSession.Builder(this, player).build()
    }

    /** The player behind the session, for tests. */
    internal val player: Player? get() = session?.player

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.takeIf { it.action == ACTION_PLAY_URI }?.let(::PlayRequest)
        val player = session?.player
        if (request != null && player != null) {
            val metadata = MediaMetadata.Builder().setTitle(request.title).build()
            val item = MediaItem.Builder().setUri(request.uri).setMediaMetadata(metadata).build()
            player.setMediaItem(item, request.offsetMillis)
            player.prepare()
            player.play()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Ends the service once there is nothing left to play: the clip finished, failed, or was stopped. */
    private class StopWhenDone(private val stop: () -> Unit) : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) stop()
        }

        override fun onPlayerError(error: PlaybackException) = stop()
    }

    /** The audio to play, as carried by the intent [playIntent] builds. */
    private class PlayRequest(intent: Intent) {
        val uri: String = checkNotNull(intent.getStringExtra(EXTRA_URI)) { "play request has no uri" }
        val title: String? = intent.getStringExtra(EXTRA_TITLE)
        val offsetMillis: Long = intent.getLongExtra(EXTRA_OFFSET_MILLIS, 0L)
    }

    companion object {
        private const val ACTION_PLAY_URI = "com.portoperatingsystem.tv.action.PLAY_URI"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_OFFSET_MILLIS = "offsetMillis"

        /** The intent that starts playback of [uri] from [offsetMillis]. */
        fun playIntent(context: Context, uri: String, title: String?, offsetMillis: Long): Intent =
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY_URI)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_OFFSET_MILLIS, offsetMillis)
    }
}
