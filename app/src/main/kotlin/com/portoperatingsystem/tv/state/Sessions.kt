package com.portoperatingsystem.tv.state

import android.media.session.PlaybackState

/** The canonical playback state of an Android [state]; null when Android reports none this app knows. */
internal fun playbackOf(state: PlaybackState?): Playback? = when (state?.state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING
    -> Playback.PLAYING
    PlaybackState.STATE_PAUSED -> Playback.PAUSED
    PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> Playback.STOPPED
    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> Playback.LOADING
    PlaybackState.STATE_ERROR -> Playback.ERROR
    else -> null
}

/**
 * The session a person means by "what is playing" or "skip ahead": one that is playing, else one that is loading, else
 * one that is paused, else the most recent. Android lists an idle app's session ahead of the one that is playing, so
 * the first is not enough. Every command and every report picks its session here, so they agree on which app it is.
 */
internal fun <T> mostRelevant(sessions: List<T>, playback: (T) -> Playback?): T? =
    sessions.firstOrNull { playback(it) == Playback.PLAYING }
        ?: sessions.firstOrNull { playback(it) == Playback.LOADING }
        ?: sessions.firstOrNull { playback(it) == Playback.PAUSED }
        ?: sessions.firstOrNull()

/**
 * The playback a report states: unknown when media access is not granted ([sessions] null), STOPPED when access is
 * granted and no app holds an active session (nothing is playing, which the TV knows), else the chosen session's.
 */
internal fun <T> reportedPlayback(sessions: List<T>?, chosen: T?, playback: (T) -> Playback?): Playback? = when {
    sessions == null -> null
    chosen == null -> Playback.STOPPED
    else -> playback(chosen)
}
