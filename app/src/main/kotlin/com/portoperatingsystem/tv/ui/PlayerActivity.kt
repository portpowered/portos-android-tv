package com.portoperatingsystem.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.portoperatingsystem.tv.R
import com.portoperatingsystem.tv.playback.PlaybackService

/**
 * The full-screen player for `play-uri`, `play-video` and `play-audio`: a Media3 [PlayerView] attached to the player
 * [PlaybackService] owns. Nothing covers the media: the standard controls (play and pause, seek bar, position and
 * duration) appear only when someone presses a remote key, and hide again after a few seconds. It closes itself when
 * that player goes idle or ends, so a stop key or the end of the file returns to the previous app, and Back stops
 * playback and closes it. Audio plays on a plain background.
 */
internal class PlayerActivity : Activity() {
    @VisibleForTesting
    internal lateinit var view: PlayerView
        private set
    private var attached: Player? = null
    private val finishWhenDone = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = PlayerView(this).apply {
            isFocusable = true
            keepScreenOn = true
        }
        setContentView(view)
        configure(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configure(intent)
    }

    override fun onStart() {
        super.onStart()
        val player = PlaybackService.currentPlayer
        if (player == null || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            finish()
        } else {
            player.addListener(finishWhenDone)
            view.player = player
            attached = player
            view.requestFocus()
        }
    }

    override fun onStop() {
        attached?.removeListener(finishWhenDone)
        attached = null
        view.player = null
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        PlaybackService.currentPlayer?.stop()
        finish()
        return true
    }

    // PlayerView's shutter colour and controller timeout are Media3 opt-in APIs; the pinned 1.5.1 is what they match.
    @OptIn(UnstableApi::class)
    private fun configure(intent: Intent) {
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        val background = if (video) Color.BLACK else getColor(R.color.background)
        view.setBackgroundColor(background)
        view.setShutterBackgroundColor(background)
        // No overlay by default, not even when playback starts, pauses or ends; a remote key shows the controls for a
        // few seconds.
        view.controllerAutoShow = false
        view.controllerShowTimeoutMs = CONTROLS_MILLIS
        view.hideController()
    }

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val CONTROLS_MILLIS = 5_000

        /** The intent that opens the player screen, for a [video] or for audio. */
        fun intent(context: Context, video: Boolean): Intent = Intent(context, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_VIDEO, video)
    }
}
