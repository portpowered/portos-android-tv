package com.portoperatingsystem.tv.control

import android.view.KeyEvent
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class FakeAdb(override var available: Boolean = false, private val succeeds: Boolean = true) : AdbInput {
    val taps = mutableListOf<Pair<Int, Int>>()
    val keys = mutableListOf<Int>()

    override fun tap(x: Int, y: Int): Boolean {
        taps += x to y
        return succeeds
    }

    override fun keyEvent(keyCode: Int): Boolean {
        keys += keyCode
        return succeeds
    }
}

private class FakeAccessibility(
    var enabled: Boolean = true,
    var pressResult: Boolean = true,
    var gesture: GestureOutcome = GestureOutcome.COMPLETED
) : AccessibilityInput {
    val pressed = mutableListOf<NavigationKey>()
    val taps = mutableListOf<TapGesture.Point>()

    override fun isEnabled() = enabled

    override fun press(key: NavigationKey): Boolean {
        pressed += key
        return pressResult
    }

    override fun tap(point: TapGesture.Point): GestureOutcome {
        taps += point
        return gesture
    }
}

class InputRouterTest {
    private val android12 = 31
    private val android13 = 33
    private val adb = FakeAdb()
    private val accessibility = FakeAccessibility()

    private fun router(sdk: Int = android12, touch: Boolean = false, size: DisplaySize? = DisplaySize(1920, 1080)) =
        InputRouter(adb, accessibility, { size }, sdk, touch)

    @Test
    fun aTvWithoutTouchOrAdbOffersNeitherTapsNorDirectionalKeysBeforeAndroid13() {
        assertEquals(InputCapabilities(touch = false, directionalKeys = false), router().capabilities())
    }

    @Test
    fun touchInputIsOfferedWhenTheTvHasItOrAdbCanInjectIt() {
        assertEquals(InputCapabilities(touch = true, directionalKeys = false), router(touch = true).capabilities())

        adb.available = true
        assertEquals(InputCapabilities(touch = true, directionalKeys = true), router().capabilities())
    }

    @Test
    fun directionalKeysAreOfferedFromAndroid13() {
        assertEquals(InputCapabilities(touch = false, directionalKeys = true), router(android13).capabilities())
    }

    @Test
    fun aTapGoesThroughAdbWhenAvailableInDisplayPixels() {
        adb.available = true

        assertEquals(Execution.Done, router().tap(0.5, 0.25))
        assertEquals(listOf(960 to 270), adb.taps)
        assertTrue(accessibility.taps.isEmpty())
    }

    @Test
    fun theRightEdgeTapsTheLastPixelColumn() {
        adb.available = true

        router().tap(1.0, 1.0)

        assertEquals(listOf(1919 to 1079), adb.taps)
    }

    @Test
    fun aTapFallsBackToAGestureWhenAdbIsNotAvailableOnATouchTv() {
        assertEquals(Execution.Done, router(touch = true).tap(0.5, 0.5))
        assertEquals(listOf(TapGesture.Point(960f, 540f)), accessibility.taps)
        assertTrue(adb.taps.isEmpty())
    }

    @Test
    fun aTapFallsBackToAGestureWhenAdbFailsOnATouchTv() {
        val failing = FakeAdb(available = true, succeeds = false)

        val result = InputRouter(failing, accessibility, { DisplaySize(100, 100) }, android12, true).tap(0.5, 0.5)

        assertEquals(Execution.Done, result)
        assertEquals(1, failing.taps.size)
        assertEquals(1, accessibility.taps.size)
    }

    @Test
    fun aTapFailsWithTheReasonOnATvWithoutTouchInputOrAdb() {
        assertEquals(Execution.Failed(ExecutionFailure.TAP_NEEDS_TOUCH_OR_ADB), router().tap(0.5, 0.5))
        assertTrue(accessibility.taps.isEmpty())
    }

    @Test
    fun aTapFailsWhenAdbFailsOnATvWithoutTouchInput() {
        val failing = FakeAdb(available = true, succeeds = false)

        val result = InputRouter(failing, accessibility, { DisplaySize(100, 100) }, android12, false).tap(0.5, 0.5)

        assertEquals(Execution.Failed(ExecutionFailure.TAP_NEEDS_TOUCH_OR_ADB), result)
    }

