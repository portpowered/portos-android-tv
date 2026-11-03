package com.portoperatingsystem.tv.control

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScreenshotEncoderTest {
    private data class Attempt(val width: Int, val height: Int, val quality: Int)

    private fun encode(
        width: Int,
        height: Int,
        maxBytes: Int,
        attempts: MutableList<Attempt>,
        sizeOf: (Attempt) -> Int
    ) = ScreenshotEncoder.encode(width, height, maxBytes) { w, h, q ->
        val attempt = Attempt(w, h, q)
        attempts += attempt
        ByteArray(sizeOf(attempt))
    }

    @Test
    fun keepsTheFirstAttemptWhenItFits() {
        val attempts = mutableListOf<Attempt>()
        val image = assertNotNull(encode(1920, 1080, 1000, attempts) { 900 })
        assertEquals(listOf(Attempt(1920, 1080, 85)), attempts)
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        assertEquals(900, image.bytes.size)
    }

    @Test
    fun stepsDownUntilTheImageFits() {
        val attempts = mutableListOf<Attempt>()
        val image = assertNotNull(encode(1920, 1080, 1000, attempts) { if (it.width > 800) 5000 else 800 })
        assertEquals(listOf(1920, 1920, 1280, 1280, 960, 800), attempts.map { it.width })
        assertEquals(listOf(85, 75, 80, 70, 65, 55), attempts.map { it.quality })
        assertEquals(800, image.width)
        assertEquals(450, image.height)
    }

    @Test
    fun aFourKFrameIsTriedAtFullSizeFirstAndStepsDownFrom3840() {
        val attempts = mutableListOf<Attempt>()
        val image = assertNotNull(encode(3840, 2160, 1000, attempts) { if (it.width > 1280) 5000 else 900 })
        assertEquals(listOf(3840, 3840, 1920, 1920, 1280), attempts.map { it.width })
        assertEquals(listOf(85, 75, 85, 75, 80), attempts.map { it.quality })
        assertEquals(1280, image.width)
        assertEquals(720, image.height)
    }

    @Test
    fun aFourKFrameThatFitsIsKeptAtFullSize() {
        val attempts = mutableListOf<Attempt>()
        val image = assertNotNull(encode(3840, 2160, 4_194_304, attempts) { 3_000_000 })
        assertEquals(listOf(Attempt(3840, 2160, 85)), attempts)
        assertEquals(3840, image.width)
        assertEquals(2160, image.height)
    }

    @Test
    fun neverScalesAnImageUp() {
        val attempts = mutableListOf<Attempt>()
        val image = assertNotNull(encode(640, 360, 1000, attempts) { 10 })
        assertEquals(640, image.width)
        assertEquals(360, image.height)
    }

    @Test
    fun givesUpWhenEvenTheSmallestAttemptIsTooLarge() {
        val attempts = mutableListOf<Attempt>()
        assertNull(encode(1920, 1080, 1000, attempts) { 5000 })
        assertContentEquals(listOf(1920, 1920, 1280, 1280, 960, 800, 640, 480, 320), attempts.map { it.width })
        attempts.clear()
        assertNull(encode(3840, 2160, 1000, attempts) { 5000 })
        val fourK = listOf(3840, 3840, 1920, 1920, 1280, 1280, 960, 800, 640, 480, 320)
        assertContentEquals(fourK, attempts.map { it.width })
    }

    @Test
    fun theDefaultLimitAdmitsA4MibJpegButNotOneByteMore() {
        assertEquals(4_194_304, ScreenshotEncoder.MAX_BYTES)
        val fits = ScreenshotEncoder.encode(1920, 1080) { _, _, _ -> ByteArray(ScreenshotEncoder.MAX_BYTES) }
        assertNotNull(fits)
        assertEquals(1920, fits.width)
        val tooBig = ScreenshotEncoder.encode(1920, 1080) { _, _, _ -> ByteArray(ScreenshotEncoder.MAX_BYTES + 1) }
        assertNull(tooBig)
    }

    @Test
    fun aBase64ReportOfALimitSizedImageFitsTheHostWebhookBody() {
        val base64 = (ScreenshotEncoder.MAX_BYTES + 2) / 3 * 4
        val jws = (base64 + 200 + 2) / 3 * 4
        assert(jws + 400 < 8_388_608) { "a limit-sized screenshot report would exceed the 8 MiB webhook body" }
    }

    @Test
    fun theHeightKeepsTheAspectRatioAndNeverReachesZero() {
        val attempts = mutableListOf<Attempt>()
        encode(4000, 1, 0, attempts) { 1 }
        assertEquals(1, attempts.minOf { it.height })
    }
}
