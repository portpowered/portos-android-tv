package com.portoperatingsystem.tv.state

/**
 * Collapses a burst of "report soon" requests into one run of [action]. Android's media session fires several
 * callbacks within a second for one change (buffering, playing, a metadata refresh, a position update), and each one
 * used to start its own report. The first [request] schedules [action] after [delayMillis]; requests that arrive
 * before it runs are absorbed, and the action reads the state as it is when it runs, so it sees the last change.
 *
 * [schedule] runs a task after a delay.
 */
internal class CoalescedRequests(
    private val delayMillis: Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val action: () -> Unit
) {
    private var pending = false

    fun request() {
        synchronized(this) {
            if (pending) return
            pending = true
        }
        schedule(delayMillis, ::fire)
    }

    // Cleared before the action runs, so a change that lands while it runs asks for another report.
    private fun fire() {
        synchronized(this) { pending = false }
        action()
    }
}
