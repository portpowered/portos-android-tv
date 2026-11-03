package com.portoperatingsystem.tv.control

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class TapGestureTest {
    @Test
    fun aTapIsOneFiftyMillisecondStrokeStartingImmediately() {
        val gesture = TapGesture.description(TapGesture.point(0.25, 0.5, 1920, 1080))

        assertEquals(1, gesture.strokeCount)
        assertEquals(50L, gesture.getStroke(0).duration)
        assertEquals(0L, gesture.getStroke(0).startTime)
    }

    @Test
    fun mapsFractionsOntoTheRealDisplayWhateverTheSnapshotSize() {
        assertEquals(TapGesture.Point(480f, 540f), TapGesture.point(0.25, 0.5, 1920, 1080))
        assertEquals(TapGesture.Point(960f, 1080f), TapGesture.point(0.25, 0.5, 3840, 2160))
    }

    @Test
    fun theOriginIsTheTopLeftPixel() {
        assertEquals(TapGesture.Point(0f, 0f), TapGesture.point(0.0, 0.0, 1920, 1080))
    }

    @Test
    fun theFarEdgeLandsOnTheLastPixelInsteadOfOffScreen() {
        assertEquals(TapGesture.Point(1919f, 1079f), TapGesture.point(1.0, 1.0, 1920, 1080))
    }
}
