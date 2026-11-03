package com.portoperatingsystem.tv.control

import com.portoperatingsystem.tv.state.Playback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeSession(
    override val playback: Playback? = Playback.PLAYING,
    override val supportsSeek: Boolean = true,
    override val positionMillis: Long? = 60_000,
    override val durationMillis: Long? = 600_000
) : PlayerSession {
    val seeks = mutableListOf<Long>()

    override fun seekTo(positionMillis: Long) {
        seeks += positionMillis
    }
}

class SessionControlTest {
    private fun control(vararg sessions: PlayerSession) = SessionControl { sessions.toList() }

    @Test
    fun seeksTheSessionThatIsPlayingNotTheIdleOneListedFirst() {
        val idle = FakeSession(playback = Playback.STOPPED)
        val playing = FakeSession()
        assertEquals(Execution.Done, control(idle, playing).seekTo(90_000))
        assertTrue(idle.seeks.isEmpty())
        assertEquals(listOf(90_000L), playing.seeks)
    }

    @Test
    fun skipsRelativeToTheCurrentPosition() {
        val session = FakeSession(positionMillis = 60_000)
        val control = control(session)
        assertEquals(Execution.Done, control.skip(10_000))
        assertEquals(Execution.Done, control.skip(-30_000))
        assertEquals(listOf(70_000L, 30_000L), session.seeks)
    }

    @Test
    fun keepsThePositionInsideTheItem() {
        val session = FakeSession(positionMillis = 5_000, durationMillis = 100_000)
        val control = control(session)
        control.skip(-30_000)
        control.seekTo(500_000)
        assertEquals(listOf(0L, 100_000L), session.seeks)
    }

    @Test
    fun clampsOnlyAtZeroWhenTheLengthIsUnknown() {
        val session = FakeSession(positionMillis = 5_000, durationMillis = null)
        val control = control(session)
        control.skip(-30_000)
        control.seekTo(500_000)
        assertEquals(listOf(0L, 500_000L), session.seeks)
    }

    @Test
    fun startOverIsASeekToZero() {
        val session = FakeSession()
        assertEquals(Execution.Done, control(session).seekTo(0))
        assertEquals(listOf(0L), session.seeks)
    }

    @Test
    fun failsClearlyWhenTheAppDoesNotAdvertiseSeek() {
        val session = FakeSession(supportsSeek = false)
        val control = control(session)
        val failed = Execution.Failed(ExecutionFailure.SEEK_NOT_SUPPORTED)
        assertEquals(failed, control.seekTo(1_000))
        assertEquals(failed, control.skip(1_000))
        assertTrue(session.seeks.isEmpty())
    }

    @Test
    fun failsWhenASkipHasNoPositionToStartFrom() {
        val session = FakeSession(positionMillis = null)
        assertEquals(Execution.Failed(ExecutionFailure.POSITION_UNKNOWN), control(session).skip(1_000))
        assertTrue(session.seeks.isEmpty())
    }

    @Test
    fun failsWhenThereIsNoSession() {
        assertEquals(Execution.Failed(ExecutionFailure.NO_MEDIA_SESSION), control().seekTo(1_000))
    }

    @Test
    fun failsWhenMediaSessionsAreHidden() {
        assertEquals(
            Execution.Failed(ExecutionFailure.MEDIA_ACCESS_NOT_ALLOWED),
            SessionControl { null }.seekTo(1_000)
        )
    }
}
