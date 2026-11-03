package com.portoperatingsystem.tv.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayingReportsTest {
    private class Scheduler {
        val delays = mutableListOf<Long>()
        var pending: (() -> Unit)? = null
        var cancelled = 0

        fun schedule(delay: Long, task: () -> Unit): () -> Unit {
            delays += delay
            pending = task
            return {
                cancelled++
                pending = null
            }
        }

        fun fire() = checkNotNull(pending).also { pending = null }.invoke()
    }

    @Test
    fun reportsOnceAMinuteWhilePlayingAndStopsWhenItDoesNot() {
        val scheduler = Scheduler()
        var playing = true
        lateinit var reports: PlayingReports
        var reported = 0
        reports = PlayingReports(60_000, scheduler::schedule) {
            reported++
            reports.update(playing)
        }
        reports.update(true)
        reports.update(true)
        assertEquals(listOf(60_000L), scheduler.delays)

        scheduler.fire()
        scheduler.fire()
        assertEquals(2, reported)
        assertEquals(listOf(60_000L, 60_000L, 60_000L), scheduler.delays)

        playing = false
        scheduler.fire()
        assertTrue(scheduler.pending == null)
        assertEquals(3, reported)
    }

    @Test
    fun pausingCancelsThePendingReport() {
        val scheduler = Scheduler()
        val reports = PlayingReports(60_000, scheduler::schedule) {}
        reports.update(true)
        reports.update(false)
        assertEquals(1, scheduler.cancelled)
        assertTrue(scheduler.pending == null)
    }
}
