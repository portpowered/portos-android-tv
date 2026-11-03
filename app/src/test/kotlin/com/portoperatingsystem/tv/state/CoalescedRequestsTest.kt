package com.portoperatingsystem.tv.state

import kotlin.test.Test
import kotlin.test.assertEquals

class CoalescedRequestsTest {
    private val delays = mutableListOf<Long>()
    private var task: (() -> Unit)? = null
    private var runs = 0
    private val requests = CoalescedRequests(
        delayMillis = 2_000,
        schedule = { delay, scheduled ->
            delays += delay
            task = scheduled
        },
        action = { runs++ }
    )

    private fun fire() = checkNotNull(task).also { task = null }.invoke()

    @Test
    fun aBurstOfRequestsRunsTheActionOnce() {
        repeat(5) { requests.request() }
        assertEquals(listOf(2_000L), delays)
        assertEquals(0, runs)

        fire()
        assertEquals(1, runs)
    }

    @Test
    fun aRequestAfterTheActionRanSchedulesAnotherRun() {
        requests.request()
        fire()
        requests.request()
        fire()
        assertEquals(2, runs)
        assertEquals(listOf(2_000L, 2_000L), delays)
    }
}
