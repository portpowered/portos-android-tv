package com.portoperatingsystem.tv.protocol.command

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageBodiesTest {
    private val mediaPlayer = "port1/systems/zero/capability-interfaces/media-player/"
    private val url = "https://files.example.com/a.jpg?sig=abc"
    private val image = """{"url":"$url","mimeType":"image/jpeg","expiresAt":"2026-11-07T10:10:00Z"}"""
    private val part = """"part":{"set":"s1","index":1,"count":3}"""

    private fun decode(type: String, body: String) =
        CommandDecoder.decode(mapOf("v" to "1", "id" to "m1", "type" to mediaPlayer + type, "body" to body))

    private fun invalid(reason: String) = Decoded.Rejected("m1", Rejection.InvalidMessage(reason))

    private fun accepted(command: Command) = Decoded.Accepted(Delivery("m1", command))

    private val imagesRule =
        "images must be 1 to 30 objects with an HTTPS url and a mimeType of image/jpeg, image/png or image/webp"
    private val partRule = "part must be {set, index, count} with 0 <= index < count <= 30"

    @Test
    fun decodesAShowImagesPart() {
        assertEquals(
            accepted(
                Command.ShowImages(
                    Part("s1", 1, 3),
                    listOf(ImageFile(url, "image/jpeg"), ImageFile(url, "image/webp")),
                    title = "Beach week",
                    secondsPerImage = 5,
                    loop = true
                )
            ),
            decode(
                "show-images",
                """{$part,"images":[$image,{"url":"$url","mimeType":"image/webp"}],""" +
                    """"title":"Beach week","secondsPerImage":5,"loop":true}"""
            )
        )
    }

    @Test
    fun showImagesDefaultsToEightSecondsOnceThrough() {
        assertEquals(
            accepted(Command.ShowImages(Part("s1", 1, 3), listOf(ImageFile(url, "image/jpeg")), null, 8, false)),
            decode("show-images", """{$part,"images":[$image]}""")
        )
    }

    @Test
    fun rejectsShowImagesOutsideItsLimits() {
        val secondsRule = "secondsPerImage must be an integer within 2..60"
        listOf("1", "61", "2.5", "\"8\"").forEach { seconds ->
            assertEquals(
                invalid(secondsRule),
                decode("show-images", """{$part,"images":[$image],"secondsPerImage":$seconds}""")
            )
        }
        val thirtyOne = List(31) { image }.joinToString(",")
        listOf(
            "[]",
            "[$thirtyOne]",
            """[{"url":"$url","mimeType":"image/gif"}]""",
            """[{"url":"http://files.example.com/a.jpg","mimeType":"image/jpeg"}]""",
            """["$url"]"""
        ).forEach { images ->
            assertEquals(invalid(imagesRule), decode("show-images", """{$part,"images":$images}"""))
        }
        listOf(
            "",
            """"part":{"set":"","index":0,"count":1},""",
            """"part":{"set":"s","index":1,"count":1},""",
            """"part":{"set":"s","index":0,"count":31},""",
            """"part":{"set":"s","index":-1,"count":2},"""
        ).forEach { badPart ->
            assertEquals(invalid(partRule), decode("show-images", """{$badPart"images":[$image]}"""))
        }
    }

    @Test
    fun decodesAddImagesWithAndWithoutAPosition() {
        assertEquals(
            accepted(Command.AddImages(Part("s1", 1, 3), listOf(ImageFile(url, "image/jpeg")), 2)),
            decode("add-images", """{$part,"images":[$image],"position":2}""")
        )
        assertEquals(
            accepted(Command.AddImages(Part("s1", 1, 3), listOf(ImageFile(url, "image/jpeg")), null)),
            decode("add-images", """{$part,"images":[$image]}""")
        )
        listOf("0", "31", "\"2\"", "1.5").forEach { position ->
            assertEquals(
                invalid("position must be an integer within 1..30"),
                decode("add-images", """{$part,"images":[$image],"position":$position}""")
            )
        }
    }

    @Test
    fun decodesRemoveImages() {
        assertEquals(accepted(Command.RemoveImages(listOf(3, 1))), decode("remove-images", """{"positions":[3,1]}"""))
        listOf("[]", "[0]", "[31]", "[1,1]", "[\"1\"]", "[1.5]", "1").forEach { positions ->
            assertEquals(
                invalid("positions must be 1 to 30 distinct integers within 1..30"),
                decode("remove-images", """{"positions":$positions}""")
            )
        }
    }
}