    @Test
    fun aGestureTapNeedsTheAccessibilityService() {
        accessibility.enabled = false

        assertEquals(Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED), router(touch = true).tap(0.5, 0.5))
    }

    @Test
    fun aCancelledOrUndispatchedGestureIsAFailureNotASuccess() {
        accessibility.gesture = GestureOutcome.CANCELLED
        assertEquals(Execution.Failed(ExecutionFailure.TAP_CANCELLED), router(touch = true).tap(0.5, 0.5))

        accessibility.gesture = GestureOutcome.NOT_DISPATCHED
        assertEquals(Execution.Failed(ExecutionFailure.TAP_NOT_DISPATCHED), router(touch = true).tap(0.5, 0.5))
    }

    @Test
    fun aTapNeedsTheDisplaySize() {
        adb.available = true

        assertEquals(Execution.Failed(ExecutionFailure.DISPLAY_UNAVAILABLE), router(size = null).tap(0.5, 0.5))
    }

    @Test
    fun directionalKeysBeforeAndroid13UseAdbWhenAvailable() {
        adb.available = true
        val expected = mapOf(
            NavigationKey.UP to KeyEvent.KEYCODE_DPAD_UP,
            NavigationKey.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            NavigationKey.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            NavigationKey.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            NavigationKey.SELECT to KeyEvent.KEYCODE_DPAD_CENTER
        )

        expected.keys.forEach { assertEquals(Execution.Done, router().press(it)) }

        assertEquals(expected.values.toList(), adb.keys)
        assertTrue(accessibility.pressed.isEmpty())
    }

    @Test
    fun directionalKeysBeforeAndroid13FallBackToTheServiceWithoutAdb() {
        assertEquals(Execution.Done, router().press(NavigationKey.DOWN))
        assertEquals(listOf(NavigationKey.DOWN), accessibility.pressed)
    }

    @Test
    fun directionalKeysBeforeAndroid13FallBackToTheServiceWhenAdbFails() {
        val failing = FakeAdb(available = true, succeeds = false)

        val result = InputRouter(failing, accessibility, { null }, android12, false).press(NavigationKey.SELECT)

        assertEquals(Execution.Done, result)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_CENTER), failing.keys)
    }

    @Test
    fun aDirectionalKeyThatFindsNoFocusIsAFailureThatNamesNetworkDebugging() {
        accessibility.pressResult = false

        assertEquals(Execution.Failed(ExecutionFailure.KEY_NEEDS_FOCUS), router().press(NavigationKey.SELECT))
    }

    @Test
    fun androidThirteenUsesTheServiceEvenWhenAdbIsAvailable() {
        adb.available = true

        assertEquals(Execution.Done, router(android13).press(NavigationKey.UP))
        assertTrue(adb.keys.isEmpty())
        assertEquals(listOf(NavigationKey.UP), accessibility.pressed)
    }

    @Test
    fun androidThirteenKeyTheSystemRefusesIsPlainlyNotPerformed() {
        accessibility.pressResult = false

        assertEquals(Execution.Failed(ExecutionFailure.KEY_NOT_PERFORMED), router(android13).press(NavigationKey.UP))
    }

    @Test
    fun backAndHomeNeverUseAdb() {
        adb.available = true

        listOf(NavigationKey.BACK, NavigationKey.HOME).forEach { assertEquals(Execution.Done, router().press(it)) }

        assertTrue(adb.keys.isEmpty())
        assertEquals(listOf(NavigationKey.BACK, NavigationKey.HOME), accessibility.pressed)
    }

    @Test
    fun aKeyWithoutAdbNeedsTheAccessibilityService() {
        accessibility.enabled = false

        assertEquals(Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED), router().press(NavigationKey.BACK))
    }

    @Test
    fun adbKeysNeedNoAccessibilityService() {
        accessibility.enabled = false
        adb.available = true

        assertEquals(Execution.Done, router().press(NavigationKey.UP))
    }
}
