package com.portoperatingsystem.tv.control

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.ComponentName
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class PortOsAccessibilityServiceTest {
    @Test
    fun theConnectedServiceIsAvailableUntilItIsUnbound() {
        val service = Robolectric.setupService(PortOsAccessibilityService::class.java)
        assertNull(PortOsAccessibilityService.instance)

        service.onServiceConnected()
        assertSame(service, PortOsAccessibilityService.instance)

        service.onUnbind(null)
        assertNull(PortOsAccessibilityService.instance)
    }

    @Test
    fun backAndHomeArePerformedAsGlobalActions() {
        val service = Robolectric.setupService(PortOsAccessibilityService::class.java)

        service.press(NavigationKey.BACK)
        service.press(NavigationKey.HOME)

        assertEquals(
            listOf(AccessibilityService.GLOBAL_ACTION_BACK, AccessibilityService.GLOBAL_ACTION_HOME),
            shadowOf(service).globalActionsPerformed
        )
    }

    @Test
    fun aTapEndsInAnOutcomeInsteadOfHanging() {
        val service = Robolectric.setupService(PortOsAccessibilityService::class.java)

        val outcome = AtomicReference<GestureOutcome>()
        thread { outcome.set(service.tap(TapGesture.Point(480f, 270f))) }.join()

        assertTrue(outcome.get() != null)
    }

    @Test
    fun theSettingListsTheServiceInShortAndFlattenedForm() {
        val service = ComponentName("com.portoperatingsystem.tv", "com.portoperatingsystem.tv.control.Svc")
        assertTrue(AccessibilitySetting.lists("a/.B:${service.flattenToString()}", service))
        assertTrue(AccessibilitySetting.lists(service.flattenToShortString(), service))
        assertFalse(AccessibilitySetting.lists("a/.B:c/.D", service))
        assertFalse(AccessibilitySetting.lists("", service))
        assertFalse(AccessibilitySetting.lists(null, service))
    }
}
