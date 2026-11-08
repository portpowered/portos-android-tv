package com.portoperatingsystem.tv.playback

import com.portoperatingsystem.tv.protocol.command.ImageFile
import com.portoperatingsystem.tv.protocol.command.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImagePartsTest {
    private fun images(vararg names: String) = names.map { ImageFile("https://example.com/$it.jpg", "image/jpeg") }

    @Test
    fun aSinglePartIsCompleteAtOnce() {
        assertEquals(images("a"), ImageParts().accept("show", Part("s", 0, 1), images("a")))
    }

    @Test
    fun partsArrivingInAnyOrderCompleteInSetOrder() {
        val parts = ImageParts()
        assertNull(parts.accept("show", Part("s", 2, 3), images("e")))
        assertNull(parts.accept("show", Part("s", 0, 3), images("a", "b")))
        assertEquals(images("a", "b", "c", "d", "e"), parts.accept("show", Part("s", 1, 3), images("c", "d")))
    }

    @Test
    fun interleavedSetsBothComplete() {
        val parts = ImageParts()
        assertNull(parts.accept("show", Part("old", 0, 2), images("a")))
        assertNull(parts.accept("show", Part("new", 0, 2), images("x")))
        assertEquals(images("a", "b"), parts.accept("show", Part("old", 1, 2), images("b")))
        assertEquals(images("x", "y"), parts.accept("show", Part("new", 1, 2), images("y")))
    }

    @Test
    fun aCompletedSetDropsTheOnesBegunBeforeIt() {
        val parts = ImageParts()
        assertNull(parts.accept("show", Part("old", 0, 2), images("a")))
        assertEquals(images("x"), parts.accept("show", Part("new", 0, 1), images("x")))
        assertNull(parts.accept("show", Part("old", 1, 2), images("b")))
    }

    @Test
    fun onlyTheLastFewUnfinishedSetsAreKept() {
        val parts = ImageParts(keep = 2)
        listOf("one", "two", "three").forEach { assertNull(parts.accept("show", Part(it, 0, 2), images(it))) }
        assertNull(parts.accept("show", Part("one", 1, 2), images("b")))
        assertEquals(images("three", "c"), parts.accept("show", Part("three", 1, 2), images("c")))
    }

    @Test
    fun showAndAddSetsAreKeptApart() {
        val parts = ImageParts()
        assertNull(parts.accept("show", Part("s", 0, 2), images("a")))
        assertEquals(images("n"), parts.accept("add", Part("t", 0, 1), images("n")))
        assertEquals(images("a", "b"), parts.accept("show", Part("s", 1, 2), images("b")))
    }
}
