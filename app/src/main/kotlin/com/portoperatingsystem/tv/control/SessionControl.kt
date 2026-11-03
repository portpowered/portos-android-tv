package com.portoperatingsystem.tv.control

import com.portoperatingsystem.tv.state.Playback
import com.portoperatingsystem.tv.state.mostRelevant

/** One app's media session, as far as seeking needs it. Behind an interface so seeking is testable without a TV. */
internal interface PlayerSession {
    val playback: Playback?

    /** Whether the app advertises `ACTION_SEEK_TO`: without it `TransportControls.seekTo` is ignored. */
    val supportsSeek: Boolean

    /** Where playback is now (advanced to this moment while playing); null when the app reports no position. */
    val positionMillis: Long?

    /** Length of the current item; null for a live stream or an app that does not say. */
    val durationMillis: Long?

    fun seekTo(positionMillis: Long)
}

/**
 * Seeks the session a person means by "the thing that is playing" ([mostRelevant], the same pick the state reports
 * use). [sessions] is null when notification-listener access is not granted, so media sessions are hidden.
 */
internal class SessionControl(private val sessions: () -> List<PlayerSession>?) : SessionPort {
    override fun seekTo(positionMillis: Long): Execution = withSession { session ->
        jump(session, positionMillis)
    }

    override fun skip(deltaMillis: Long): Execution = withSession { session ->
        val from = session.positionMillis ?: return@withSession Execution.Failed(ExecutionFailure.POSITION_UNKNOWN)
        jump(session, from + deltaMillis)
    }

    private inline fun withSession(action: (PlayerSession) -> Execution): Execution {
        val all = sessions() ?: return Execution.Failed(ExecutionFailure.MEDIA_ACCESS_NOT_ALLOWED)
        val session = mostRelevant(all) { it.playback } ?: return Execution.Failed(ExecutionFailure.NO_MEDIA_SESSION)
        return if (session.supportsSeek) action(session) else Execution.Failed(ExecutionFailure.SEEK_NOT_SUPPORTED)
    }

    // A position outside the item means the start, or the end when the length is known.
    private fun jump(session: PlayerSession, target: Long): Execution {
        val end = session.durationMillis
        session.seekTo(if (end != null) target.coerceIn(0, end) else target.coerceAtLeast(0))
        return Execution.Done
    }
}
