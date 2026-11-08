package com.portoperatingsystem.tv.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import java.io.File

/** Items to play and the downloaded file behind each, by media id, which goes when its item goes. */
internal class Playlist(val items: List<MediaItem>, val files: Map<String, File>) {
    companion object {
        /**
         * One item per downloaded file, titled [title]; an image shows for [imageMillis], other files play. An image's
         * duration is a Media3 opt-in API; the pinned 1.5.1 is what it matches.
         */
        @OptIn(UnstableApi::class)
        fun of(saved: List<File>, mimeTypes: List<String>, title: String?, imageMillis: Long?): Playlist {
            val metadata = MediaMetadata.Builder().setTitle(title).build()
            val items = saved.zip(mimeTypes) { file, mimeType ->
                val builder = MediaItem.Builder()
                    .setMediaId(file.name)
                    .setUri(Uri.fromFile(file))
                    .setMimeType(mimeType)
                    .setMediaMetadata(metadata)
                imageMillis?.let(builder::setImageDurationMs)
                builder.build()
            }
            return Playlist(items, saved.associateBy { it.name })
        }
    }
}

/**
 * Counts the requests that replace what plays, so a download that finishes after a newer request began is thrown
 * away.
 */
internal class Requests {
    private var latest = 0

    /** Starts a request that replaces what plays and returns its number. */
    fun next(): Int = ++latest

    /** The number of the request that last replaced what plays. */
    fun current(): Int = latest

    /** Whether [id] still names the request that last replaced what plays. */
    fun isLatest(id: Int): Boolean = id == latest
}
