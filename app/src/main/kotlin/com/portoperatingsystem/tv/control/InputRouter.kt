package com.portoperatingsystem.tv.control

import android.os.Build
import android.view.KeyEvent
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import kotlin.math.roundToInt

/** What this TV can do with remote input right now; reported to Port OS so it only offers what works. */
internal data class InputCapabilities(
    /** A tap can be delivered: the TV has touch input, or local adb can inject one. */
    val touch: Boolean,
    /** Arrow keys and Select can be delivered: Android 13 has D-pad global actions, or local adb can inject keys. */
    val directionalKeys: Boolean
)

/** How a dispatched accessibility gesture ended. */
internal enum class GestureOutcome { COMPLETED, CANCELLED, NOT_DISPATCHED }

/** The accessibility service as the router needs it. */
internal interface AccessibilityInput {
    /** Whether the service is switched on (`make setup-tv`). */
    fun isEnabled(): Boolean

    /** Presses [key] through the service; false when the system did not perform it. */
    fun press(key: NavigationKey): Boolean

    /** Dispatches a tap gesture at [point] and waits for Android to finish or cancel it. */
    fun tap(point: TapGesture.Point): GestureOutcome
}

/** The real display size in pixels. */
internal data class DisplaySize(val width: Int, val height: Int)

/**
 * Decides how remote input reaches the TV. Local adb is used when it is available, because it works on a TV without
 * touch input and where apps expose no accessibility nodes. Otherwise the accessibility service acts, and a failure is
 * reported as one rather than as a success: Android silently drops gestures on a device without touch input, and the
 * focus fallback needs a focused node.
 */
internal class InputRouter(
    private val adb: AdbInput,
    private val accessibility: AccessibilityInput,
    private val display: () -> DisplaySize?,
    private val sdkInt: Int,
    /** The device declares `touchscreen` or `faketouch`, the only devices that accept accessibility gestures. */
    private val hasTouchInput: Boolean
) {
    fun capabilities(): InputCapabilities =
        InputCapabilities(touch = hasTouchInput || adb.available, directionalKeys = hasDpadActions() || adb.available)

    /** Taps ([x], [y]), fractions of the display's width and height (0,0 top-left). */
    fun tap(x: Double, y: Double): Execution {
        val size = display() ?: return Execution.Failed(ExecutionFailure.DISPLAY_UNAVAILABLE)
        val point = TapGesture.point(x, y, size.width, size.height)
        return when {
            adb.available && adb.tap(point.x.roundToInt(), point.y.roundToInt()) -> Execution.Done
            !hasTouchInput -> Execution.Failed(ExecutionFailure.TAP_NEEDS_TOUCH_OR_ADB)
            !accessibility.isEnabled() -> Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED)
            else -> when (accessibility.tap(point)) {
                GestureOutcome.COMPLETED -> Execution.Done
                GestureOutcome.CANCELLED -> Execution.Failed(ExecutionFailure.TAP_CANCELLED)
                GestureOutcome.NOT_DISPATCHED -> Execution.Failed(ExecutionFailure.TAP_NOT_DISPATCHED)
            }
        }
    }

    fun press(key: NavigationKey): Execution {
        // Below Android 13 a directional key has no global action, so it is injected, or else needs a focused node.
        val injectable = directionalKeyCode(key)?.takeUnless { hasDpadActions() }
        return when {
            injectable != null && adb.available && adb.keyEvent(injectable) -> Execution.Done
            !accessibility.isEnabled() -> Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED)
            accessibility.press(key) -> Execution.Done
            injectable != null -> Execution.Failed(ExecutionFailure.KEY_NEEDS_FOCUS)
            else -> Execution.Failed(ExecutionFailure.KEY_NOT_PERFORMED)
        }
    }

    private fun hasDpadActions(): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    private fun directionalKeyCode(key: NavigationKey): Int? = when (key) {
        NavigationKey.UP -> KeyEvent.KEYCODE_DPAD_UP
        NavigationKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        NavigationKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        NavigationKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        NavigationKey.SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
        NavigationKey.BACK, NavigationKey.HOME -> null
    }
}
