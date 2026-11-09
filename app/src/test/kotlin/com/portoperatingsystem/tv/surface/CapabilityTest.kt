package com.portoperatingsystem.tv.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CapabilityTest {
    @Test
    fun theDisplayIsAlways960By540DpWhateverThePanel() {
        val fullHd = SurfaceDisplay.info(1920, 1080)
        assertEquals("1920", fullHd.getValue("widthPx").jsonPrimitive.content)
        assertEquals("960", fullHd.getValue("widthDp").jsonPrimitive.content)
        assertEquals("540", fullHd.getValue("heightDp").jsonPrimitive.content)
        assertEquals("2.0", fullHd.getValue("density").jsonPrimitive.content)
        assertEquals("24", fullHd.getValue("safeAreaInsetDp").jsonPrimitive.content)
        assertEquals("TEN_FOOT", fullHd.getValue("viewingDistance").jsonPrimitive.content)

        val hd = SurfaceDisplay.info(1280, 720)
        assertEquals("540", hd.getValue("heightDp").jsonPrimitive.content)
        assertEquals("1.33", hd.getValue("density").jsonPrimitive.content)
        assertEquals(2f, SurfaceDisplay.density(1920))
    }

    @Test
    fun theRegistrationListsEveryComponentTheRendererDraws() {
        val registration = Capability.registration(SurfaceDisplay.info(1920, 1080))

        assertEquals("1.0", registration.getValue("catalogVersion").jsonPrimitive.content)
        assertEquals(
            listOf(Capability.BASIC_CATALOG, Capability.TV_CATALOG),
            registration.getValue("catalogIds").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("524288", registration.getValue("maxPayloadBytes").jsonPrimitive.content)
        assertEquals(SurfaceDisplay.info(1920, 1080), registration.getValue("display"))
        val components = registration.getValue("components").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("Text", "Image", "Icon", "Row", "Column", "List", "Card", "Divider", "Timer"),
            components.map { it.getValue("name").jsonPrimitive.content }
        )
        for (component in components) {
            assertTrue(component.getValue("rendering").jsonPrimitive.content.isNotBlank())
            assertTrue(component.getValue("properties") is JsonObject)
        }
    }

    @Test
    fun everyComponentAcceptsAWeight() {
        val components = Capability.registration(SurfaceDisplay.info(1920, 1080))
            .getValue("components").jsonArray.map { it.jsonObject }
        for (component in components) {
            assertTrue("weight" in component.getValue("properties").jsonObject, component.toString())
        }
    }
}
