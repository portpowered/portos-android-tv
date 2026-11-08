package com.portoperatingsystem.tv.playback

import com.portoperatingsystem.tv.protocol.command.ImageFile
import com.portoperatingsystem.tv.protocol.command.Part

/**
 * Puts a slideshow message back together from the FCM pushes it was split into. Parts arrive in any order, and the
 * parts of two messages can interleave; [accept] returns every image of a set, in order, once it holds all of its
 * parts, and null until then. Each kind (show-images, add-images) keeps its last [keep] unfinished sets; a completed
 * set drops the ones begun before it, which a newer command has overtaken.
 */
internal class ImageParts(private val keep: Int = KEEP) {
    private class Pending(count: Int) {
        val parts = arrayOfNulls<List<ImageFile>>(count)
    }

    private val pending = mutableMapOf<String, LinkedHashMap<String, Pending>>()

    fun accept(kind: String, part: Part, images: List<ImageFile>): List<ImageFile>? {
        val sets = pending.getOrPut(kind) { LinkedHashMap() }
        val set = sets[part.set]?.takeIf { it.parts.size == part.count } ?: Pending(part.count).also {
            sets.remove(part.set)
            sets[part.set] = it
            if (sets.size > keep) sets.remove(sets.keys.first())
        }
        set.parts[part.index] = images
        if (set.parts.any { it == null }) return null
        val begunBefore = sets.keys.takeWhile { it != part.set }
        begunBefore.forEach(sets::remove)
        sets.remove(part.set)
        return set.parts.flatMap { checkNotNull(it) }
    }

    private companion object {
        const val KEEP = 3
    }
}
