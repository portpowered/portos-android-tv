package com.portoperatingsystem.tv.protocol.media

/** What the TV opens for a media identity: an `ACTION_VIEW` of [uri], addressed to [packageName]. */
public data class LaunchTarget(val uri: String, val packageName: String)

/** Maps canonical media identities to the deep links of the TV apps that play them. */
public object DeepLinks {
    /** YouTube's Android TV / Google TV app. A plain https intent resolves to a browser stub on Google TV. */
    public const val YOUTUBE_PACKAGE: String = "com.google.android.youtube.tv"

    /** Spotify's Android TV app. */
    public const val SPOTIFY_PACKAGE: String = "com.spotify.tv.android"

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]+$")
    private val SPOTIFY_ID = Regex("^[A-Za-z0-9]+$")
    private val SPOTIFY_KINDS = setOf("track", "album", "artist", "playlist", "show", "episode")

    /** Returns the launch target for [media], or null when no supported TV app plays it. */
    public fun resolve(media: MediaUri): LaunchTarget? = when (media.provider) {
        "youtube" -> youtube(media)
        "spotify" -> spotify(media)
        else -> null
    }

    private fun youtube(media: MediaUri): LaunchTarget? {
        if (media.mediaType != "video" || !YOUTUBE_ID.matches(media.resourceId)) return null
        return when (media.resourceKind) {
            "video" -> LaunchTarget("https://www.youtube.com/watch?v=${media.resourceId}", YOUTUBE_PACKAGE)
            "channel" -> LaunchTarget("https://www.youtube.com/channel/${media.resourceId}", YOUTUBE_PACKAGE)
            // Verified on a Google TV: the playlist page starts the first item and queues the rest.
            "playlist" -> LaunchTarget("https://www.youtube.com/playlist?list=${media.resourceId}", YOUTUBE_PACKAGE)
            else -> null
        }
    }

    private fun spotify(media: MediaUri): LaunchTarget? {
        val supported = media.resourceKind in SPOTIFY_KINDS && SPOTIFY_ID.matches(media.resourceId)
        return if (supported) {
            LaunchTarget(
                "spotify:${media.resourceKind}:${media.resourceId}",
                SPOTIFY_PACKAGE
            )
        } else {
            null
        }
    }
}
