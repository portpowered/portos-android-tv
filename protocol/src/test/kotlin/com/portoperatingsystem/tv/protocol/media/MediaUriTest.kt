package com.portoperatingsystem.tv.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaUriTest {
    @Test
    fun parsesCanonicalIdentity() {
        assertEquals(
            MediaUri("youtube", "video", "video", "dQw4w9WgXcQ"),
            MediaUri.parse("portos-media://portoperatingsystem.com/youtube/video/video/dQw4w9WgXcQ")
        )
    }

    @Test
    fun acceptsLegacyAuthorityAndDecodesId() {
        assertEquals(
            MediaUri("spotify", "audio", "track", "a b"),
            MediaUri.parse("portos-media://portpowered.com/spotify/audio/track/a%20b")
        )
    }

    @Test
    fun rejectsMalformedIdentities() {
        listOf(
            "https://portoperatingsystem.com/youtube/video/video/x",
            "portos-media://example.com/youtube/video/video/x",
            "portos-media://portoperatingsystem.com/youtube/video/x",
            "portos-media://portoperatingsystem.com/youtube/video/video/x/extra",
            "portos-media://portoperatingsystem.com/youtube/video/video/x?t=1",
            "portos-media://portoperatingsystem.com/youtube/video/video/x#t",
            "portos-media://portoperatingsystem.com/youtube//video/x",
            "not a uri"
        ).forEach { assertNull(MediaUri.parse(it), it) }
    }
}
