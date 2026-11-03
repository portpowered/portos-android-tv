package com.portoperatingsystem.tv.control

/** A JPEG ready to upload. */
internal class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Shrinks a screenshot until it fits [MAX_BYTES]. The host's installation webhook body limit is 8 MiB, and the JPEG
 * travels base64-encoded inside a base64url-encoded signed report, so a busy frame is re-encoded smaller and at lower
 * quality, one rung at a time, and the first rung that fits wins. A full 4K frame at high quality is tried first;
 * a smaller screen is never scaled up, so its first rung is its native size.
 */
internal object ScreenshotEncoder {
    /**
     * The most bytes the plugin accepts for one image (4 MiB). Base64 inside the signed report makes that about
     * 7,457,000 bytes on the wire, under the host's 8,388,608-byte installation webhook body limit.
     */
    const val MAX_BYTES: Int = 4_194_304

    /** Width and JPEG quality of each attempt, best first. */
    private val ladder = listOf(
        Rung(width = 3840, quality = 85),
        Rung(width = 3840, quality = 75),
        Rung(width = 1920, quality = 85),
        Rung(width = 1920, quality = 75),
        Rung(width = 1280, quality = 80),
        Rung(width = 1280, quality = 70),
        Rung(width = 960, quality = 65),
        Rung(width = 800, quality = 55),
        Rung(width = 640, quality = 50),
        Rung(width = 480, quality = 45),
        Rung(width = 320, quality = 40)
    )

    /**
     * Encodes a [sourceWidth] by [sourceHeight] image through [render], which returns the JPEG for a width, height
     * and quality. Returns null when even the smallest rung is too large.
     */
    fun encode(
        sourceWidth: Int,
        sourceHeight: Int,
        maxBytes: Int = MAX_BYTES,
        render: (width: Int, height: Int, quality: Int) -> ByteArray
    ): EncodedImage? {
        require(sourceWidth > 0 && sourceHeight > 0) { "the image has no pixels" }
        val tried = mutableSetOf<Rung>()
        return ladder.firstNotNullOfOrNull { rung ->
            val width = minOf(rung.width, sourceWidth)
            // A screen narrower than a rung makes it a repeat of an earlier attempt; do not render it twice.
            if (!tried.add(Rung(width, rung.quality))) return@firstNotNullOfOrNull null
            val height = maxOf(1, (sourceHeight.toLong() * width / sourceWidth).toInt())
            render(width, height, rung.quality).takeIf { it.size <= maxBytes }?.let { EncodedImage(it, width, height) }
        }
    }

    private data class Rung(val width: Int, val quality: Int)
}
