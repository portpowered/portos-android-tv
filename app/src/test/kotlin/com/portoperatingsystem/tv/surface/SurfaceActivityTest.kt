package com.portoperatingsystem.tv.surface

import android.app.Application
import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A plain Application: only the intent is built.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class SurfaceActivityTest {
    @Test
    fun `an agent close sends the TV to the launcher home in a new task`() {
        val intent = SurfaceActivity.homeIntent()

        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
