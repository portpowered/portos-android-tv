package com.portoperatingsystem.tv.state

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import com.portoperatingsystem.tv.PortOsApplication

/**
 * Notification-listener access is what lets the app read other apps' media sessions. While the system keeps
 * this service bound, it watches sessions and asks for a state report whenever playback or the title changes.
 */
internal class MediaSessionListener : NotificationListenerService() {
    private var watched: List<MediaController> = emptyList()

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()

        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = changed()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watch(controllers.orEmpty())
        changed()
    }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, MediaSessionListener::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsChanged, component)
        watch(manager.getActiveSessions(component))
        changed()
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged)
        watch(emptyList())
    }

    private fun watch(controllers: List<MediaController>) {
        watched.forEach { it.unregisterCallback(callback) }
        watched = controllers
        watched.forEach { it.registerCallback(callback) }
    }

    // Not private: the callbacks above call it, and private would need a synthetic accessor.
    internal fun changed() = (application as PortOsApplication).graph.requestReport()
}
