package com.portoperatingsystem.tv.state

import com.portoperatingsystem.tv.control.InputCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StateSnapshotTest {
    @Test
    fun claimsCarryEveryKnownField() {
        val snapshot = StateSnapshot(
            sampledAtMs = 100_123,
            playback = Playback.PLAYING,
            nowPlaying = NowPlaying("com.google.android.youtube.tv", "Song", "Artist", 1234),
            volumePercent = 40,
            muted = false,
            backgroundLaunchAllowed = true,
            mediaAccessAllowed = true,
            accessibilityEnabled = true,
            input = InputCapabilities(touch = true, directionalKeys = false)
        )
        assertEquals(
            Json.parseToJsonElement(
                """
                {"iat":100,"sampledAtMs":100123,"jti":"j","reports":{
                  "playbackState":"PLAYING",
                  "nowPlaying":{"active":true,"app":"com.google.android.youtube.tv","name":"Song","artist":"Artist",
                    "positionMs":1234},
                  "volume":40,"muted":false,
                  "input":{"touch":true,"directionalKeys":false},
                  "connectivity":{"backgroundLaunchAllowed":true,"mediaAccessAllowed":true,
                    "accessibilityEnabled":true}}}
                """.trimIndent()
            ),
            snapshot.claims(nowSeconds = 100, jti = "j")
        )
    }

    @Test
    fun claimsListTheLaunchableAppsWhenKnown() {
        val snapshot = StateSnapshot(
            sampledAtMs = 1,
            playback = null,
            nowPlaying = null,
            volumePercent = 0,
            muted = false,
            backgroundLaunchAllowed = true,
            mediaAccessAllowed = true,
            accessibilityEnabled = true,
            input = InputCapabilities(touch = false, directionalKeys = false),
            applications = listOf("com.netflix.ninja", "com.spotify.tv.android")
        )
        val reports = snapshot.claims(nowSeconds = 1, jti = "j").getValue("reports").jsonObject
        assertEquals(
            Json.parseToJsonElement("""["com.netflix.ninja","com.spotify.tv.android"]"""),
            reports.getValue("applications")
        )
    }

    @Test
    fun unknownFieldsAreOmittedNotGuessed() {
        val snapshot = StateSnapshot(
            sampledAtMs = 5_123,
            playback = null,
            nowPlaying = null,
            volumePercent = 0,
            muted = true,
            backgroundLaunchAllowed = false,
            mediaAccessAllowed = false,
            accessibilityEnabled = false,
            input = InputCapabilities(touch = false, directionalKeys = false)
        )
        assertEquals(
            Json.parseToJsonElement(
                """
                {"iat":5,"sampledAtMs":5123,"jti":"j","reports":{"volume":0,"muted":true,
                  "input":{"touch":false,"directionalKeys":false},
                  "connectivity":{"backgroundLaunchAllowed":false,"mediaAccessAllowed":false,
                    "accessibilityEnabled":false}}}
                """.trimIndent()
            ),
            snapshot.claims(nowSeconds = 5, jti = "j")
        )
    }

    @Test
    fun anAnsweredRequestIsTaggedAndNamesTheAppAndMedia() {
        val mediaId = "portos-media://portoperatingsystem.com/youtube/video/video/dQw4w9WgXcQ"
        val snapshot = StateSnapshot(
            sampledAtMs = 1_000,
            playback = Playback.PAUSED,
            nowPlaying = NowPlaying("com.google.android.youtube.tv", "Song", null, 9, "YOUTUBE", mediaId),
            volumePercent = 1,
            muted = false,
            backgroundLaunchAllowed = true,
            mediaAccessAllowed = true,
            accessibilityEnabled = true,
            input = InputCapabilities(touch = true, directionalKeys = true)
        )
        val claims = snapshot.claims(nowSeconds = 1, jti = "j", requestId = "req-1")
        assertEquals("req-1", claims["requestId"]?.jsonPrimitive?.content)
        assertEquals(1_000L, claims["sampledAtMs"]?.jsonPrimitive?.content?.toLong())
        val nowPlaying = claims["reports"]!!.jsonObject["nowPlaying"]!!.jsonObject
        assertEquals("YOUTUBE", nowPlaying["provider"]?.jsonPrimitive?.content)
        assertEquals(mediaId, nowPlaying["mediaId"]?.jsonPrimitive?.content)
    }

    @Test
    fun theSampleTimeKeepsMillisecondsSoReportsInOneSecondStayDistinct() {
        fun snapshot(sampledAtMs: Long, position: Long) = StateSnapshot(
            sampledAtMs = sampledAtMs,
            playback = Playback.PLAYING,
            nowPlaying = NowPlaying("com.google.android.youtube.tv", "Song", null, position),
            volumePercent = 1,
            muted = false,
            backgroundLaunchAllowed = true,
            mediaAccessAllowed = true,
            accessibilityEnabled = true,
            input = InputCapabilities(touch = true, directionalKeys = true)
        )
        val first = snapshot(1_700_000_000_120, position = 5_000).claims(nowSeconds = 1_700_000_000, jti = "a")
        val second = snapshot(1_700_000_000_640, position = 5_520).claims(nowSeconds = 1_700_000_000, jti = "b")
        assertEquals(first["iat"], second["iat"])
        assertEquals(1_700_000_000_120L, first["sampledAtMs"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_700_000_000_640L, second["sampledAtMs"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun aPlayingPositionAdvancesFromTheSessionsLastUpdate() {
        assertEquals(70_000L, currentPosition(10_000, updatedAt = 1_000, speed = 1f, now = 61_000, playing = true))
        assertEquals(10_000L, currentPosition(10_000, updatedAt = 1_000, speed = 1f, now = 61_000, playing = false))
        assertEquals(10_000L, currentPosition(10_000, updatedAt = 0, speed = 1f, now = 61_000, playing = true))
        assertNull(currentPosition(-1, updatedAt = 1_000, speed = 1f, now = 2_000, playing = true))
    }
}
