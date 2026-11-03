package com.portoperatingsystem.tv.control

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.portoperatingsystem.tv.protocol.command.NavigationKey

/** [ScreenPort] that sends input through an [InputRouter]; [capture] answers a snapshot request. */
internal class AndroidScreen(
    private val context: Context,
    private val input: InputRouter,
    private val capture: (requestId: String) -> Unit
) : ScreenPort {
    override fun isAccessibilityEnabled(): Boolean = AccessibilitySetting.isEnabled(context)

    override fun pressKey(key: NavigationKey): Execution = input.press(key)

    override fun tap(x: Double, y: Double): Execution = input.tap(x, y)

    override fun captureScreen(requestId: String) = capture(requestId)
}

/** [AccessibilityInput] backed by the connected [PortOsAccessibilityService]. */
internal class ServiceInput(private val context: Context) : AccessibilityInput {
    override fun isEnabled(): Boolean = AccessibilitySetting.isEnabled(context)

    override fun press(key: NavigationKey): Boolean = PortOsAccessibilityService.instance?.press(key) ?: false

    override fun tap(point: TapGesture.Point): GestureOutcome =
        PortOsAccessibilityService.instance?.tap(point) ?: GestureOutcome.NOT_DISPATCHED
}

/** The real display size, read per tap because the TV can change resolution. */
internal class RealDisplay(private val context: Context) {
    @Suppress("DEPRECATION")
    fun size(): DisplaySize? {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return DisplaySize(metrics.widthPixels, metrics.heightPixels)
    }
}

/**
 * Whether Android accepts gestures: only devices that declare a touchscreen or a fake touch (a mouse-driven touch
 * emulation) do. A TV that declares neither silently drops `dispatchGesture`.
 */
internal fun hasTouchInput(packageManager: PackageManager): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH)
