package com.portoperatingsystem.tv.control

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeTransport(var probeReply: AdbReply, var runReply: AdbReply = AdbReply.Ok(0, "")) : AdbTransport {
    var probes = 0
    val commands = mutableListOf<String>()

    override fun probe(): AdbReply {
        probes++
        return probeReply
    }

    override fun run(command: String): AdbReply {
        commands += command
        return runReply
    }
}

// Robolectric only so the log lines LocalAdb writes do not hit the unmocked Android Log.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class LocalAdbTest {
    private var now = 1_000L
    private val minute = 60_000L

    private fun adb(transport: FakeTransport) = LocalAdb(transport) { now }

    @Test
    fun isNotAvailableUntilAProbeSucceeds() {
        val transport = FakeTransport(AdbReply.Ok(0, ""))
        val adb = adb(transport)

        assertFalse(adb.available)
        assertFalse(adb.tap(1, 2))
        assertTrue(transport.commands.isEmpty())

        assertTrue(adb.refresh())
        assertTrue(adb.available)
    }

    @Test
    fun injectsTapsAndKeysThroughTheShell() {
        val transport = FakeTransport(AdbReply.Ok(0, ""))
        val adb = adb(transport).also { it.refresh() }

        assertTrue(adb.tap(960, 540))
        assertTrue(adb.keyEvent(20))

        assertEquals(listOf("input tap 960 540", "input keyevent 20"), transport.commands)
    }

    @Test
    fun aTvWithNetworkDebuggingOffIsProbedRarely() {
        val transport = FakeTransport(AdbReply.NotListening)
        val adb = adb(transport)

        assertFalse(adb.refresh())
        assertFalse(adb.available)

        now += LocalAdb.RECHECK_WHEN_OFF_MILLIS - 1
        adb.refresh()
        assertEquals(1, transport.probes)

        now += 1
        adb.refresh()
        assertEquals(2, transport.probes)
    }

    @Test
    fun aKeyTheOwnerHasNotAllowedIsNotPromptedForAgainSoon() {
        val transport = FakeTransport(AdbReply.NotAuthorized)
        val adb = adb(transport)
        adb.refresh()

        // One short follow-up, then the long wait.
        now += LocalAdb.RECHECK_AFTER_FIRST_UNAUTHORIZED_MILLIS
        adb.refresh()
        assertEquals(2, transport.probes)

        now += 60 * minute
        adb.refresh()
        assertEquals(2, transport.probes)

        now += LocalAdb.RECHECK_WHEN_UNAUTHORIZED_MILLIS
        adb.refresh()
        assertEquals(3, transport.probes)
        assertFalse(adb.available)
    }

    @Test
    fun anAllowGivenAfterTheFirstPromptIsNoticedAtTheShortFollowUp() {
        val transport = FakeTransport(AdbReply.NotAuthorized)
        val adb = adb(transport)
        adb.refresh()

        now += LocalAdb.RECHECK_AFTER_FIRST_UNAUTHORIZED_MILLIS - 1
        assertFalse(adb.probeDue())

        transport.probeReply = AdbReply.Ok(0, "")
        now += 1
        assertTrue(adb.refresh())
        assertTrue(adb.available)
    }

    @Test
    fun theShortFollowUpComesAgainAfterAnotherKindOfReply() {
        val transport = FakeTransport(AdbReply.NotAuthorized)
        val adb = adb(transport)
        adb.refresh()
        now += LocalAdb.RECHECK_AFTER_FIRST_UNAUTHORIZED_MILLIS
        adb.refresh()

        transport.probeReply = AdbReply.NotListening
        now += LocalAdb.RECHECK_WHEN_UNAUTHORIZED_MILLIS
        adb.refresh()
        transport.probeReply = AdbReply.NotAuthorized
        now += LocalAdb.RECHECK_WHEN_OFF_MILLIS
        adb.refresh()
        val probes = transport.probes

        now += LocalAdb.RECHECK_AFTER_FIRST_UNAUTHORIZED_MILLIS
        adb.refresh()
        assertEquals(probes + 1, transport.probes)
    }

    @Test
    fun anAvailableDaemonIsReverifiedOccasionallyNotPerCommand() {
        val transport = FakeTransport(AdbReply.Ok(0, ""))
        val adb = adb(transport)
        adb.refresh()

        repeat(5) { adb.tap(1, 1) }
        adb.refresh()
        assertEquals(1, transport.probes)

        now += LocalAdb.RECHECK_WHEN_AVAILABLE_MILLIS
        assertTrue(adb.probeDue())
        assertFalse(adb.refresh())
        assertEquals(2, transport.probes)
    }

    @Test
    fun aFailingCommandMakesAdbUnavailableSoCallersFallBack() {
        val transport = FakeTransport(AdbReply.Ok(0, ""), runReply = AdbReply.Failed("connection reset"))
        val adb = adb(transport).also { it.refresh() }

        assertFalse(adb.tap(1, 1))
        assertFalse(adb.available)
        assertFalse(adb.tap(1, 1))
        assertEquals(1, transport.commands.size)
    }

    @Test
    fun aDaemonThatDisappearsIsNoticedAndReportedChanged() {
        val transport = FakeTransport(AdbReply.Ok(0, ""), runReply = AdbReply.NotListening)
        val adb = adb(transport).also { it.refresh() }
        adb.tap(1, 1)

        transport.probeReply = AdbReply.Ok(0, "")
        now += LocalAdb.RECHECK_WHEN_OFF_MILLIS
        assertTrue(adb.refresh())
        assertTrue(adb.available)
    }

    @Test
    fun aCommandThatExitsNonZeroFailsWithoutDisablingAdb() {
        val transport = FakeTransport(AdbReply.Ok(0, ""), runReply = AdbReply.Ok(1, "bad"))
        val adb = adb(transport).also { it.refresh() }

        assertFalse(adb.tap(1, 1))
        assertTrue(adb.available)
    }
}
