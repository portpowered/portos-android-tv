package com.portoperatingsystem.tv.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.protocol.command.Command
import com.portoperatingsystem.tv.protocol.command.ImageFile
import com.portoperatingsystem.tv.protocol.command.Part
import com.portoperatingsystem.tv.protocol.command.SlideshowCommand
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class SlideshowTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val mediaDirectory = File(application.cacheDir, "media")
    private var running: ServiceController<PlaybackService>? = null
    private var startId = 0

    @BeforeTest
    fun serveImages() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.contains("gone") == true) {
                    MockResponse().setResponseCode(HTTP_GONE)
                } else {
                    MockResponse().setBody(Buffer().write(ByteArray(IMAGE_BYTES)))
                }
        }
        server.start()
    }

    // Media3 session ids are unique per process, so every test releases its session.
    @AfterTest
    fun releaseTheSession() {
        running?.destroy()
        server.shutdown()
    }

    private fun images(vararg names: String) = names.map { ImageFile(server.url("/$it.jpg").toString(), "image/jpeg") }

    private fun send(command: SlideshowCommand): PlaybackService {
        val intent = PlaybackService.slideshowIntent(application, command)
        val controller = running
        if (controller != null) {
            controller.get().onStartCommand(intent, 0, ++startId)
            return controller.get()
        }
        return Robolectric.buildService(PlaybackService::class.java, intent).create().startCommand(0, ++startId)
            .also { running = it }
            .get()
    }

    private fun show(vararg names: String, loop: Boolean = false): Command.ShowImages =
        Command.ShowImages(Part("s", 0, 1), images(*names), "Beach week", SECONDS, loop)

    // Downloads run on their own thread and hand the result to the main looper.
    private fun awaitCount(service: PlaybackService, count: Int): Player {
        repeat(WAIT_ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            val player = service.player
            if (player != null && player.mediaItemCount == count) return player
            Thread.sleep(WAIT_MILLIS)
        }
        error("the slideshow never held $count images")
    }

    // Lets a download that would change the slideshow finish, so a test can check that it did not.
    private fun settle() {
        Thread.sleep(SETTLE_MILLIS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun mediaIds(player: Player) = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    @Test
    fun showsEveryPartAsOneSlideshow() {
        send(Command.ShowImages(Part("s", 1, 2), images("c"), "Beach week", SECONDS, loop = false))
        val service = send(Command.ShowImages(Part("s", 0, 2), images("a", "b"), "Beach week", SECONDS, loop = false))

        val player = awaitCount(service, 3)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        val item = player.getMediaItemAt(0)
        assertEquals(SECONDS * 1000L, item.localConfiguration?.imageDurationMs)
        assertEquals("image/jpeg", item.localConfiguration?.mimeType)
        assertEquals("Beach week", item.mediaMetadata.title)
        assertEquals(3, mediaDirectory.listFiles()?.size)
        assertTrue(shadowOf(application).nextStartedActivity.component?.className?.endsWith("PlayerActivity") == true)
    }

    @Test
    fun aSlideshowWithoutLoopPausesAtTheEndOfItsLastImage() {
        val player = awaitCount(send(show("a", "b")), 2) as ExoPlayer

        assertFalse(player.pauseAtEndOfMediaItems)
        player.seekTo(1, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(player.pauseAtEndOfMediaItems)
    }

    @Test
    fun aLoopingSlideshowStartsOver() {
        val player = awaitCount(send(show("a", "b", loop = true)), 2) as ExoPlayer

        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        player.seekTo(1, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(player.pauseAtEndOfMediaItems)
    }

    @Test
    fun addsImagesAtAPositionOrAtTheEnd() {
        val service = send(show("a", "b"))
        val shown = mediaIds(awaitCount(service, 2))

        send(Command.AddImages(Part("t", 0, 1), images("x"), position = 2))
        val player = awaitCount(service, 3)
        assertEquals(shown[0], mediaIds(player)[0])
        assertEquals(shown[1], mediaIds(player)[2])
        assertEquals(SECONDS * 1000L, player.getMediaItemAt(1).localConfiguration?.imageDurationMs)
        assertEquals("Beach week", player.getMediaItemAt(1).mediaMetadata.title)

        send(Command.AddImages(Part("u", 0, 1), images("y"), position = null))
        assertEquals(shown[1], mediaIds(awaitCount(service, 4))[2])
        assertEquals(4, mediaDirectory.listFiles()?.size)
    }

    @Test
    fun ignoresAnAddThatWouldPassThirtyImages() {
        val service = send(show(*Array(29) { "i$it" }))
        awaitCount(service, 29)

        send(Command.AddImages(Part("t", 0, 1), images("x", "y"), position = null))
        settle()
        assertEquals(29, assertNotNull(service.player).mediaItemCount)
        assertEquals(29, mediaDirectory.listFiles()?.size)
    }

    @Test
    fun removesImagesAndTheirFiles() {
        val service = send(show("a", "b", "c"))
        val shown = mediaIds(awaitCount(service, 3))

        send(Command.RemoveImages(listOf(3, 1)))
        assertEquals(listOf(shown[1]), mediaIds(awaitCount(service, 1)))
        assertEquals(1, mediaDirectory.listFiles()?.size)
    }

    @Test
    fun ignoresARemovalPastTheEnd() {
        val service = send(show("a", "b"))
        awaitCount(service, 2)

        send(Command.RemoveImages(listOf(1, 3)))
        assertEquals(2, assertNotNull(service.player).mediaItemCount)
    }

    @Test
    fun removingEveryImageEndsTheSlideshow() {
        val service = send(show("a", "b"))
        awaitCount(service, 2)

        send(Command.RemoveImages(listOf(1, 2)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun changesWithNoSlideshowStopTheIdleService() {
        assertTrue(shadowOf(send(Command.RemoveImages(listOf(1)))).isStoppedBySelf)
    }

    @Test
    fun aFailedDownloadShowsNothing() {
        val service = send(Command.ShowImages(Part("s", 0, 1), images("a", "gone"), null, SECONDS, loop = false))
        repeat(WAIT_ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shadowOf(service).isStoppedBySelf) {
                assertEquals(emptyList(), mediaDirectory.listFiles()?.map { it.name to it.length() }.orEmpty())
                return
            }
            Thread.sleep(WAIT_MILLIS)
        }
        error("the service did not stop after a failed download")
    }

    private companion object {
        const val SECONDS = 5
        const val IMAGE_BYTES = 64
        const val WAIT_ATTEMPTS = 250
        const val WAIT_MILLIS = 20L
        const val SETTLE_MILLIS = 500L
        const val HTTP_GONE = 410
    }
}
