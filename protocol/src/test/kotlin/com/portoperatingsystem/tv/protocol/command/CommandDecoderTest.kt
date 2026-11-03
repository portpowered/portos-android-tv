package com.portoperatingsystem.tv.protocol.command

import com.portoperatingsystem.tv.protocol.media.LaunchTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandDecoderTest {
    private val mediaPlayer = "port1/systems/zero/capability-interfaces/media-player/"
    private val speaker = "port1/systems/zero/capability-interfaces/speaker/"
    private val touch = "port1/systems/zero/capability-interfaces/touch-input/"
    private val keypad = "port1/systems/zero/capability-interfaces/keypad-input/"
    private val launcher = "port1/systems/zero/capability-interfaces/application-launcher/"
    private val snapshot = "port1/systems/zero/capability-interfaces/snapshot/"
    private val controlRoutes = "port1/systems/zero/capability-interfaces/control-routes-provider/"

    private fun message(type: String, body: String? = null, id: String = "m1", version: String = "1") = buildMap {
        put("v", version)
        put("id", id)
        put("type", type)
        body?.let { put("body", it) }
    }

    private fun accepted(command: Command) = Decoded.Accepted(Delivery("m1", command))

    private fun invalid(reason: String) = Decoded.Rejected("m1", Rejection.InvalidMessage(reason))

    @Test
    fun decodesPlayMedia() {
        val mediaId = "portos-media://portoperatingsystem.com/youtube/video/video/dQw4w9WgXcQ"
        assertEquals(
            accepted(
                Command.PlayMedia(
                    mediaId,
                    LaunchTarget("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "com.google.android.youtube.tv")
                )
            ),
            CommandDecoder.decode(message(mediaPlayer + "play-media", """{"mediaId":"$mediaId"}"""))
        )
    }

    @Test
    fun decodesTransportWithoutBody() {
        mapOf(
            "play" to TransportAction.PLAY,
            "pause" to TransportAction.PAUSE,
            "stop" to TransportAction.STOP,
            "next" to TransportAction.NEXT,
            "previous" to TransportAction.PREVIOUS
        ).forEach { (name, action) ->
            assertEquals(accepted(Command.Transport(action)), CommandDecoder.decode(message(mediaPlayer + name)))
        }
    }

    @Test
    fun decodesSpeakerCommands() {
        assertEquals(
            accepted(Command.SetVolume(43)),
            CommandDecoder.decode(message(speaker + "set-volume", """{"volume":42.6}"""))
        )
        assertEquals(
            accepted(Command.AdjustVolume(-10)),
            CommandDecoder.decode(message(speaker + "adjust-volume", """{"volumeDelta":-10}"""))
        )
        assertEquals(
            accepted(Command.SetMute(true)),
            CommandDecoder.decode(message(speaker + "set-mute", """{"mute":true}"""))
        )
    }

    private val notification = "port1/systems/zero/capability-interfaces/notification/notification-trigger"

    private fun trigger(type: String = "IMMEDIATE", priority: String = "NORMAL", title: String = "Dinner") =
        """{"triggerId":"t1","triggerType":"$type","notification":{"title":"$title","body":"Dinner is ready",""" +
            """"priority":"$priority","channels":["IN_APP"],"targetUsers":[]}}"""

    @Test
    fun decodesImmediateNotificationsWithPriorityDependentDuration() {
        assertEquals(
            accepted(Command.ShowNotification("Dinner", "Dinner is ready", 15_000)),
            CommandDecoder.decode(message(notification, trigger()))
        )
        assertEquals(
            accepted(Command.ShowNotification("Dinner", "Dinner is ready", 30_000)),
            CommandDecoder.decode(message(notification, trigger(priority = "URGENT")))
        )
    }

    @Test
    fun rejectsNotificationsTheTvCannotShow() {
        listOf("SCHEDULED", "EVENT_BASED", "CONDITION_BASED").forEach { type ->
            assertEquals(
                invalid("triggerType must be IMMEDIATE"),
                CommandDecoder.decode(message(notification, trigger(type = type)))
            )
        }
        assertEquals(
            invalid("title must be a string"),
            CommandDecoder.decode(message(notification, trigger(title = " ")))
        )
        assertEquals(
            invalid("notification must be an object"),
            CommandDecoder.decode(message(notification, """{"triggerType":"IMMEDIATE"}"""))
        )
    }

    @Test
    fun decodesPlayUri() {
        val uri = "https://example.com/chime.mp3"
        assertEquals(
            accepted(Command.PlayUri(uri, null, 0)),
            CommandDecoder.decode(message(mediaPlayer + "play-uri", """{"uri":"$uri"}"""))
        )
        assertEquals(
            accepted(Command.PlayUri(uri, "Chime", 1500)),
            CommandDecoder.decode(
                message(
                    mediaPlayer + "play-uri",
                    """{"uri":"$uri","title":"Chime","sourceType":"FILE","offsetInMilliseconds":1500}"""
                )
            )
        )
    }

    @Test
    fun rejectsPlayUriTheTvCannotPlay() {
        val reject = "uri must be HTTPS with a host and no credentials"
        listOf(
            "http://example.com/a.mp3",
            "https://user:secret@example.com/a.mp3",
            "https:///a.mp3",
            "file:///sdcard/a.mp3",
            "not a uri"
        ).forEach { uri ->
            assertEquals(
                invalid(reject),
                CommandDecoder.decode(message(mediaPlayer + "play-uri", """{"uri":"$uri"}"""))
            )
        }
        assertEquals(
            invalid("sourceType must be FILE"),
            CommandDecoder.decode(
                message(mediaPlayer + "play-uri", """{"uri":"https://example.com/live","sourceType":"LIVE_STREAM"}""")
            )
        )
        assertEquals(
            invalid("offsetInMilliseconds must be a non-negative integer"),
            CommandDecoder.decode(
                message(mediaPlayer + "play-uri", """{"uri":"https://example.com/a.mp3","offsetInMilliseconds":-5}""")
            )
        )
        assertEquals(invalid("uri must be a string"), CommandDecoder.decode(message(mediaPlayer + "play-uri", "{}")))
    }

    @Test
    fun rejectsWrongVersionAndMissingId() {
        assertEquals(
            Decoded.Rejected("m1", Rejection.UnsupportedVersion("2")),
            CommandDecoder.decode(message(mediaPlayer + "play", version = "2"))
        )
        assertEquals(
            Decoded.Rejected(null, Rejection.InvalidMessage("message id is missing")),
            CommandDecoder.decode(message(mediaPlayer + "play", id = " "))
        )
    }

    @Test
    fun rejectsUnknownType() {
        assertEquals(
            Decoded.Rejected("m1", Rejection.UnsupportedType("x/turn-on")),
            CommandDecoder.decode(message("x/turn-on", "{}"))
        )
    }

    @Test
    fun rejectsInvalidBodies() {
        assertEquals(invalid("body is not a JSON object"), CommandDecoder.decode(message(speaker + "set-volume")))
        assertEquals(
            invalid("body is not a JSON object"),
            CommandDecoder.decode(message(speaker + "set-volume", "[1]"))
        )
        assertEquals(
            invalid("volume must be a number"),
            CommandDecoder.decode(message(speaker + "set-volume", """{"volume":"50"}"""))
        )
        assertEquals(
            invalid("volume must be within 0..100"),
            CommandDecoder.decode(message(speaker + "set-volume", """{"volume":101}"""))
        )
        assertEquals(
            invalid("mute must be a boolean"),
            CommandDecoder.decode(message(speaker + "set-mute", """{"mute":"true"}"""))
        )
        assertEquals(
            invalid("mediaId is not a portos-media identity"),
            CommandDecoder.decode(message(mediaPlayer + "play-media", """{"mediaId":"https://youtu.be/x"}"""))
        )
    }

    @Test
    fun rejectsMediaTheTvCannotPlay() {
        val mediaId = "portos-media://portoperatingsystem.com/netflix/video/title/1"
        assertEquals(
            Decoded.Rejected("m1", Rejection.UnsupportedMedia(mediaId)),
            CommandDecoder.decode(message(mediaPlayer + "play-media", """{"mediaId":"$mediaId"}"""))
        )
    }

    @Test
    fun decodesTheKeysTheAccessibilityServicePerforms() {
        NavigationKey.entries.forEach { key ->
            assertEquals(
                accepted(Command.SendKey(key)),
                CommandDecoder.decode(message(keypad + "send-key", """{"keyCode":"${key.name}"}"""))
            )
        }
    }

    @Test
    fun decodesPlayAndPauseKeysAsMediaTransports() {
        mapOf("PLAY" to TransportAction.PLAY, "PAUSE" to TransportAction.PAUSE).forEach { (code, action) ->
            assertEquals(
                accepted(Command.Transport(action)),
                CommandDecoder.decode(message(keypad + "send-key", """{"keyCode":"$code"}"""))
            )
        }
    }

    @Test
    fun refusesKeysTheTvCannotPress() {
        assertEquals(
            invalid("keyCode VOLUME_UP is not supported"),
            CommandDecoder.decode(message(keypad + "send-key", """{"keyCode":"VOLUME_UP"}"""))
        )
        assertEquals(
            invalid("keyCode must be a string"),
            CommandDecoder.decode(message(keypad + "send-key", """{"keyCode":5}"""))
        )
        assertEquals(
            invalid("body is not a JSON object"),
            CommandDecoder.decode(message(keypad + "send-key"))
        )
    }

    @Test
    fun decodesTapsAsFractionsOfTheScreen() {
        assertEquals(
            accepted(Command.Tap(0.13, 0.45)),
            CommandDecoder.decode(message(touch + "tap", """{"x":0.13,"y":0.45}"""))
        )
        assertEquals(
            accepted(Command.Tap(0.0, 1.0)),
            CommandDecoder.decode(message(touch + "tap", """{"x":0,"y":1}"""))
        )
    }

    @Test
    fun refusesTapsOutsideTheScreen() {
        assertEquals(
            invalid("x must be a number from 0 to 1"),
            CommandDecoder.decode(message(touch + "tap", """{"x":250,"y":0.5}"""))
        )
        assertEquals(
            invalid("y must be a number from 0 to 1"),
            CommandDecoder.decode(message(touch + "tap", """{"x":0.5,"y":-0.1}"""))
        )
        assertEquals(
            invalid("y must be a number from 0 to 1"),
            CommandDecoder.decode(message(touch + "tap", """{"x":0.5}"""))
        )
        assertEquals(
            invalid("x must be a number from 0 to 1"),
            CommandDecoder.decode(message(touch + "tap", """{"x":"0.5","y":0.5}"""))
        )
        assertEquals(
            invalid("body is not a JSON object"),
            CommandDecoder.decode(message(touch + "tap"))
        )
    }

    @Test
    fun decodesSeekAndSkipAsAbsoluteAndSignedRelativeMoves() {
        assertEquals(
            accepted(Command.Seek(90_000)),
            CommandDecoder.decode(message(mediaPlayer + "seek", """{"positionMilliseconds":90000}"""))
        )
        assertEquals(
            accepted(Command.Skip(10_000)),
            CommandDecoder.decode(message(mediaPlayer + "skip-forward", """{"deltaPositionMilliseconds":10000}"""))
        )
        assertEquals(
            accepted(Command.Skip(-30_000)),
            CommandDecoder.decode(message(mediaPlayer + "skip-backward", """{"deltaPositionMilliseconds":30000}"""))
        )
    }

    @Test
    fun decodesStartOverAsSeekToTheBeginningWithAnyBody() {
        listOf(null, "{}").forEach { body ->
            assertEquals(accepted(Command.Seek(0)), CommandDecoder.decode(message(mediaPlayer + "start-over", body)))
        }
    }

    @Test
    fun rejectsSeeksAndSkipsWithoutAUsableDistance() {
        val delta = "deltaPositionMilliseconds"
        mapOf(
            "seek" to listOf("""{}""", """{"positionMilliseconds":-1}""", """{"positionMilliseconds":"9"}"""),
            "skip-forward" to listOf("""{}""", """{"$delta":0}""", """{"$delta":1.5}"""),
            "skip-backward" to listOf("""{}""", """{"$delta":-5}""")
        ).forEach { (name, bodies) ->
            bodies.forEach { body ->
                val decoded = CommandDecoder.decode(message(mediaPlayer + name, body))
                assertEquals(true, decoded is Decoded.Rejected && decoded.rejection is Rejection.InvalidMessage, body)
            }
        }
    }

    @Test
    fun decodesLaunchApplicationForAnAndroidPackage() {
        assertEquals(
            accepted(Command.LaunchApp("com.netflix.ninja")),
            CommandDecoder.decode(
                message(
                    launcher + "launch-application",
                    """{"catalogVendorId":0,"applicationId":"com.netflix.ninja"}"""
                )
            )
        )
    }

    @Test
    fun rejectsLaunchApplicationThatIsNotAnAndroidPackageLaunch() {
        listOf(
            """{"applicationId":"com.netflix.ninja"}""",
            """{"catalogVendorId":1,"applicationId":"com.netflix.ninja"}""",
            """{"catalogVendorId":0}""",
            """{"catalogVendorId":0,"applicationId":"netflix"}""",
            """{"catalogVendorId":0,"applicationId":"com.netflix.ninja","data":"movie=1"}"""
        ).forEach { body ->
            val decoded = CommandDecoder.decode(message(launcher + "launch-application", body))
            assertEquals(true, decoded is Decoded.Rejected && decoded.rejection is Rejection.InvalidMessage, body)
        }
    }

    @Test
    fun decodesSnapshotRequestsWithTheMessageIdAndAnyBody() {
        listOf(null, "{}", "not json").forEach { body ->
            assertEquals(
                Decoded.Accepted(Delivery("shot-7", Command.CaptureScreen("shot-7"))),
                CommandDecoder.decode(message(snapshot + "get-snapshot", body, id = "shot-7"))
            )
        }
    }

    @Test
    fun decodesAStateReadWithTheMessageIdAndNoBody() {
        assertEquals(
            accepted(Command.ReportState("m1")),
            CommandDecoder.decode(message(controlRoutes + "report-attributes"))
        )
    }
}
