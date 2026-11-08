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
import com.portoperatingsystem.tv.state.Answers
import com.portoperatingsystem.tv.state.CommandOutcome
import com.portoperatingsystem.tv.state.FailureCode
import java.io.File

/**
 * The slideshow [PlaybackService] shows: a playlist of images, each shown for its duration. Showing one downloads
 * every image before the first shows, so it never stalls; adding and removing images change the slideshow on screen.
 * Without loop the player pauses just before the last image ends, which keeps that image up; with loop
 * it starts over.
 *
 * The service owns the player, the downloads and the files; [Owner] is what this needs from it. Every command is
 * answered once through [answers]: when it acted, or why it could not.
 */
internal class Slideshow(
    private val downloads: FileDownloads,
    private val requests: Requests,
    private val answers: Answers,
    private val owner: Owner
) {
    /** What the slideshow needs from the service that plays it. */
    interface Owner {
        /** The service's player, or null once it is gone. */
        val player: Player?

        /** The downloaded file behind each item, by media id. */
        val files: MutableMap<String, File>

        /**
         * Replaces whatever plays with [playlist], a slideshow that starts over when it [loop]s. The command is
         * answered with [acted] once the first image shows.
         */
        fun startSlideshow(playlist: Playlist, loop: Boolean, acted: CommandOutcome.Acted)

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
            remove(request.positions, request.commandId)
            return
        }
        val images = parts.accept(request.kind, request.part, request.images) ?: return
        if (request.kind == SlideshowRequest.KIND_SHOW) {
            show(images, request)
        } else {
            add(images, request.position, request.commandId)
        }
    }

    /** Called whenever the service replaces what plays, with whether it is now this slideshow. */
    fun replaced(slideshow: Boolean, loop: Boolean) {
        showing = slideshow
        loops = loop
        holdLast()
    }

    private fun show(images: List<ImageFile>, request: SlideshowRequest) {
        val id = requests.next()
        answers.begin(request.commandId)
        downloads.downloadAll(images.map { it.url to it.mimeType }) { saved, failed ->
            when {
                // A newer command already answered this one as replaced.
                !requests.isLatest(id) || owner.player == null -> saved?.forEach(File::delete)
                saved == null -> {
                    answers.fail(request.commandId, FailureCode.DOWNLOAD_FAILED, downloadFailure(failed))
                    owner.stopUnlessPlaying()
                }
                else -> {
                    imageMillis = request.secondsPerImage * MILLIS
                    owner.startSlideshow(
                        Playlist.of(saved, images.map { it.mimeType }, request.title, imageMillis),
                        request.loop,
                        CommandOutcome.Acted(request.secondsPerImage, request.loop, saved.size)
                    )
                }
            }
        }
    }

    // Inserts the images once all are downloaded, if the same slideshow still shows and has room for them.
    private fun add(images: List<ImageFile>, position: Int?, commandId: String?) {
        if (refuseAdd(images.size, commandId)) return
        val id = requests.current()
        downloads.downloadAll(images.map { it.url to it.mimeType }) { saved, failed ->
            when {
                owner.player == null -> saved?.forEach(File::delete)
                saved == null -> {
                    answers.fail(commandId, FailureCode.DOWNLOAD_FAILED, downloadFailure(failed))
                    owner.stopUnlessPlaying()
                }
                !requests.isLatest(id) -> {
                    saved.forEach(File::delete)
                    answers.fail(commandId, FailureCode.REPLACED, "the slideshow was replaced while images downloaded")
                    owner.stopUnlessPlaying()
                }
                refuseAdd(saved.size, commandId) -> saved.forEach(File::delete)
                else -> insert(saved, images, position, commandId)
            }
        }
    }

    // Answers and returns true when an add of [count] images cannot happen now.
    private fun refuseAdd(count: Int, commandId: String?): Boolean {
        val player = owner.player
        val current = player?.mediaItemCount ?: 0
        val refusal = when {
            player == null || !showing -> FailureCode.NO_SLIDESHOW to "no slideshow is showing"
            current + count > MAX_SLIDESHOW_IMAGES ->
                FailureCode.TOO_MANY_IMAGES to
                    "adding $count images to the $current showing would pass the limit of $MAX_SLIDESHOW_IMAGES"
            else -> return false
        }
        Log.w(TAG, "add-images refused: ${refusal.second}")
        answers.fail(commandId, refusal.first, refusal.second)
        owner.stopUnlessPlaying()
        return true
    }

    private fun insert(saved: List<File>, images: List<ImageFile>, position: Int?, commandId: String?) {
        val player = checkNotNull(owner.player)
        val count = player.mediaItemCount
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
        val added = Playlist.of(saved, images.map { it.mimeType }, title, imageMillis)
        owner.files.putAll(added.files)
        player.addMediaItems(position?.let { (it - 1).coerceAtMost(count) } ?: count, added.items)
        answers.answer(commandId, acted(count + saved.size))
    }

    // Removes the images at the 1-based positions, the last first so the others keep their place.
    private fun remove(positions: List<Int>, commandId: String?) {
        val player = owner.player
        val count = player?.mediaItemCount ?: 0
        val past = positions.firstOrNull { it > count }
        if (player == null || !showing || past != null) {
            val failure = if (player == null || !showing) {
                FailureCode.NO_SLIDESHOW to "no slideshow is showing"
            } else {
                FailureCode.POSITION_OUT_OF_RANGE to "position $past is past the end; the slideshow has $count images"
            }
            Log.w(TAG, "remove-images refused: ${failure.second}")
            answers.fail(commandId, failure.first, failure.second)
            owner.stopUnlessPlaying()
            return
        }
        positions.sortedDescending().forEach { position ->
            owner.files.remove(player.getMediaItemAt(position - 1).mediaId)?.delete()
            player.removeMediaItem(position - 1)
        }
        if (player.mediaItemCount == 0) player.stop()
        answers.answer(commandId, acted(count - positions.size))
    }

    private fun acted(queueLength: Int) = CommandOutcome.Acted((imageMillis / MILLIS).toInt(), loops, queueLength)

    private fun downloadFailure(index: Int) =
        if (index >= 0) "image ${index + 1} could not be downloaded" else "an image could not be downloaded"

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
