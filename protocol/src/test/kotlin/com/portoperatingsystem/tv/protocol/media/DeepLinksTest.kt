package com.portoperatingsystem.tv.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinksTest {
    private val youtubeTv = "com.google.android.youtube.tv"

    @Test
    fun youtubeVideoOpensInYoutubeTv() {
        assertEquals(
            LaunchTarget("https://www.youtube.com/watch?v=dQw4w9WgXcQ", youtubeTv),
            DeepLinks.resolve(MediaUri("youtube", "video", "video", "dQw4w9WgXcQ"))
        )
    }

    @Test
    fun youtubeChannelOpensChannelPage() {
        assertEquals(
            LaunchTarget("https://www.youtube.com/channel/UC123", youtubeTv),
            DeepLinks.resolve(MediaUri("youtube", "video", "channel", "UC123"))
        )
    }

    @Test
    fun youtubePlaylistOpensPlaylistPage() {
        assertEquals(
            LaunchTarget("https://www.youtube.com/playlist?list=PLabc_-1", youtubeTv),
            DeepLinks.resolve(MediaUri("youtube", "video", "playlist", "PLabc_-1"))
        )
    }

    @Test
    fun spotifyUsesItsUriScheme() {
        assertEquals(
            LaunchTarget("spotify:track:abc", "com.spotify.tv.android"),
            DeepLinks.resolve(MediaUri("spotify", "audio", "track", "abc"))
        )
    }

    @Test
    fun unsupportedMediaResolvesToNothing() {
        assertNull(DeepLinks.resolve(MediaUri("youtube", "video", "playlist", "PL1&x=1")))
        assertNull(DeepLinks.resolve(MediaUri("youtube", "video", "user", "abc")))
        assertNull(DeepLinks.resolve(MediaUri("youtube", "video", "video", "bad id&x=1")))
        assertNull(DeepLinks.resolve(MediaUri("spotify", "audio", "user", "abc")))
        assertNull(DeepLinks.resolve(MediaUri("netflix", "video", "title", "1")))
    }
}
