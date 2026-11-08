package com.portoperatingsystem.tv.playback

import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.portoperatingsystem.tv.protocol.command.ImageFile
import com.portoperatingsystem.tv.protocol.command.MAX_SLIDESHOW_IMAGES
import java.io.File

/**
 * The slideshow [PlaybackService] shows: a playlist of images, each shown for its duration. Showing one downloads
 * every image before the first shows, so it never stalls; adding and removing images change the slideshow on screen.
 * Without loop the player pauses just before the last image ends, which keeps that image up; with loop
 * it starts over.
 *
 * The service owns the player, the downloads and the files; [Owner] is what this needs from it.
 */
internal class Slideshow(
    private val downloads: FileDownloads,
    private val requests: Requests,
    private val owner: Owner
) {
    /** What the slideshow needs from the service that plays it. */
    interface Owner {
        /** The service's player, or null once it is gone. */
        val player: Player?

        /** The downloaded file behind each item, by media id. */
        val files: MutableMap<String, File>

        /** Replaces whatever plays with [playlist], a slideshow that starts over when it [loop]s. */
        fun startSlideshow(playlist: Playlist, loop: Boolean)

        /** Stops the service unless something plays. */
        fun stopUnlessPlaying()
    }

    private val parts = ImageParts()
    private var showing = false
    private var loops = false
    private var imageMillis = 0L

    /** Handles remove-images, and one part of show-images or add-images once the last part of its set arrives. */
    fun accept(request: SlideshowRequest) {
        if (request.kind == SlideshowRequest.KIND_REMOVE) {
            remove(request.positions)
            return
        }
        val images = parts.accept(request.kind, request.part, request.images) ?: return
        if (request.kind == SlideshowRequest.KIND_SHOW) show(images, request) else add(images, request.position)
    }

    /** Called whenever the service replaces what plays, with whether it is now this slideshow. */
    fun replaced(slideshow: Boolean, loop: Boolean) {
        showing = slideshow
        loops = loop
        holdLast()
    }

    private fun show(images: List<ImageFile>, request: SlideshowRequest) {
        val id = requests.next()
        downloads.downloadAll(images.map { it.url to it.mimeType }) { saved ->
            when {
                !requests.isLatest(id) || owner.player == null -> saved?.forEach(File::delete)
                saved == null -> owner.stopUnlessPlaying()
                else -> {
                    imageMillis = request.secondsPerImage * MILLIS
                    owner.startSlideshow(
                        Playlist.of(
                            saved,
                            images.map {
                                it.mimeType
                            },
                            request.title,
                            imageMillis
                        ),
                        request.loop
                    )
                }
            }
        }
    }

    // Inserts the images once all are downloaded, if the same slideshow still shows and has room for them.
    private fun add(images: List<ImageFile>, position: Int?) {
        val id = requests.current()
        downloads.downloadAll(images.map { it.url to it.mimeType }) { saved ->
            val player = owner.player
            val count = player?.mediaItemCount ?: 0
            val fits = saved != null && count + saved.size <= MAX_SLIDESHOW_IMAGES
            val same = requests.isLatest(id) && showing
            if (player == null || !fits || !same) {
                Log.w(TAG, "add-images ignored: no slideshow shows, it changed, it would pass 30, or a download failed")
                saved?.forEach(File::delete)
                owner.stopUnlessPlaying()
            } else {
                val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                val added = Playlist.of(checkNotNull(saved), images.map { it.mimeType }, title, imageMillis)
                owner.files.putAll(added.files)
                player.addMediaItems(position?.let { (it - 1).coerceAtMost(count) } ?: count, added.items)
            }
        }
    }

    // Removes the images at the 1-based positions, the last first so the others keep their place.
    private fun remove(positions: List<Int>) {
        val player = owner.player
        if (player == null || !showing || positions.any { it > player.mediaItemCount }) {
            Log.w(TAG, "remove-images ignored: no slideshow shows or a position is past its end")
            owner.stopUnlessPlaying()
            return
        }
        positions.sortedDescending().forEach { position ->
            owner.files.remove(player.getMediaItemAt(position - 1).mediaId)?.delete()
            player.removeMediaItem(position - 1)
        }
        if (player.mediaItemCount == 0) player.stop()
    }

    /**
     * Pauses a slideshow that does not loop just before its last image ends, which keeps that image on screen. The
     * last item must never reach its end: the player then ends, and the service and the player screen close on that.
     * (Media3's pauseAtEndOfMediaItems does not hold the final item; it ends anyway.) The service calls this whenever
     * the current item or the playlist changes, so the hold always sits on the current last image. Player messages
     * are a Media3 opt-in API; the pinned 1.5.1 is what it matches.
     */
    @OptIn(UnstableApi::class)
    fun holdLast() {
        cancelHold?.invoke()
        cancelHold = null
        holdsAt = null
        val player = owner.player as? ExoPlayer ?: return
        if (!showing || loops || player.mediaItemCount == 0) return
        val at = player.mediaItemCount - 1 to (imageMillis - HOLD_MARGIN_MILLIS).coerceAtLeast(0L)
        val message = player.createMessage { _, _ -> owner.player?.pause() }
            .setPosition(at.first, at.second)
            .setLooper(Looper.getMainLooper())
            .setDeleteAfterDelivery(true)
            .send()
        cancelHold = { message.cancel() }
        holdsAt = at
    }

    // Cancels the pending pause; the message itself is an opt-in type, so only holdLast names it.
    private var cancelHold: (() -> Unit)? = null

    /** The item index and position at which the slideshow pauses on its last image, or null when it does not. */
    @VisibleForTesting
    var holdsAt: Pair<Int, Long>? = null
        private set

    private companion object {
        const val MILLIS = 1000L

        // How long before the end of the last image the slideshow pauses, so the pause lands before the player ends.
        const val HOLD_MARGIN_MILLIS = 250L
        const val TAG = "PortOsPlayback"
    }
}
