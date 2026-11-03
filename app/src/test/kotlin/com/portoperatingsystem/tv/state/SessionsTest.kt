package com.portoperatingsystem.tv.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionsTest {
    @Test
    fun noActiveSessionWithMediaAccessReportsStopped() {
        assertEquals(Playback.STOPPED, reportedPlayback(emptyList<Playback>(), null) { it })
    }

    @Test
    fun withoutMediaAccessPlaybackStaysUnknown() {
        assertNull(reportedPlayback<Playback>(null, null) { it })
    }

    @Test
    fun theChosenSessionGivesThePlayback() {
        val sessions = listOf(Playback.PAUSED, Playback.PLAYING)
        assertEquals(Playback.PLAYING, reportedPlayback(sessions, mostRelevant(sessions) { it }) { it })
    }
}
