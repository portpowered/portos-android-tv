package com.portoperatingsystem.tv.state

import com.portoperatingsystem.tv.control.InputCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CommandOutcomeTest {
    private val snapshot = StateSnapshot(
        sampledAtMs = 100_123,
        playback = Playback.PLAYING,
        nowPlaying = NowPlaying("com.portoperatingsystem.tv", "Beach week", null, 0, queue = QueuePlace(1, 3)),
        volumePercent = 40,
        muted = false,
        backgroundLaunchAllowed = true,
        mediaAccessAllowed = true,
        accessibilityEnabled = true,
        input = InputCapabilities(touch = false, directionalKeys = false)
    )
    private val sent = mutableListOf<Pair<String, CommandOutcome>>()
    private val answers = Answers { id, outcome -> sent += id to outcome }
    private val acted = CommandOutcome.Acted(8, false, 3)
    private val replaced =
        CommandOutcome.Failed(FailureCode.REPLACED, "a newer command replaced this one before it took effect")

    private fun claims(outcome: CommandOutcome): JsonObject =
        snapshot.claims(nowSeconds = 100, jti = "j", requestId = "m1", outcome = outcome)

    @Test
    fun anActedReportCarriesTheRequestIdTheStateAndTheOutcome() {
        val claims = claims(acted)
        println("SUCCESS CLAIMS: $claims")

        assertEquals("m1", claims.getValue("requestId").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"secondsPerImage":8,"loop":false}"""), claims.getValue("outcome"))
        assertEquals(setOf("iat", "sampledAtMs", "jti", "requestId", "reports", "outcome"), claims.keys)
        val playing = claims.getValue("reports").jsonObject.getValue("nowPlaying").jsonObject
        assertEquals(3, playing.getValue("queueLength").jsonPrimitive.content.toInt())
    }

    @Test
    fun aFileOutcomeLeavesOutSecondsPerImage() {
        assertEquals(
            Json.parseToJsonElement("""{"loop":true}"""),
            claims(CommandOutcome.Acted(null, true, 1)).getValue("outcome")
        )
    }

    @Test
    fun aFailedReportNamesTheCodeAndStillCarriesTheState() {
        val claims = claims(CommandOutcome.Failed(FailureCode.NO_SLIDESHOW, "no slideshow is showing"))
        println("FAILURE CLAIMS: $claims")

        assertEquals(
            Json.parseToJsonElement("""{"failure":{"code":"NO_SLIDESHOW","message":"no slideshow is showing"}}"""),
            claims.getValue("outcome")
        )
        val reports = claims.getValue("reports").jsonObject
        assertEquals("PLAYING", reports.getValue("playbackState").jsonPrimitive.content)
    }

    @Test
    fun aCommandIsAnsweredOnce() {
        answers.begin("a")
        answers.answer("a", acted)
        answers.fail("a", FailureCode.PLAYBACK_FAILED, "late")
        answers.begin("b")

        assertEquals(listOf<Pair<String, CommandOutcome>>("a" to acted), sent)
    }

    @Test
    fun aNewCommandAnswersTheOneItReplacedOnce() {
        answers.begin("a")
        answers.begin("b")
        answers.begin(null)

        assertEquals(listOf<Pair<String, CommandOutcome>>("a" to replaced, "b" to replaced), sent)
    }

    @Test
    fun theArmedOutcomeIsSentWhenTheFirstItemShowsAndNotAgain() {
        answers.begin("a")
        answers.starting(acted)
        answers.shown()
        answers.shown()
        answers.playerFailed("late")

        assertEquals(listOf<Pair<String, CommandOutcome>>("a" to acted), sent)
    }

    @Test
    fun aPlayerErrorBeforeTheFirstItemShowsFailsTheCommand() {
        answers.begin("a")
        answers.starting(acted)
        answers.playerFailed("ERROR_CODE_DECODING_FAILED")

        assertEquals("a", sent.single().first)
        assertEquals(FailureCode.PLAYBACK_FAILED, (sent.single().second as CommandOutcome.Failed).code)
    }
}
