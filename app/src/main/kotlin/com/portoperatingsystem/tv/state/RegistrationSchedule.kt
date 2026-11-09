package com.portoperatingsystem.tv.state

import android.os.SystemClock

/**
 * When the `generativeUi` capability registration is due: on the first report after the app starts, then again once
 * [renewMillis] has passed since it was last sent successfully. The backend expires a registration after 720 hours,
 * so a daily renewal keeps it alive. Held in memory; a restart sends it anyway.
 */
internal class RegistrationSchedule(
    private val renewMillis: Long = RENEW_MILLIS,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    @Volatile private var sentAt: Long? = null

    fun due(): Boolean = sentAt?.let { now() - it >= renewMillis } ?: true

    /** Records that the registration was delivered, so the next one is due [renewMillis] from now. */
    fun sent() {
        sentAt = now()
    }

    companion object {
        const val RENEW_MILLIS = 24 * 60 * 60 * 1000L
    }
}
