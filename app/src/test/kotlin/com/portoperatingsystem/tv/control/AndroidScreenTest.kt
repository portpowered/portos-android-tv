package com.portoperatingsystem.tv.control

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class AndroidScreenTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val requested = mutableListOf<String>()
    private val adb = FakeAdb()
    private val router = InputRouter(
        adb = adb,
        accessibility = ServiceInput(application),
        display = { DisplaySize(1920, 1080) },
        sdkInt = 31,
        hasTouchInput = true
    )
    private val screen = AndroidScreen(application, router) { requested += it }

    @Test
    fun capturingTheScreenAsksTheCaptureForTheRequest() {
        screen.captureScreen("req-9")

        assertEquals(listOf("req-9"), requested)
    }

    @Test
    fun aKeyIsReportedFailedWhileTheServiceIsOff() {
        assertEquals(
            Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED),
            screen.pressKey(NavigationKey.SELECT)
        )
    }

    @Test
    fun aTapIsReportedFailedWhileTheServiceIsOff() {
        assertEquals(Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED), screen.tap(0.5, 0.5))
        assertNull(PortOsAccessibilityService.instance)
    }

    @Test
    fun aTapIsReportedFailedWhileTheServiceIsEnabledButNotConnected() {
        enableService()

        assertEquals(Execution.Failed(ExecutionFailure.TAP_NOT_DISPATCHED), screen.tap(0.5, 0.5))
    }

    @Test
    fun aTapGoesThroughLocalAdbInDisplayPixels() {
        adb.available = true

        assertEquals(Execution.Done, screen.tap(0.5, 0.25))
        assertEquals(listOf(960 to 270), adb.taps)
    }

    @Test
    fun theServiceCountsAsEnabledOnlyWhenTheSettingsListIt() {
        val resolver = application.contentResolver
        assertFalse(screen.isAccessibilityEnabled())

        Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other/.Service:${application.packageName}/${PortOsAccessibilityService::class.java.name}"
        )
        assertFalse(screen.isAccessibilityEnabled())

        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        assertTrue(screen.isAccessibilityEnabled())
    }

    private fun enableService() {
        Settings.Secure.putString(
            application.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${application.packageName}/${PortOsAccessibilityService::class.java.name}"
        )
        Settings.Secure.putInt(application.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
}
