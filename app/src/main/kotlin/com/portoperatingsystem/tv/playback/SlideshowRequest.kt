package com.portoperatingsystem.tv.playback

import android.content.Intent
import com.portoperatingsystem.tv.protocol.command.Command
import com.portoperatingsystem.tv.protocol.command.ImageFile
import com.portoperatingsystem.tv.protocol.command.Part
import com.portoperatingsystem.tv.protocol.command.SlideshowCommand

/** A slideshow command, or one part of it, as an intent to [PlaybackService] carries it. */
internal class SlideshowRequest(intent: Intent) {
    val kind: String = checkNotNull(intent.getStringExtra(EXTRA_KIND)) { "slideshow request has no kind" }
    val part =
        Part(
            intent.getStringExtra(EXTRA_SET).orEmpty(),
            intent.getIntExtra(EXTRA_INDEX, 0),
            intent.getIntExtra(EXTRA_COUNT, 1)
        )
    val images: List<ImageFile> = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        .zip(intent.getStringArrayListExtra(EXTRA_MIME_TYPES).orEmpty(), ::ImageFile)
    val title: String? = intent.getStringExtra(EXTRA_TITLE)
    val secondsPerImage: Int = intent.getIntExtra(EXTRA_SECONDS, 0)
    val loop: Boolean = intent.getBooleanExtra(EXTRA_LOOP, false)
    val position: Int? = intent.getIntExtra(EXTRA_POSITION, 0).takeIf { it > 0 }
    val positions: List<Int> = intent.getIntArrayExtra(EXTRA_POSITIONS)?.toList().orEmpty()

    companion object {
        const val KIND_SHOW = "show"
        const val KIND_ADD = "add"
        const val KIND_REMOVE = "remove"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SET = "set"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_MIME_TYPES = "mimeTypes"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SECONDS = "secondsPerImage"
        private const val EXTRA_LOOP = "loop"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_POSITIONS = "positions"

        /** Puts [command] into [intent], for [SlideshowRequest] to read back. */
        fun fill(intent: Intent, command: SlideshowCommand): Intent = when (command) {
            is Command.ShowImages -> intent.putExtra(EXTRA_KIND, KIND_SHOW)
                .withImages(command.part, command.images)
                .putExtra(EXTRA_TITLE, command.title)
                .putExtra(EXTRA_SECONDS, command.secondsPerImage)
                .putExtra(EXTRA_LOOP, command.loop)
            is Command.AddImages -> intent.putExtra(EXTRA_KIND, KIND_ADD)
                .withImages(command.part, command.images)
                .putExtra(EXTRA_POSITION, command.position ?: 0)
            is Command.RemoveImages -> intent.putExtra(EXTRA_KIND, KIND_REMOVE)
                .putExtra(EXTRA_POSITIONS, command.positions.toIntArray())
        }

        private fun Intent.withImages(part: Part, images: List<ImageFile>): Intent = putExtra(EXTRA_SET, part.set)
            .putExtra(EXTRA_INDEX, part.index)
            .putExtra(EXTRA_COUNT, part.count)
            .putStringArrayListExtra(EXTRA_URLS, ArrayList(images.map { it.url }))
            .putStringArrayListExtra(EXTRA_MIME_TYPES, ArrayList(images.map { it.mimeType }))
    }
}
