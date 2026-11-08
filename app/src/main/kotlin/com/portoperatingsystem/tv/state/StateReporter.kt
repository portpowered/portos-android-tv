package com.portoperatingsystem.tv.state

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.util.Log
import com.portoperatingsystem.tv.auth.Session
import com.portoperatingsystem.tv.cloud.PortOsClient
import com.portoperatingsystem.tv.cloud.PortOsException
import com.portoperatingsystem.tv.control.AccessibilitySetting
import com.portoperatingsystem.tv.control.AndroidDevice
import com.portoperatingsystem.tv.control.InputCapabilities
import com.portoperatingsystem.tv.control.isMuted
import com.portoperatingsystem.tv.protocol.media.MediaIdentity
import kotlin.math.roundToInt

/** Reads the TV's current state and posts it to Port OS. Safe to call from any background thread. */
@Suppress("LongParameterList") // Each parameter is a distinct source of the report, wired once in AppGraph.
internal class StateReporter(
    private val context: Context,
    private val device: AndroidDevice,
    private val client: PortOsClient,
    private val session: Session,
    private val launchableApps: () -> List<String>,
    private val inputCapabilities: () -> InputCapabilities,
    private val onReported: (playing: Boolean) -> Unit = {}
) {
    @Volatile private var lastCommandAt = 0L

    /** Records that a command just ran, so a state read right after it waits for the TV to settle. */
    fun commandRan() {
        lastCommandAt = SystemClock.elapsedRealtime()
    }

    /**
     * Answers a forced attribute query: reads the TV now and posts it tagged with [requestId], which is what completes
     * the host's waiting request. When a command ran a moment ago Android has not published its effect yet, so wait
     * out the rest of [SETTLE_MILLIS] first; otherwise a query sent straight after "pause" would still show PLAYING.
     */
    fun reportRequested(requestId: String): Boolean {
        val remaining = SETTLE_MILLIS - (SystemClock.elapsedRealtime() - lastCommandAt)
        if (remaining > 0) Thread.sleep(remaining)
        return report(requestId)
    }

    /** Reports now. Returns false when the report could not be delivered and is worth retrying. */
    fun report(requestId: String? = null): Boolean {
        val linkId = session.authLinkId ?: return true
        return try {
            val snapshot = snapshot()
            client.report(linkId, snapshot.claims(System.currentTimeMillis() / MILLIS, requestId = requestId))
            onReported(snapshot.playback == Playback.PLAYING)
            true
        } catch (error: PortOsException) {
            Log.w(TAG, "state report failed: ${error.message}")
            !error.retryable
        }
    }

    fun snapshot(): StateSnapshot {
        val sampledAtMs = System.currentTimeMillis()
        val sessions = activeSessions()
        val controller = sessions?.let { mostRelevant(it) { session -> playbackOf(session.playbackState) } }
        val (current, max) = device.volume()
        return StateSnapshot(
            sampledAtMs = sampledAtMs,
            playback = reportedPlayback(sessions, controller) { session -> playbackOf(session.playbackState) },
            nowPlaying = controller?.let(::nowPlaying),
            volumePercent = if (max == 0) 0 else (current * PERCENT.toDouble() / max).roundToInt(),
            muted = device.isMuted(),
            backgroundLaunchAllowed = device.canLaunchFromBackground(),
            mediaAccessAllowed = sessions != null,
            accessibilityEnabled = AccessibilitySetting.isEnabled(context),
            input = inputCapabilities(),
            applications = launchableApps()
        )
    }

    /** Active media sessions, most recent first; null when notification-listener access is not granted. */
    private fun activeSessions(): List<MediaController>? = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, MediaSessionListener::class.java))
    } catch (_: SecurityException) {
        null
    }

    private fun nowPlaying(controller: MediaController): NowPlaying {
        val metadata = controller.metadata
        val state = controller.playbackState
        val app = controller.packageName
        return NowPlaying(
            appPackage = app,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            positionMs = state?.let {
                currentPosition(
                    it.position,
                    it.lastPositionUpdateTime,
                    it.playbackSpeed,
                    SystemClock.elapsedRealtime(),
                    playbackOf(it) == Playback.PLAYING
                )
            },
            provider = MediaIdentity.provider(app),
            mediaId = MediaIdentity.mediaId(app, identifiers(metadata)),
            queue = queuePlace(controller.queue?.map { it.queueId }, state?.activeQueueItemId)
        )
    }

    /** Every identifier the session's metadata exposes, for [MediaIdentity] to pick the one it can prove. */
    private fun identifiers(metadata: MediaMetadata?): List<String> = listOfNotNull(
        metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
        metadata?.description?.mediaId,
        metadata?.description?.mediaUri?.toString()
    ).filter { it.isNotBlank() }

    private companion object {
        const val TAG = "PortOsState"
        const val MILLIS = 1000L
        const val PERCENT = 100

        /** How long after a command Android needs to publish its effect on the media session. */
        const val SETTLE_MILLIS = 1200L
    }
}
