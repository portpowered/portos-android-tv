package com.portoperatingsystem.tv.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistrationScheduleTest {
    private var clock = 1_000L
    private val schedule = RegistrationSchedule(renewMillis = RegistrationSchedule.RENEW_MILLIS, now = { clock })

    @Test
    fun `is due before anything was sent`() {
        assertTrue(schedule.due())
        clock += 10 * RegistrationSchedule.RENEW_MILLIS
        assertTrue(schedule.due())
    }

    @Test
    fun `is not due again until 24 hours after the last successful send`() {
        schedule.sent()

        clock += RegistrationSchedule.RENEW_MILLIS - 1
        assertFalse(schedule.due())

        clock += 1
        assertTrue(schedule.due())
    }

    @Test
    fun `a renewal restarts the 24 hours`() {
        schedule.sent()
        clock += RegistrationSchedule.RENEW_MILLIS
        schedule.sent()

        clock += RegistrationSchedule.RENEW_MILLIS - 1
        assertFalse(schedule.due())
    }
}
