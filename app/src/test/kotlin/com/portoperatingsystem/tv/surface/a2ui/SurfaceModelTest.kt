package com.portoperatingsystem.tv.surface.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class SurfaceModelTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val create = """{"version":"1.0","createSurface":{"surfaceId":"s","catalogId":"c","components":[
        {"id":"root","component":"Column","children":["t"]},{"id":"t","component":"Text","text":"hi"}],
        "dataModel":{"name":"Ada","items":[{"n":1},{"n":2}]}}}"""

    private fun model(): SurfaceModel = SurfaceModel.create(A2uiParser.parse(json(create)) as A2uiMessage.CreateSurface)

    @Test
    fun parsesTheFourServerMessages() {
        val messages = A2uiParser.parseAll(
            json(
                """[$create,
                {"version":"1.0","updateComponents":{"surfaceId":"s","components":[{"id":"t","component":"Text"}]}},
                {"version":"1.0","updateDataModel":{"surfaceId":"s","path":"/name","value":"Bo"}},
                {"version":"1.0","deleteSurface":{"surfaceId":"s"}}]"""
            )
        )

        assertEquals(4, messages.size)
        assertIs<A2uiMessage.CreateSurface>(messages[0])
        assertIs<A2uiMessage.UpdateComponents>(messages[1])
        assertEquals("/name", (messages[2] as A2uiMessage.UpdateDataModel).path)
        assertIs<A2uiMessage.DeleteSurface>(messages[3])
    }

    @Test
    fun refusesMalformedMessages() {
        val bad = listOf(
            """{"createSurface":{"surfaceId":"s"}}""",
            """{"version":"0.9","createSurface":{"surfaceId":"s"}}""",
            """{"version":"1.0","createSurface":{"surfaceId":"s"},"deleteSurface":{"surfaceId":"s"}}""",
            """{"version":"1.0","deleteSurface":{}}""",
            """{"version":"1.0","updateComponents":{"surfaceId":"s","components":[{"component":"Text"}]}}""",
            """{"version":"1.0","updateComponents":{"surfaceId":"s","components":[{"id":"a","component":"Surface"}]}}""",
            """{"version":"1.0","updateComponents":{"surfaceId":"s"}}""",
            "\"text\""
        )
        for (text in bad) assertFailsWith<A2uiException>(text) { A2uiParser.parse(json(text)) }
        assertFailsWith<A2uiException> { A2uiParser.parseAll(null) }
    }

    @Test
    fun updateComponentsAddsAndReplacesById() {
        val updated = model().apply(
            A2uiParser.parse(
                json(
                    """{"version":"1.0","updateComponents":{"surfaceId":"s","components":[
                    {"id":"t","component":"Text","text":"new"},{"id":"u","component":"Divider"}]}}"""
                )
            )
        )

        assertEquals(listOf("root", "t", "u"), updated.components.keys.toList())
        assertEquals(JsonPrimitive("new"), updated.components.getValue("t")["text"])
    }

    @Test
    fun updateDataModelSetsADeepPathAndDeletesOnNull() {
        val set = model().apply(A2uiMessage.UpdateDataModel("s", "/items/1/n", JsonPrimitive(9)))
        assertEquals(JsonPrimitive(9), DataPointer.get(set.dataModel, "/items/1/n"))

        val deleted = set.apply(A2uiMessage.UpdateDataModel("s", "/name", kotlinx.serialization.json.JsonNull))
        assertNull(DataPointer.get(deleted.dataModel, "/name"))

        val replaced = deleted.apply(A2uiMessage.UpdateDataModel("s", null, json("""{"x":1}""")))
        assertEquals(json("""{"x":1}"""), replaced.dataModel)
    }

    @Test
    fun dataPointerReadsEscapedKeysAndArrayAppend() {
        val root = json("""{"a/b":{"c~d":5},"list":[1]}""")
        assertEquals(JsonPrimitive(5), DataPointer.get(root, "/a~1b/c~0d"))
        assertNull(DataPointer.get(root, "/list/4"))
        val appended = DataPointer.set(root, "/list/-", JsonPrimitive(2))
        assertEquals(json("""[1,2]"""), DataPointer.get(appended, "/list"))
        assertEquals(root, DataPointer.set(root, "/list/9", JsonPrimitive(2)))
    }

    @Test
    fun componentListKeepsTheOrderItWasSent() {
        val ids = model().componentList().map { (it as JsonObject).id() }
        assertEquals(listOf("root", "t"), ids)
        assertEquals("c", model().catalogId)
        assertEquals("Ada", model().dataModel.jsonObject["name"]?.let { (it as JsonPrimitive).content })
    }
}
