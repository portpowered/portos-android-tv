package com.portoperatingsystem.tv.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Presses remote keys and captures the screen. */
internal interface ScreenControl {
    /** Presses [key]; false when the system did not perform it. */
    fun press(key: NavigationKey): Boolean

    /** Captures the screen and passes it to [onCaptured], or null when it could not be captured. */
    fun capture(onCaptured: (Bitmap?) -> Unit)
}

/**
 * The one accessibility service. Remote buttons and screen capture both need it: Android only lets an accessibility
 * service perform global navigation actions and take screenshots of other apps. It is switched on through `adb`
 * (`make setup-tv`) because Google TV has no settings screen for it. It ignores accessibility events.
 */
internal class PortOsAccessibilityService :
    AccessibilityService(),
    ScreenControl {
    private val dispatcher = KeyDispatcher(
        object : KeySurface {
            override val sdkInt: Int = Build.VERSION.SDK_INT

            override fun globalAction(action: Int): Boolean = performGlobalAction(action)

            override fun clickFocused(): Boolean =
                focusedNode()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

            override fun moveFocus(direction: Int): Boolean {
                val next = focusedNode()?.focusSearch(direction) ?: return false
                return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                    next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
        }
    )

    public override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun press(key: NavigationKey): Boolean = dispatcher.press(key)

    /**
     * Taps once at [point] (display pixels) and waits for Android to finish or cancel the gesture, so the result is
     * what happened rather than what was requested. Blocks up to a few seconds, so call it off the main thread, which
     * delivers the result. Android drops gestures on a TV without touch input; [InputRouter] does not ask it to.
     */
    fun tap(point: TapGesture.Point): GestureOutcome {
        val outcome = AtomicReference(GestureOutcome.CANCELLED)
        val finished = CountDownLatch(1)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                outcome.set(GestureOutcome.COMPLETED)
                finished.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finished.countDown()
            }
        }
        if (!dispatchGesture(TapGesture.description(point), callback, Handler(Looper.getMainLooper()))) {
            Log.w(TAG, "tap at ${point.x},${point.y} was not dispatched")
            return GestureOutcome.NOT_DISPATCHED
        }
        // A gesture Android never finishes is as good as cancelled.
        finished.await(GESTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return outcome.get()
    }

    override fun capture(onCaptured: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onCaptured(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    screenshot.hardwareBuffer.close()
                    onCaptured(software)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed with code $errorCode")
                    onCaptured(null)
                }
            }
        )
    }

    internal fun focusedNode(): AccessibilityNodeInfo? = rootInActiveWindow?.let { root ->
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    companion object {
        private const val TAG = "PortOsAccessibility"
        private const val GESTURE_TIMEOUT_SECONDS = 3L

        /** The connected service, or null while it is off or not yet bound. */
        @Volatile
        var instance: PortOsAccessibilityService? = null
            private set
    }
}
