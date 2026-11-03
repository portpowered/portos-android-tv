package com.portoperatingsystem.tv.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaIdentityTest {
    private val youtube = DeepLinks.YOUTUBE_PACKAGE
    private val spotify = DeepLinks.SPOTIFY_PACKAGE

    @Test
    fun namesTheAppsPortOsKnows() {
        assertEquals("YOUTUBE", MediaIdentity.provider(youtube))
        assertEquals("SPOTIFY", MediaIdentity.provider(spotify))
        assertNull(MediaIdentity.provider("com.example.player"))
    }

    @Test
    fun spotifyUrisBecomeAudioMediaIds() {
        assertEquals(
            "portos-media://portoperatingsystem.com/spotify/audio/track/4cOdK2wGLETKBW3PvgPWqT",
            MediaIdentity.mediaId(spotify, listOf("spotify:track:4cOdK2wGLETKBW3PvgPWqT"))
        )
        assertEquals(
            "portos-media://portoperatingsystem.com/spotify/audio/episode/Ab12",
            MediaIdentity.mediaId(spotify, listOf("https://open.spotify.com/episode/Ab12?si=x"))
        )
    }

    @Test
    fun youtubeVideoIdsAndWatchUrlsBecomeVideoMediaIds() {
        val expected = "portos-media://portoperatingsystem.com/youtube/video/video/dQw4w9WgXcQ"
        assertEquals(expected, MediaIdentity.mediaId(youtube, listOf("dQw4w9WgXcQ")))
        assertEquals(expected, MediaIdentity.mediaId(youtube, listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        assertEquals(expected, MediaIdentity.mediaId(youtube, listOf("https://youtu.be/dQw4w9WgXcQ")))
    }

    @Test
    fun theFirstCandidateThatProvesTheMediaWins() {
        assertEquals(
            "portos-media://portoperatingsystem.com/youtube/video/video/dQw4w9WgXcQ",
            MediaIdentity.mediaId(youtube, listOf("not an id", "dQw4w9WgXcQ"))
        )
    }

    @Test
    fun anythingElseIsLeftOutNotGuessed() {
        assertNull(MediaIdentity.mediaId(youtube, listOf("PLxyz", "some title", "")))
        assertNull(MediaIdentity.mediaId(spotify, listOf("spotify:user:abc", "dQw4w9WgXcQ")))
        assertNull(MediaIdentity.mediaId("com.example.player", listOf("spotify:track:abc", "dQw4w9WgXcQ")))
        assertNull(MediaIdentity.mediaId(youtube, emptyList()))
    }

    @Test
    fun theIdsAreTheOnesPlayMediaAccepts() {
        val id = MediaIdentity.mediaId(spotify, listOf("spotify:track:4cOdK2wGLETKBW3PvgPWqT"))!!
        assertEquals(
            LaunchTarget("spotify:track:4cOdK2wGLETKBW3PvgPWqT", spotify),
            DeepLinks.resolve(MediaUri.parse(id)!!)
        )
    }
}
