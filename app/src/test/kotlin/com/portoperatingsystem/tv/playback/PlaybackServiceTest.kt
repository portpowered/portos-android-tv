package com.portoperatingsystem.tv.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.protocol.command.Command
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
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
    private val server = MockWebServer()
    private val mediaDirectory = File(application.cacheDir, "media")

    // Media3 session ids are unique per process, so every test releases its session.
    @AfterTest
    fun releaseTheSession() {
        running?.destroy()
        server.shutdown()
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

    private fun startFile(loop: Boolean): PlaybackService {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(FILE_BYTES))))
        server.start()
        val url = server.url("/dance.mp4").toString()
        val intent = PlaybackService.playFileIntent(
            application,
            Command.PlayFile(url, "video/mp4", video = true, loop = loop, title = "Dance")
        )
        val controller = Robolectric.buildService(PlaybackService::class.java, intent).create().startCommand(0, 1)
        running = controller
        awaitItem(controller.get())
        return controller.get()
    }

    // The download runs on its own thread and hands the result to the main looper.
    private fun awaitItem(service: PlaybackService) {
        repeat(WAIT_ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (service.player?.currentMediaItem != null) return
            Thread.sleep(WAIT_MILLIS)
        }
        error("the downloaded file never started playing")
    }

    @Test
    fun playsADownloadedFileOnRepeatWhenLooping() {
        val player = assertNotNull(startFile(loop = true).player)

        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        assertEquals("Dance", player.currentMediaItem?.mediaMetadata?.title)
        val uri = assertNotNull(player.currentMediaItem?.localConfiguration?.uri)
        assertEquals("file", uri.scheme)
        assertEquals(listOf(File(assertNotNull(uri.path))), mediaDirectory.listFiles()?.toList())
        assertTrue(shadowOf(application).nextStartedActivity.component?.className?.endsWith("PlayerActivity") == true)
    }

    @Test
    fun playsADownloadedFileOnceWithoutLooping() {
        assertEquals(Player.REPEAT_MODE_OFF, assertNotNull(startFile(loop = false).player).repeatMode)
    }

    @Test
    fun deletesDownloadedFilesWhenDestroyed() {
        startFile(loop = true)
        assertEquals(1, mediaDirectory.listFiles()?.size)

        running?.destroy()
        running = null

        assertEquals(0, mediaDirectory.listFiles()?.size)
    }

    @Test
    fun stopsItselfWhenTheDownloadFails() {
        server.enqueue(MockResponse().setResponseCode(HTTP_GONE))
        server.start()
        val url = server.url("/gone.mp4").toString()
        val intent = PlaybackService.playFileIntent(
            application,
            Command.PlayFile(url, "video/mp4", video = true, loop = false)
        )
        val controller = Robolectric.buildService(PlaybackService::class.java, intent).create().startCommand(0, 1)
        running = controller

        repeat(WAIT_ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shadowOf(controller.get()).isStoppedBySelf) return
            Thread.sleep(WAIT_MILLIS)
        }
        error("the service did not stop after a failed download")
    }

    private companion object {
        const val FILE_BYTES = 64
        const val WAIT_ATTEMPTS = 250
        const val WAIT_MILLIS = 20L
        const val HTTP_GONE = 410
    }
}
