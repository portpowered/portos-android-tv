package com.portoperatingsystem.tv.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.portoperatingsystem.tv.protocol.command.ImageFile
import com.portoperatingsystem.tv.protocol.command.MAX_SLIDESHOW_IMAGES
import java.io.File

/**
 * The slideshow [PlaybackService] shows: a playlist of images, each shown for its duration. Showing one downloads
 * every image before the first shows, so it never stalls; adding and removing images change the slideshow on screen.
 * Without loop the player pauses at the end of the last image instead of ending, which keeps that image up; with loop
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
     * Pauses at the end of the last image of a slideshow that does not loop, which keeps that image on screen; the
     * service calls it whenever the current item or the playlist changes. Pausing at the end of an item is a Media3
     * opt-in API; the pinned 1.5.1 is what it matches.
     */
    @OptIn(UnstableApi::class)
    fun holdLast() {
        val player = owner.player as? ExoPlayer ?: return
        player.pauseAtEndOfMediaItems =
            showing &&
            !loops &&
            player.currentMediaItemIndex == player.mediaItemCount - 1
    }

    private companion object {
        const val MILLIS = 1000L
        const val TAG = "PortOsPlayback"
    }
}
