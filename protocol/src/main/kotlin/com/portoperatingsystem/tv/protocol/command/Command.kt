package com.portoperatingsystem.tv.protocol.command

import com.portoperatingsystem.tv.protocol.media.LaunchTarget

/** A Port OS command the TV executes. Each maps to one canonical capability message. */
public sealed interface Command {
    /** `media-player/play-media`: open [target] in its app. */
    public data class PlayMedia(val mediaId: String, val target: LaunchTarget) : Command

    /** `media-player/play-uri`: play the finite public HTTPS audio file at [uri], starting [offsetMillis] in. */
    public data class PlayUri(val uri: String, val title: String?, val offsetMillis: Long) : Command

    /** `notification/notification-trigger` (IMMEDIATE): show [title] and [body] on screen for [displayMillis]. */
    public data class ShowNotification(val title: String, val body: String, val displayMillis: Long) : Command

    /** `media-player/seek` and `start-over` (position 0): jump to [positionMillis] in the current item. */
    public data class Seek(val positionMillis: Long) : Command

    /** `media-player/skip-forward` (positive) and `skip-backward` (negative): move [deltaMillis] from the position. */
    public data class Skip(val deltaMillis: Long) : Command

    /** `application-launcher/launch-application`: open the installed app with Android package [packageName]. */
    public data class LaunchApp(val packageName: String) : Command

    /** A transport control delivered as a media key. */
    public data class Transport(val action: TransportAction) : Command

    /** `speaker/set-volume`: absolute volume, 0–100. */
    public data class SetVolume(val percent: Int) : Command

    /** `speaker/adjust-volume`: relative change, −100–100. */
    public data class AdjustVolume(val delta: Int) : Command

    /** `speaker/set-mute`. */
    public data class SetMute(val muted: Boolean) : Command

    /** `keypad-input/send-key` for a key the accessibility service performs. PLAY and PAUSE are [Transport]. */
    public data class SendKey(val key: NavigationKey) : Command

    /**
     * `touch-input/tap`: tap once at ([x], [y]), fractions of the screen width and height (0,0 is the top-left), which
     * is how the snapshot image the caller looked at maps onto the display at any resolution.
     */
    public data class Tap(val x: Double, val y: Double) : Command

    /** `snapshot/get-snapshot`: capture the screen and post it, tagged with [requestId], to the webhook. */
    public data class CaptureScreen(val requestId: String) : Command

    /**
     * `control-routes-provider/report-attributes`: sample the TV's state now and post it, tagged with [requestId], to
     * the webhook. This is how a forced attribute query reads the TV.
     */
    public data class ReportState(val requestId: String) : Command
}

/** The remote-control keys the accessibility service can perform. */
public enum class NavigationKey { UP, DOWN, LEFT, RIGHT, SELECT, BACK, HOME }

/** Transport controls the TV sends to the active media session. */
public enum class TransportAction { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

/** A decoded FCM delivery: the Port OS message id and the command it carries. */
public data class Delivery(val messageId: String, val command: Command)

/** Why an FCM delivery was not executed. Rejections are logged and reported, never guessed around. */
public sealed interface Rejection {
    /** The `v` key names a protocol this build does not speak. */
    public data class UnsupportedVersion(val version: String?) : Rejection

    /** The `type` key names a message this TV does not handle. */
    public data class UnsupportedType(val type: String?) : Rejection

    /** The id or body is missing, or the body does not match the message schema. */
    public data class InvalidMessage(val reason: String) : Rejection

    /** The media identity is valid but no supported TV app plays it. */
    public data class UnsupportedMedia(val mediaId: String) : Rejection
}

/** Result of decoding one FCM data message. */
public sealed interface Decoded {
    /** The message is a command to execute. */
    public data class Accepted(val delivery: Delivery) : Decoded

    /** The message must not be executed. */
    public data class Rejected(val messageId: String?, val rejection: Rejection) : Decoded
}
