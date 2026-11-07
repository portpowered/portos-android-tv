package com.portoperatingsystem.tv.protocol.command

import kotlin.test.Test
import kotlin.test.assertEquals

class FileBodiesTest {
    private val mediaPlayer = "port1/systems/zero/capability-interfaces/media-player/"
    private val url = "https://files.example.com/clip.mp4?sig=abc"
    private val expires = "2026-11-06T10:10:00Z"

    private fun decode(type: String, body: String) =
        CommandDecoder.decode(mapOf("v" to "1", "id" to "m1", "type" to mediaPlayer + type, "body" to body))

    private fun video(fields: String = """"url":"$url","mimeType":"video/mp4","expiresAt":"$expires"""") =
        """{"video":{$fields}"""

    private fun invalid(reason: String) = Decoded.Rejected("m1", Rejection.InvalidMessage(reason))

    private fun accepted(command: Command) = Decoded.Accepted(Delivery("m1", command))

    @Test
    fun decodesALoopingVideo() {
        assertEquals(
            accepted(Command.PlayFile(url, "video/mp4", video = true, loop = true)),
            decode("play-video", video() + ""","loop":true}""")
        )
    }

    @Test
    fun loopDefaultsToFalse() {
        assertEquals(
            accepted(Command.PlayFile(url, "video/mp4", video = true, loop = false)),
            decode("play-video", video() + "}")
        )
    }

    @Test
    fun rejectsVideosTheTvCannotPlay() {
        val badUrl = "video url must be HTTPS with a host and no credentials"
        assertEquals(
            invalid(badUrl),
            decode("play-video", video(""""url":"http://files.example.com/a.mp4","mimeType":"video/mp4"""") + "}")
        )
        assertEquals(
            invalid(badUrl),
            decode("play-video", video(""""url":"https://u:p@files.example.com/a.mp4","mimeType":"video/mp4"""") + "}")
        )
        assertEquals(
            invalid("video mimeType must be video/mp4"),
            decode("play-video", video(""""url":"$url","mimeType":"video/webm"""") + "}")
        )
        assertEquals(invalid("video must be an object"), decode("play-video", "{}"))
    }

    @Test
    fun rejectsALoopThatIsNotABoolean() {
        listOf(""""yes"""", "1", "null", """"true"""").forEach { loop ->
            assertEquals(
                invalid("loop must be a boolean"),
                decode("play-video", video() + ""","loop":$loop}""")
            )
        }
    }

    @Test
    fun decodesAnAudioFileThatNeverLoops() {
        val audio = "https://files.example.com/song.mp3"
        assertEquals(
            accepted(Command.PlayFile(audio, "audio/mpeg", video = false, loop = false)),
            decode("play-audio", """{"audio":{"url":"$audio","mimeType":"audio/mpeg","expiresAt":"$expires"}}""")
        )
    }

    @Test
    fun aTitleNamesTheFile() {
        assertEquals(
            accepted(Command.PlayFile(url, "video/mp4", video = true, loop = false, title = "Dance")),
            decode("play-video", video() + ""","title":"Dance"}""")
        )
        val audio = "https://files.example.com/song.mp3"
        assertEquals(
            accepted(Command.PlayFile(audio, "audio/mpeg", video = false, loop = false, title = "Song")),
            decode("play-audio", """{"audio":{"url":"$audio","mimeType":"audio/mpeg"},"title":"Song"}""")
        )
        listOf("1", "null", "true").forEach { title ->
            assertEquals(invalid("title must be a string"), decode("play-video", video() + ""","title":$title}"""))
        }
    }

    @Test
    fun rejectsAudioTheTvCannotPlay() {
        assertEquals(
            invalid("audio mimeType must start with audio/"),
            decode("play-audio", """{"audio":{"url":"$url","mimeType":"video/mp4"}}""")
        )
        assertEquals(
            invalid("audio url must be HTTPS with a host and no credentials"),
            decode("play-audio", """{"audio":{"url":"http://files.example.com/a.mp3","mimeType":"audio/mpeg"}}""")
        )
        assertEquals(invalid("audio must be an object"), decode("play-audio", """{"audio":"x"}"""))
    }
}
