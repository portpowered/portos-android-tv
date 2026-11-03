package com.portoperatingsystem.tv.control

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.View
import com.portoperatingsystem.tv.protocol.command.NavigationKey

/** What pressing a remote key needs from the accessibility service, so the choice of action is testable. */
internal interface KeySurface {
    /** The device's SDK level. */
    val sdkInt: Int

    /** Performs a global action (`GLOBAL_ACTION_*`). */
    fun globalAction(action: Int): Boolean

    /** Clicks the node that has input focus. */
    fun clickFocused(): Boolean

    /** Moves focus one step in [direction] (a `View.FOCUS_*` constant), starting at the focused node. */
    fun moveFocus(direction: Int): Boolean
}

/**
 * Presses remote keys. Back and Home are global actions. The directional keys and Select use the D-pad global
 * actions where the platform has them (Android 13), and act on the focused node otherwise.
 */
internal class KeyDispatcher(private val surface: KeySurface) {
    fun press(key: NavigationKey): Boolean = when (key) {
        NavigationKey.BACK -> surface.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        NavigationKey.HOME -> surface.globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        NavigationKey.UP -> directional(DPAD_UP, View.FOCUS_UP)
        NavigationKey.DOWN -> directional(DPAD_DOWN, View.FOCUS_DOWN)
        NavigationKey.LEFT -> directional(DPAD_LEFT, View.FOCUS_LEFT)
        NavigationKey.RIGHT -> directional(DPAD_RIGHT, View.FOCUS_RIGHT)
        NavigationKey.SELECT -> if (hasDpadActions()) {
            surface.globalAction(DPAD_CENTER)
        } else {
            surface.clickFocused()
        }
    }

    private fun directional(action: Int, direction: Int): Boolean =
        if (hasDpadActions()) surface.globalAction(action) else surface.moveFocus(direction)

    private fun hasDpadActions(): Boolean = surface.sdkInt >= Build.VERSION_CODES.TIRAMISU

    private companion object {
        // AccessibilityService.GLOBAL_ACTION_DPAD_* (API 33). Spelled out because lint rejects the inlined
        // constants at minSdk 26, and they are only used once hasDpadActions() says the platform knows them.
        const val DPAD_UP = 16
        const val DPAD_DOWN = 17
        const val DPAD_LEFT = 18
        const val DPAD_RIGHT = 19
        const val DPAD_CENTER = 20
    }
}
