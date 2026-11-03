package com.portoperatingsystem.tv.playback

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val uri = "https://example.com/audio/chime.mp3"
    private var running: ServiceController<PlaybackService>? = null

    // Media3 session ids are unique per process, so every test releases its session.
    @AfterTest
    fun releaseTheSession() {
        running?.destroy()
    }

    private fun start(title: String?, offset: Long): PlaybackService {
        val intent = PlaybackService.playIntent(application, uri, title, offset)
        val controller = Robolectric.buildService(PlaybackService::class.java, intent).create().startCommand(0, 1)
        running = controller
        return controller.get()
    }

    @Test
    fun playsTheRequestedUriWithItsTitleFromTheOffset() {
        val player = assertNotNull(start("Chime", 1500).player)

        val item = assertNotNull(player.currentMediaItem)
        assertEquals(uri, item.localConfiguration?.uri.toString())
        assertEquals("Chime", item.mediaMetadata.title)
        assertEquals(1500L, player.currentPosition)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun stopsItselfWhenPlaybackIsStopped() {
        val service = start(null, 0)

        assertNotNull(service.player).stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
