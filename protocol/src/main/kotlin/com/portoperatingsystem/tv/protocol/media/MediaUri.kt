package com.portoperatingsystem.tv.protocol.media

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A canonical Port OS media identity:
 * `portos-media://portoperatingsystem.com/<provider>/<media-type>/<resource-kind>/<resource-id>`.
 */
public data class MediaUri(
    val provider: String,
    val mediaType: String,
    val resourceKind: String,
    val resourceId: String
) {
    public companion object {
        private const val SCHEME = "portos-media"
        private val AUTHORITIES = setOf("portoperatingsystem.com", "portpowered.com")
        private const val SEGMENTS = 4
        private const val ID_SEGMENT = 3

        /** Parses [value], or returns null when it is not a well-formed canonical media identity. */
        public fun parse(value: String): MediaUri? {
            val uri = toUri(value) ?: return null
            val segments = uri.rawPath.orEmpty().trim('/').split('/')
            val valid = uri.scheme == SCHEME &&
                uri.rawAuthority in AUTHORITIES &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                segments.size == SEGMENTS &&
                segments.none { it.isEmpty() }
            // The String overload of decode: the Charset one needs Android 13, so older TVs would crash.
            return if (valid) {
                MediaUri(segments[0], segments[1], segments[2], URLDecoder.decode(segments[ID_SEGMENT], "UTF-8"))
            } else {
                null
            }
        }

        private fun toUri(value: String): URI? = try {
            URI(value)
        } catch (_: URISyntaxException) {
            null
        }
    }
}
