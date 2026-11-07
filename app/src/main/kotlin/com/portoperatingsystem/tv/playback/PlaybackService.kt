package com.portoperatingsystem.tv.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.portoperatingsystem.tv.ui.PlayerActivity
import java.io.File

/**
 * Plays `play-uri` audio and the files `play-video` and `play-audio` deliver. The [MediaSession] makes the playback
 * visible to the platform like any other media app: media keys (play, pause, stop, next, previous) reach it,
 * [com.portoperatingsystem.tv.state.MediaSessionListener] reports its state, and Media3 keeps the service in the
 * foreground, with a notification, while it plays. Starting playback also opens [PlayerActivity], which shows this
 * player. The service stops itself when playback ends, fails or is stopped, and that closes the screen.
 *
 * A file is downloaded once into the app cache and the local copy plays, so a loop never fetches again. A new request
 * replaces the item and deletes the previous file; destroying the service deletes them all.
 */
internal class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var downloads: FileDownloads
    private var current: File? = null

    // Counts requests, so a download that finishes after a newer request began is thrown away.
    private var latest = 0

    override fun onCreate() {
        super.onCreate()
        downloads = FileDownloads(File(cacheDir, MEDIA_DIRECTORY))
        // A previous process may have been killed with files still on disk.
        downloads.deleteAll()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes(video = false), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(StopWhenDone(::stopSelf))
        session = MediaSession.Builder(this, player).build()
        currentPlayer = player
    }

    /** The player behind the session, for tests. */
    internal val player: Player? get() = session?.player

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_URI -> playUri(PlayRequest(intent))
            ACTION_PLAY_FILE -> playFile(FileRequest(intent))
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        latest++
        currentPlayer = null
        downloads.close()
        current = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun playUri(request: PlayRequest) {
        latest++
        val metadata = MediaMetadata.Builder().setTitle(request.title).build()
        val item = MediaItem.Builder().setUri(request.uri).setMediaMetadata(metadata).build()
        start(item, request.offsetMillis, video = false, loop = false, file = null)
    }

    private fun playFile(request: FileRequest) {
        val id = ++latest
        downloads.download(request.url, request.mimeType) { file -> downloaded(id, request, file) }
    }

    private fun downloaded(id: Int, request: FileRequest, file: File?) {
        when {
            id != latest || session == null -> file?.delete()
            file == null -> stopSelf()
            else -> {
                val item = MediaItem.Builder().setUri(Uri.fromFile(file)).setMimeType(request.mimeType).build()
                start(item, 0L, request.video, request.loop, file)
            }
        }
    }

    // Replaces whatever plays: the item, how it loops, what the audio is for, the screen, and the previous file.
    private fun start(item: MediaItem, offsetMillis: Long, video: Boolean, loop: Boolean, file: File?) {
        val player = session?.player ?: return
        player.setAudioAttributes(attributes(video), true)
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaItem(item, offsetMillis)
        player.prepare()
        player.play()
        current?.delete()
        current = file
        startActivity(PlayerActivity.intent(this, video))
    }

    private fun attributes(video: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (video) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** Ends the service once there is nothing left to play: the clip finished, failed, or was stopped. */
    private class StopWhenDone(private val stop: () -> Unit) : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) stop()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback failed: ${error.errorCodeName}")
            stop()
        }
    }

    /** The audio to play, as carried by the intent [playIntent] builds. */
    private class PlayRequest(intent: Intent) {
        val uri: String = checkNotNull(intent.getStringExtra(EXTRA_URI)) { "play request has no uri" }
        val title: String? = intent.getStringExtra(EXTRA_TITLE)
        val offsetMillis: Long = intent.getLongExtra(EXTRA_OFFSET_MILLIS, 0L)
    }

    /** The file to download and play, as carried by the intent [playFileIntent] builds. */
    private class FileRequest(intent: Intent) {
        val url: String = checkNotNull(intent.getStringExtra(EXTRA_URI)) { "play request has no url" }
        val mimeType: String = checkNotNull(intent.getStringExtra(EXTRA_MIME_TYPE)) { "play request has no mimeType" }
        val video: Boolean = intent.getBooleanExtra(EXTRA_VIDEO, false)
        val loop: Boolean = intent.getBooleanExtra(EXTRA_LOOP, false)
    }

    companion object {
        private const val ACTION_PLAY_URI = "com.portoperatingsystem.tv.action.PLAY_URI"
        private const val ACTION_PLAY_FILE = "com.portoperatingsystem.tv.action.PLAY_FILE"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_OFFSET_MILLIS = "offsetMillis"
        private const val EXTRA_MIME_TYPE = "mimeType"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_LOOP = "loop"
        private const val MEDIA_DIRECTORY = "media"
        private const val TAG = "PortOsPlayback"

        /**
         * The player the running service owns, or null when there is none. [PlayerActivity] shows it in the same
         * process; both read it on the main thread.
         */
        @Volatile
        var currentPlayer: Player? = null
            private set

        /** The intent that starts playback of [uri] from [offsetMillis]. */
        fun playIntent(context: Context, uri: String, title: String?, offsetMillis: Long): Intent =
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY_URI)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_OFFSET_MILLIS, offsetMillis)

        /** The intent that downloads [url] and plays the file, [video] or audio, repeating it when [loop]. */
        fun playFileIntent(context: Context, url: String, mimeType: String, video: Boolean, loop: Boolean): Intent =
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY_FILE)
                .putExtra(EXTRA_URI, url)
                .putExtra(EXTRA_MIME_TYPE, mimeType)
                .putExtra(EXTRA_VIDEO, video)
                .putExtra(EXTRA_LOOP, loop)
    }
}
