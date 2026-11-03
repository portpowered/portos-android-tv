package com.portoperatingsystem.tv.control

import android.util.Log

/** What the TV's own adb daemon answered. */
internal sealed interface AdbReply {
    /** The daemon ran the command and it exited with [exitCode]. */
    data class Ok(val exitCode: Int, val output: String) : AdbReply

    /** Nothing listens on the adb port: network debugging is off. Costs nothing and prompts nobody. */
    data object NotListening : AdbReply

    /** The daemon listens but did not accept this app's key: the "Allow USB debugging?" prompt is pending or denied. */
    data object NotAuthorized : AdbReply

    /** Anything else went wrong (a broken connection, a protocol error). */
    data class Failed(val reason: String) : AdbReply
}

/** The connection to the TV's adb daemon, behind an interface so the availability logic is testable without one. */
internal interface AdbTransport {
    /** Connects, patiently enough for the owner to answer the TV's first-time prompt, and runs a no-op. */
    fun probe(): AdbReply

    /** Runs [command] in the adb shell, quickly: a command that cannot run now is a fallback, not a wait. */
    fun run(command: String): AdbReply
}

/** Input through the TV's own adb daemon, when the owner has switched it on. */
internal interface AdbInput {
    /** Whether the last probe found the daemon usable. Cached: asking never touches the network. */
    val available: Boolean

    /** Taps the display pixel ([x], [y]); false when it could not be done. */
    fun tap(x: Int, y: Int): Boolean

    /** Sends the Android key [keyCode]; false when it could not be done. */
    fun keyEvent(keyCode: Int): Boolean
}

/**
 * Opportunistic local adb. It is never relied on: [available] is false until a probe finds the daemon usable, and a
 * failure at any time makes it false again, so callers fall back to the accessibility service. Probes are rare, and
 * how rare depends on what the last one found, so a TV that never enabled network debugging is never bothered and a
 * TV waiting for an answer to the "Allow USB debugging?" prompt is not prompted again and again.
 */
internal class LocalAdb(private val transport: AdbTransport, private val clock: () -> Long) : AdbInput {
    private class Known(val available: Boolean, val nextProbeAt: Long, val unauthorizedProbes: Int = 0)

    @Volatile
    private var known: Known? = null

    override val available: Boolean get() = known?.available == true

    /** Whether a probe is due: none ran yet, or the wait that follows the last one has passed. */
    fun probeDue(): Boolean = known?.let { clock() >= it.nextProbeAt } ?: true

    /** Probes now when [probeDue]; true when that changed [available]. Blocks, so call it off the main thread. */
    @Synchronized
    fun refresh(): Boolean {
        if (!probeDue()) return false
        val before = available
        record(transport.probe())
        return available != before
    }

    override fun tap(x: Int, y: Int): Boolean = shell("input tap $x $y")

    override fun keyEvent(keyCode: Int): Boolean = shell("input keyevent $keyCode")

    private fun shell(command: String): Boolean {
        if (!available) return false
        return when (val reply = transport.run(command)) {
            is AdbReply.Ok -> (reply.exitCode == 0).also { ran ->
                if (!ran) Log.w(TAG, "adb '$command' exited ${reply.exitCode}")
            }
            else -> {
                record(reply)
                false
            }
        }
    }

    private fun record(reply: AdbReply) {
        val now = clock()
        known = when (reply) {
            is AdbReply.Ok -> Known(true, now + RECHECK_WHEN_AVAILABLE_MILLIS)
            AdbReply.NotListening -> Known(false, now + RECHECK_WHEN_OFF_MILLIS)
            AdbReply.NotAuthorized -> {
                // The first miss is usually an owner who has not reached the prompt yet: look once more soon. A
                // second miss means a denied or ignored prompt, so back off to the long wait.
                val misses = (known?.unauthorizedProbes ?: 0) + 1
                val wait = when (misses) {
                    1 -> RECHECK_AFTER_FIRST_UNAUTHORIZED_MILLIS
                    else -> RECHECK_WHEN_UNAUTHORIZED_MILLIS
                }
                Known(false, now + wait, misses)
            }
            is AdbReply.Failed -> {
                Log.w(TAG, "local adb failed: ${reply.reason}")
                Known(false, now + RECHECK_AFTER_FAILURE_MILLIS)
            }
        }
    }

    internal companion object {
        private const val TAG = "PortOsAdb"
        const val RECHECK_WHEN_AVAILABLE_MILLIS = 10 * 60_000L
        const val RECHECK_WHEN_OFF_MILLIS = 15 * 60_000L

        /** One short follow-up after the first unallowed probe, so an allow given a moment later is noticed. */
        const val RECHECK_AFTER_FIRST_UNAUTHORIZED_MILLIS = 3 * 60_000L

        /** Each probe of an unauthorized key shows the TV's prompt again; ask rarely. */
        const val RECHECK_WHEN_UNAUTHORIZED_MILLIS = 6 * 60 * 60_000L
        const val RECHECK_AFTER_FAILURE_MILLIS = 5 * 60_000L
    }
}
