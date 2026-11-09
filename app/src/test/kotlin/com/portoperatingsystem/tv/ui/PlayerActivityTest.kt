package com.portoperatingsystem.tv.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlayerActivityTest {
    // The player screen opens with nothing over the media, for video and audio alike, and playback starting, pausing
    // or ending never brings the controls up on its own.
    @Test
    fun opensWithoutTheControlOverlay() {
        for (video in listOf(true, false)) {
            val intent = PlayerActivity.intent(ApplicationProvider.getApplicationContext(), video)
            val activity = Robolectric.buildActivity(PlayerActivity::class.java, intent).create().get()
            assertFalse(activity.view.isControllerFullyVisible, "controls showed on opening (video=$video)")
            assertFalse(activity.view.controllerAutoShow, "playback would show the controls (video=$video)")
            assertEquals(5_000, activity.view.controllerShowTimeoutMs)
        }
    }
}
