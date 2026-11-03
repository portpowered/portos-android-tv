package com.portoperatingsystem.tv.control

import android.app.Application
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.playback.PlaybackService
import com.portoperatingsystem.tv.ui.NotificationActivity
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class AndroidDeviceTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val device = AndroidDevice(application)

    @Test
    fun startsTheNotificationActivityInItsOwnTask() {
        device.showNotification("Dinner", "Dinner is ready", 30_000)

        val started = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(NotificationActivity::class.java.name, started.component?.className)
        assertEquals("Dinner", started.getStringExtra("title"))
        assertEquals("Dinner is ready", started.getStringExtra("body"))
        assertEquals(30_000L, started.getLongExtra("displayMillis", 0))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun startsThePlaybackServiceWithTheRequestedAudio() {
        device.playUri("https://example.com/chime.mp3", "Chime", 1500)

        val started = assertNotNull(shadowOf(application).nextStartedService)
        assertEquals(PlaybackService::class.java.name, started.component?.className)
        assertEquals("https://example.com/chime.mp3", started.getStringExtra("uri"))
        assertEquals("Chime", started.getStringExtra("title"))
        assertEquals(1500L, started.getLongExtra("offsetMillis", 0))
    }

    @Test
    fun theNotificationActivityShowsTheTextAndClosesOnBack() {
        val intent = NotificationActivity.intent(application, "Dinner", "Dinner is ready", 30_000)
        val controller = Robolectric.buildActivity(NotificationActivity::class.java, intent).setup()

        assertEquals(listOf("Dinner", "Dinner is ready"), texts(controller.get().window.decorView))

        controller.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        controller.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun theNotificationActivityClosesAfterTheDisplayTime() {
        val intent = NotificationActivity.intent(application, "Dinner", "Dinner is ready", 15_000)
        val controller = Robolectric.buildActivity(NotificationActivity::class.java, intent).setup()

        ShadowLooper.idleMainLooper(14_000, TimeUnit.MILLISECONDS)
        assertFalse(controller.get().isFinishing)
        ShadowLooper.idleMainLooper(1_100, TimeUnit.MILLISECONDS)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun aSecondNotificationReplacesTheFirstAndRestartsTheTimer() {
        val first = NotificationActivity.intent(application, "One", "First", 15_000)
        val controller = Robolectric.buildActivity(NotificationActivity::class.java, first).setup()
        ShadowLooper.idleMainLooper(10_000, TimeUnit.MILLISECONDS)

        controller.newIntent(NotificationActivity.intent(application, "Two", "Second", 15_000))

        assertEquals(listOf("Two", "Second"), texts(controller.get().window.decorView))
        ShadowLooper.idleMainLooper(10_000, TimeUnit.MILLISECONDS)
        assertFalse(controller.get().isFinishing)
        ShadowLooper.idleMainLooper(5_000, TimeUnit.MILLISECONDS)
        assertTrue(controller.get().isFinishing)
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
