package com.portoperatingsystem.tv.control

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/** Where and how long a `touch-input/tap` touches the screen. */
internal object TapGesture {
    /** A tap is a short stroke; long enough for apps to register the touch, short enough not to become a press. */
    const val DURATION_MILLIS = 50L

    /** A pixel position on the display. */
    data class Point(val x: Float, val y: Float)

    /** One stroke that touches [point] for [DURATION_MILLIS] and lifts. */
    fun description(point: Point): GestureDescription {
        val path = Path().apply { moveTo(point.x, point.y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, DURATION_MILLIS))
            .build()
    }

    /**
     * Maps fractions of the screen ([x], [y], each 0 to 1, 0,0 top-left) onto a [width] by [height] pixel display.
     * 1 is the edge, which is not a pixel, so it lands on the last pixel row or column instead of off screen.
     */
    fun point(x: Double, y: Double, width: Int, height: Int): Point =
        Point((x * width).toFloat().coerceAtMost(width - 1f), (y * height).toFloat().coerceAtMost(height - 1f))
}
