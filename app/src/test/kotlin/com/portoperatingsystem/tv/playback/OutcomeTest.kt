package com.portoperatingsystem.tv.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.protocol.command.Command
import com.portoperatingsystem.tv.protocol.command.ImageFile
import com.portoperatingsystem.tv.protocol.command.Part
import com.portoperatingsystem.tv.protocol.command.SlideshowCommand
import com.portoperatingsystem.tv.state.CommandOutcome
import com.portoperatingsystem.tv.state.FailureCode
import com.portoperatingsystem.tv.state.OutcomeSink
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

/** What the TV tells Port OS about the five commands it answers: when, and with what. */
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class OutcomeTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val answers = CopyOnWriteArrayList<Pair<String, CommandOutcome>>()
    private var running: ServiceController<PlaybackService>? = null
    private var startId = 0

    @BeforeTest
    fun serveImages() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    "gone" in path -> MockResponse().setResponseCode(HTTP_GONE)
                    "slow" in path -> body().setBodyDelay(SLOW_MILLIS, TimeUnit.MILLISECONDS)
                    else -> body()
                }
            }
        }
        server.start()
        PlaybackService.outcomes = OutcomeSink { id, outcome -> answers += id to outcome }
    }

    // Media3 session ids are unique per process, so every test releases its session.
    @AfterTest
    fun releaseTheSession() {
        running?.destroy()
        server.shutdown()
        // The cache directory outlives a test; leave nothing for the next one to count.
        File(application.cacheDir, "media").listFiles()?.forEach(File::delete)
        PlaybackService.outcomes = OutcomeSink { _, _ -> }
    }

    private fun body() = MockResponse().setBody(Buffer().write(PNG))

    private fun images(vararg names: String) = names.map { ImageFile(server.url("/$it.jpg").toString(), "image/png") }

    private fun send(command: SlideshowCommand, id: String = "remove"): PlaybackService {
        val intent = PlaybackService.slideshowIntent(application, command, id)
        val controller = running
        if (controller != null) {
            controller.get().onStartCommand(intent, 0, ++startId)
            return controller.get()
        }
        return Robolectric.buildService(PlaybackService::class.java, intent).create().startCommand(0, ++startId)
            .also { running = it }
            .get()
    }

    private fun sendFile(file: Command.PlayFile, id: String): PlaybackService {
        val intent = PlaybackService.playFileIntent(application, file, id)
        val controller = running
        if (controller != null) {
            controller.get().onStartCommand(intent, 0, ++startId)
            return controller.get()
        }
        return Robolectric.buildService(PlaybackService::class.java, intent).create().startCommand(0, ++startId)
            .also { running = it }
            .get()
    }

    private fun file(name: String, loop: Boolean = false) =
        Command.PlayFile(server.url("/$name").toString(), "video/mp4", video = true, loop = loop)

    private fun show(set: String, vararg names: String, loop: Boolean = false) =
        Command.ShowImages(Part(set, 0, 1), images(*names), "Beach week", SECONDS, loop)

    private fun add(set: String, vararg names: String) = Command.AddImages(Part(set, 0, 1), images(*names), null)

    private fun await(what: String, condition: () -> Boolean) {
        repeat(WAIT_ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(WAIT_MILLIS)
        }
        error("never happened: $what")
    }

    private fun answerTo(id: String): CommandOutcome {
        await("an answer to $id") { answers.any { it.first == id } }
        return answers.first { it.first == id }.second
    }

    private fun failure(id: String): CommandOutcome.Failed = answerTo(id) as CommandOutcome.Failed

    private fun assertAnsweredOnce(id: String) = assertEquals(1, answers.count { it.first == id })

    private fun settle() {
        Thread.sleep(SETTLE_MILLIS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun anAddWithNoSlideshowFailsWithNoSlideshow() {
        send(add("a1", "x"))

        assertEquals(FailureCode.NO_SLIDESHOW, failure("a1").code)
        assertAnsweredOnce("a1")
    }

    @Test
    fun aRemoveWithNoSlideshowFailsWithNoSlideshow() {
        send(Command.RemoveImages(listOf(1)), "r1")

        assertEquals(FailureCode.NO_SLIDESHOW, failure("r1").code)
    }

    @Test
    fun anAddPastThirtyFailsWithTooManyImagesAndChangesNothing() {
        val service = send(show("s1", *Array(29) { "i$it" }))
        await("29 images") { service.player?.mediaItemCount == 29 }

        send(add("a2", "x", "y"))

        assertEquals(FailureCode.TOO_MANY_IMAGES, failure("a2").code)
        assertEquals(29, assertNotNull(service.player).mediaItemCount)
    }

    @Test
    fun aRemovePastTheEndNamesThePositionAndTheLength() {
        val service = send(show("s2", "a", "b"))
        await("2 images") { service.player?.mediaItemCount == 2 }

        send(Command.RemoveImages(listOf(1, 3)), "r2")

        val failed = failure("r2")
        assertEquals(FailureCode.POSITION_OUT_OF_RANGE, failed.code)
        assertEquals("position 3 is past the end; the slideshow has 2 images", failed.message)
        assertEquals(2, assertNotNull(service.player).mediaItemCount)
    }

    @Test
    fun aFailedImageDownloadNamesTheImageCountingFromOne() {
        send(show("s3", "a", "gone"))

        val failed = failure("s3")
        assertEquals(FailureCode.DOWNLOAD_FAILED, failed.code)
        assertEquals("image 2 could not be downloaded", failed.message)
        assertAnsweredOnce("s3")
    }

    @Test
    fun aFailedAddDownloadFailsWithDownloadFailed() {
        val service = send(show("s4", "a"))
        await("1 image") { service.player?.mediaItemCount == 1 }

        send(add("a4", "x", "gone"))

        assertEquals("image 2 could not be downloaded", failure("a4").message)
        assertEquals(1, assertNotNull(service.player).mediaItemCount)
    }

    @Test
    fun aFailedFileDownloadFailsWithDownloadFailed() {
        sendFile(file("gone.mp4"), "v1")

        assertEquals(FailureCode.DOWNLOAD_FAILED, failure("v1").code)
    }

    @Test
    fun aShowWhileAnotherStillDownloadsReplacesIt() {
        send(show("slowset", "slow"))
        val service = send(show("fastset", "b"))

        assertEquals(FailureCode.REPLACED, failure("slowset").code)
        await("the second slideshow") { service.player?.mediaItemCount == 1 }
        settle()
        assertAnsweredOnce("slowset")
        assertEquals(1, assertNotNull(service.player).mediaItemCount)
    }

    @Test
    fun aFileReplacesASlideshowStillDownloading() {
        send(show("slowset2", "slow"))
        sendFile(file("clip.mp4"), "v2")

        assertEquals(FailureCode.REPLACED, failure("slowset2").code)
        settle()
    }

    @Test
    fun aShowAnswersOnceWhenItsFirstImageShows() {
        val service = send(show("s5", "a", "b", "c", loop = true))
        await("3 images") { service.player?.mediaItemCount == 3 }
        settle()
        assertEquals(emptyList(), answers.toList(), "nothing is answered before the first image shows")

        service.answerWhenShown.onPlaybackStateChanged(Player.STATE_READY)
        service.answerWhenShown.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(CommandOutcome.Acted(SECONDS, true, 3), answerTo("s5"))
        settle()
        assertAnsweredOnce("s5")
    }

    @Test
    fun anAddAndARemoveAnswerOnceTheItemsChangedWithTheSlideshowSettings() {
        val service = send(show("s6", "a", "b"))
        await("2 images") { service.player?.mediaItemCount == 2 }

        send(add("a6", "x"))
        assertEquals(CommandOutcome.Acted(SECONDS, false, 3), answerTo("a6"))
        assertEquals(3, assertNotNull(service.player).mediaItemCount)

        send(Command.RemoveImages(listOf(1, 2)), "r6")
        assertEquals(CommandOutcome.Acted(SECONDS, false, 1), answerTo("r6"))
        assertEquals(1, assertNotNull(service.player).mediaItemCount)
    }

    @Test
    fun aFileAnswersWithItsLoopWhenItPlays() {
        val service = sendFile(file("clip.mp4", loop = true), "v3")
        await("the file") { service.player?.mediaItemCount == 1 }

        service.answerWhenShown.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(CommandOutcome.Acted(null, true, 1), answerTo("v3"))
        assertAnsweredOnce("v3")
    }

    @Test
    fun aPlayerErrorOnTheFirstImageFailsWithPlaybackFailed() {
        val service = send(show("s8", "a"))
        await("1 image") { service.player?.mediaItemCount == 1 }

        service.answerWhenShown.onPlayerError(
            PlaybackException("decoding failed", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        )
        service.answerWhenShown.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(FailureCode.PLAYBACK_FAILED, failure("s8").code)
        assertAnsweredOnce("s8")
    }

    @Test
    fun previousStepsBackAnImageEvenAfterThreeSeconds() {
        val service = send(show("s7", "a", "b", "c"))
        val player = assertNotNull(service.player)
        await("3 images") { player.mediaItemCount == 3 }

        player.seekTo(2, PAST_RESTART_MILLIS)
        player.seekToPrevious()

        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun previousRestartsAFileThatHasNoPreviousItem() {
        val intent = PlaybackService.playIntent(application, "https://example.com/audio/chime.mp3", "Chime", 0L)
        val service = Robolectric.buildService(PlaybackService::class.java, intent).create().startCommand(0, 1)
        running = service
        val player = assertNotNull(service.get().player)
        player.seekTo(PAST_RESTART_MILLIS)

        player.seekToPrevious()

        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(0L, player.currentPosition)
    }

    private companion object {
        const val SECONDS = 5
        val PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        const val WAIT_ATTEMPTS = 250
        const val WAIT_MILLIS = 20L
        const val SETTLE_MILLIS = 600L
        const val SLOW_MILLIS = 300L
        const val HTTP_GONE = 410
        const val PAST_RESTART_MILLIS = 4_000L
    }
}
