package com.portoperatingsystem.tv.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.portoperatingsystem.tv.protocol.command.Command
import com.portoperatingsystem.tv.protocol.command.OwnPlayerCommand
import com.portoperatingsystem.tv.protocol.command.SlideshowCommand
import com.portoperatingsystem.tv.ui.PlayerActivity
import java.io.File

/**
 * Plays `play-uri` audio, the files `play-video` and `play-audio` deliver, and the [Slideshow] that `show-images`
 * builds and `add-images` and `remove-images` change. The [MediaSession] makes the playback visible to the platform
 * like any other media app: media keys (play, pause, stop, next, previous) reach it,
 * [com.portoperatingsystem.tv.state.MediaSessionListener] reports its state and queue, and Media3 keeps the service in
 * the foreground, with a notification, while it plays. Starting playback also opens [PlayerActivity], which shows this
 * player. The service stops itself when playback ends, fails or is stopped, and that closes the screen.
 *
 * A file is downloaded once into the app cache and the local copy plays, so a loop never fetches again. A new request
 * replaces the items and deletes the previous files; destroying the service deletes them all.
 */
internal class PlaybackService :
    MediaSessionService(),
    Slideshow.Owner {
    private var session: MediaSession? = null
    private lateinit var downloads: FileDownloads

    @VisibleForTesting
    internal lateinit var slideshow: Slideshow
        private set
    private val requests = Requests()

    override val files = mutableMapOf<String, File>()

    override fun onCreate() {
        super.onCreate()
        downloads = FileDownloads(File(cacheDir, MEDIA_DIRECTORY))
        // A previous process may have been killed with files still on disk.
        downloads.deleteAll()
        slideshow = Slideshow(downloads, requests, this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes(video = false), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(StopWhenDone(::stopSelf))
        player.addListener(HoldLast(slideshow))
        session = MediaSession.Builder(this, player).build()
        currentPlayer = player
    }

    /** The player behind the session, null once the service is destroyed. */
    override val player: Player? get() = session?.player

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_URI -> playUri(PlayRequest(intent))
            ACTION_PLAY_FILE -> playFile(FileRequest(intent))
            ACTION_SLIDESHOW -> slideshow.accept(SlideshowRequest(intent))
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        requests.next()
        currentPlayer = null
        downloads.close()
        files.clear()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun playUri(request: PlayRequest) {
        requests.next()
        val metadata = MediaMetadata.Builder().setTitle(request.title).build()
        val item = MediaItem.Builder().setUri(request.uri).setMediaMetadata(metadata).build()
        start(Playlist(listOf(item), emptyMap()), request.offsetMillis, video = false, loop = false)
    }

    private fun playFile(request: FileRequest) {
        val id = requests.next()
        downloads.download(request.url, request.mimeType) { file ->
            when {
                !requests.isLatest(id) || session == null -> file?.delete()
                file == null -> stopSelf()
                else -> start(
                    Playlist.of(listOf(file), listOf(request.mimeType), request.title, imageMillis = null),
                    0L,
                    request.video,
                    request.loop
                )
            }
        }
    }

    override fun startSlideshow(playlist: Playlist, loop: Boolean) =
        start(playlist, 0L, video = true, loop = loop, slideshow = true)

    // A command that found nothing to change must not keep alive a service that plays nothing.
    override fun stopUnlessPlaying() {
        if ((player?.mediaItemCount ?: 0) == 0) stopSelf()
    }

    // Replaces whatever plays: the items, how they loop, what the audio is for, the screen, and the previous files.
    private fun start(
        playlist: Playlist,
        offsetMillis: Long,
        video: Boolean,
        loop: Boolean,
        slideshow: Boolean = false
    ) {
        val player = player ?: return
        player.setAudioAttributes(attributes(video), true)
        player.repeatMode = when {
            !loop -> Player.REPEAT_MODE_OFF
            slideshow -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_ONE
        }
        player.setMediaItems(playlist.items, 0, offsetMillis)
        player.prepare()
        player.play()
        files.values.forEach(File::delete)
        files.clear()
        files.putAll(playlist.files)
        this.slideshow.replaced(slideshow, loop)
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

    /** Keeps the last image of a slideshow on screen whenever the current item or the playlist changes. */
    private class HoldLast(private val slideshow: Slideshow) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                slideshow.holdLast()
            }
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
        val title: String? = intent.getStringExtra(EXTRA_TITLE)
    }

    companion object {
        private const val ACTION_PLAY_URI = "com.portoperatingsystem.tv.action.PLAY_URI"
        private const val ACTION_PLAY_FILE = "com.portoperatingsystem.tv.action.PLAY_FILE"
        private const val ACTION_SLIDESHOW = "com.portoperatingsystem.tv.action.SLIDESHOW"
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

        /** The intent that hands [command] to the player. */
        fun intent(context: Context, command: OwnPlayerCommand): Intent = when (command) {
            is Command.PlayFile -> playFileIntent(context, command)
            is SlideshowCommand -> slideshowIntent(context, command)
        }

        /** The intent that downloads [file] and plays it, video or audio, repeating it when it loops. */
        fun playFileIntent(context: Context, file: Command.PlayFile): Intent =
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY_FILE)
                .putExtra(EXTRA_URI, file.url)
                .putExtra(EXTRA_MIME_TYPE, file.mimeType)
                .putExtra(EXTRA_VIDEO, file.video)
                .putExtra(EXTRA_LOOP, file.loop)
                .putExtra(EXTRA_TITLE, file.title)

        /** The intent that hands one slideshow command, or one part of it, to the player. */
        fun slideshowIntent(context: Context, command: SlideshowCommand): Intent =
            SlideshowRequest.fill(Intent(context, PlaybackService::class.java).setAction(ACTION_SLIDESHOW), command)
    }
}
