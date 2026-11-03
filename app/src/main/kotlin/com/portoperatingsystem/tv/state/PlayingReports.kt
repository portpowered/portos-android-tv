package com.portoperatingsystem.tv.state

/**
 * Keeps `positionMs` close while something plays. Android only notifies on session changes, and WorkManager cannot run
 * more often than every 15 minutes, so while a session is PLAYING this schedules one report per [delayMillis]. Each
 * report calls [update] again, which schedules the next one while playback continues and stops when it pauses or
 * stops. The process stays alive meanwhile because the media-session listener is bound to it.
 *
 * [schedule] runs a task after a delay and returns a function that cancels it.
 */
internal class PlayingReports(
    private val delayMillis: Long,
    private val schedule: (Long, () -> Unit) -> () -> Unit,
    private val report: () -> Unit
) {
    private var cancelPending: (() -> Unit)? = null

    /** Called with whether a session is playing after every report. */
    @Synchronized
    fun update(playing: Boolean) {
        if (!playing) {
            cancelPending?.invoke()
            cancelPending = null
        } else if (cancelPending == null) {
            cancelPending = schedule(delayMillis, ::fire)
        }
    }

    // The fired task is no longer pending: clear it first so the report's own update schedules the next one.
    private fun fire() {
        synchronized(this) { cancelPending = null }
        report()
    }
}
