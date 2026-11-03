package com.portoperatingsystem.tv.control

import android.accessibilityservice.AccessibilityService
import android.view.View
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeSurface(override val sdkInt: Int, private val performs: Boolean = true) : KeySurface {
    val actions = mutableListOf<String>()

    override fun globalAction(action: Int): Boolean {
        actions += "global:$action"
        return performs
    }

    override fun clickFocused(): Boolean {
        actions += "click"
        return performs
    }

    override fun moveFocus(direction: Int): Boolean {
        actions += "focus:$direction"
        return performs
    }
}

class KeyDispatcherTest {
    private val android13 = 33
    private val android12 = 32

    private fun pressed(sdk: Int, key: NavigationKey): List<String> {
        val surface = FakeSurface(sdk)
        KeyDispatcher(surface).press(key)
        return surface.actions
    }

    @Test
    fun backAndHomeAreGlobalActionsOnEverySdk() {
        listOf(android12, android13).forEach { sdk ->
            assertEquals(
                listOf("global:${AccessibilityService.GLOBAL_ACTION_BACK}"),
                pressed(sdk, NavigationKey.BACK)
            )
            assertEquals(
                listOf("global:${AccessibilityService.GLOBAL_ACTION_HOME}"),
                pressed(sdk, NavigationKey.HOME)
            )
        }
    }

    @Test
    fun dpadKeysUseTheDpadGlobalActionsFromAndroid13() {
        val expected = mapOf(
            NavigationKey.UP to 16,
            NavigationKey.DOWN to 17,
            NavigationKey.LEFT to 18,
            NavigationKey.RIGHT to 19,
            NavigationKey.SELECT to 20
        )
        expected.forEach { (key, action) -> assertEquals(listOf("global:$action"), pressed(android13, key)) }
    }

    @Test
    fun arrowsMoveFocusBeforeAndroid13() {
        assertEquals(listOf("focus:${View.FOCUS_UP}"), pressed(android12, NavigationKey.UP))
        assertEquals(listOf("focus:${View.FOCUS_DOWN}"), pressed(android12, NavigationKey.DOWN))
        assertEquals(listOf("focus:${View.FOCUS_LEFT}"), pressed(android12, NavigationKey.LEFT))
        assertEquals(listOf("focus:${View.FOCUS_RIGHT}"), pressed(android12, NavigationKey.RIGHT))
    }

    @Test
    fun selectClicksTheFocusedNodeBeforeAndroid13() {
        assertEquals(listOf("click"), pressed(android12, NavigationKey.SELECT))
    }

    @Test
    fun reportsWhetherTheSystemPerformedTheKey() {
        assertEquals(true, KeyDispatcher(FakeSurface(android13, performs = true)).press(NavigationKey.SELECT))
        assertEquals(false, KeyDispatcher(FakeSurface(android13, performs = false)).press(NavigationKey.SELECT))
        assertEquals(false, KeyDispatcher(FakeSurface(android12, performs = false)).press(NavigationKey.LEFT))
    }
}
