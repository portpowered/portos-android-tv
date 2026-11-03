package com.portoperatingsystem.tv.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.portoperatingsystem.tv.playback.PlaybackService
import com.portoperatingsystem.tv.protocol.command.TransportAction
import com.portoperatingsystem.tv.protocol.media.LaunchTarget
import com.portoperatingsystem.tv.ui.NotificationActivity

/** [DevicePort] backed by the real Android services. */
internal class AndroidDevice(private val context: Context) : DevicePort {
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    override fun canLaunchFromBackground(): Boolean = Settings.canDrawOverlays(context)

    override fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun launch(target: LaunchTarget) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))
            .setPackage(target.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    override fun showNotification(title: String, body: String, displayMillis: Long) {
        context.startActivity(NotificationActivity.intent(context, title, body, displayMillis))
    }

    override fun playUri(uri: String, title: String?, offsetMillis: Long) {
        context.startService(PlaybackService.playIntent(context, uri, title, offsetMillis))
    }

    override fun mediaKey(action: TransportAction) {
        val code = when (action) {
            TransportAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            TransportAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            TransportAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            TransportAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            TransportAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    override fun volume(): Pair<Int, Int> =
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun setVolume(index: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)

    override fun setMuted(muted: Boolean) {
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    fun isMuted(): Boolean = audio.isStreamMute(AudioManager.STREAM_MUSIC)
}
