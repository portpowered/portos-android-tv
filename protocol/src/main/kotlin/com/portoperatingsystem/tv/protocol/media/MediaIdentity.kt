package com.portoperatingsystem.tv.protocol.media

/**
 * Names what a MediaSession is playing in Port OS terms. The inverse of [DeepLinks]: where that turns a media id into
 * an app launch, this turns an app's session metadata into the media id `play-media` accepts. Anything it cannot
 * derive with certainty is null, never a guess.
 */
public object MediaIdentity {
    private const val AUTHORITY = "portos-media://portoperatingsystem.com"
    private const val SPOTIFY_KINDS = "(track|album|artist|playlist|show|episode)"
    private val SPOTIFY_URI = Regex("^spotify:$SPOTIFY_KINDS:([A-Za-z0-9]+)$")
    private const val SPOTIFY_SITE = "^https://open\\.spotify\\.com/(?:intl-[a-z]+/)?"
    private val SPOTIFY_URL = Regex("$SPOTIFY_SITE$SPOTIFY_KINDS/([A-Za-z0-9]+)(?:\\?.*)?$")
    private val YOUTUBE_WATCH =
        Regex("^https://(?:www\\.|m\\.)?youtube\\.com/watch\\?(?:.*&)?v=([A-Za-z0-9_-]{11})(?:&.*)?$")
    private val YOUTUBE_SHORT = Regex("^https://youtu\\.be/([A-Za-z0-9_-]{11})(?:\\?.*)?$")
    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** The Port OS `MediaProvider` value for the app with [packageName], or null for an app Port OS has no name for. */
    public fun provider(packageName: String): String? = when (packageName) {
        DeepLinks.YOUTUBE_PACKAGE -> "YOUTUBE"
        DeepLinks.SPOTIFY_PACKAGE -> "SPOTIFY"
        else -> null
    }

    /**
     * The full Port OS media id for what [packageName]'s session plays, from the identifiers its metadata exposes
     * ([candidates], such as `METADATA_KEY_MEDIA_ID` and `METADATA_KEY_MEDIA_URI`). The first candidate that names
     * the media unambiguously wins; null when none does.
     */
    public fun mediaId(packageName: String, candidates: List<String>): String? = candidates.firstNotNullOfOrNull {
        when (packageName) {
            DeepLinks.SPOTIFY_PACKAGE -> spotify(it.trim())
            DeepLinks.YOUTUBE_PACKAGE -> youtube(it.trim())
            else -> null
        }
    }

    private fun spotify(value: String): String? {
        val match = SPOTIFY_URI.matchEntire(value) ?: SPOTIFY_URL.matchEntire(value) ?: return null
        val (kind, id) = match.destructured
        return "$AUTHORITY/spotify/audio/$kind/$id"
    }

    // A bare 11 character value is YouTube's video id, which is what its sessions expose as the media id.
    private fun youtube(value: String): String? {
        val id = YOUTUBE_WATCH.matchEntire(value)?.groupValues?.get(1)
            ?: YOUTUBE_SHORT.matchEntire(value)?.groupValues?.get(1)
            ?: value.takeIf { YOUTUBE_ID.matches(it) }
            ?: return null
        return "$AUTHORITY/youtube/video/video/$id"
    }
}
