package com.portoperatingsystem.tv.control

import com.portoperatingsystem.tv.protocol.command.Command
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import com.portoperatingsystem.tv.protocol.command.TransportAction
import com.portoperatingsystem.tv.protocol.media.LaunchTarget
import kotlin.math.roundToInt

/** The Android capabilities commands need, behind an interface so execution is testable without a TV. */
internal interface DevicePort {
    /** Whether Android will let this app start activities from the background (`SYSTEM_ALERT_WINDOW`). */
    fun canLaunchFromBackground(): Boolean

    /** Whether [packageName] is installed. */
    fun isInstalled(packageName: String): Boolean

    /** Opens [target]. */
    fun launch(target: LaunchTarget)

    /** Shows [title] and [body] over whatever is on screen for [displayMillis]. */
    fun showNotification(title: String, body: String, displayMillis: Long)

    /** Plays the audio file at [uri], starting [offsetMillis] in. */
    fun playUri(uri: String, title: String?, offsetMillis: Long)

    /** Sends a media key to the active media session. */
    fun mediaKey(action: TransportAction)

    /** Music-stream volume as (current, max). */
    fun volume(): Pair<Int, Int>

    fun setVolume(index: Int)

    fun setMuted(muted: Boolean)
}

/** What the app can do to the screen: remote keys and taps (accessibility service or local adb) and screenshots. */
internal interface ScreenPort {
    /** Whether the accessibility service is switched on (`make setup-tv`). */
    fun isAccessibilityEnabled(): Boolean

    /** Presses [key], through local adb or the accessibility service; says why when it could not. */
    fun pressKey(key: NavigationKey): Execution

    /** Taps the screen at ([x], [y]), fractions of its width and height; says why when it could not. */
    fun tap(x: Double, y: Double): Execution

    /** Starts capturing the screen; the picture is posted to Port OS later, tagged with [requestId]. */
    fun captureScreen(requestId: String)
}

/** Moves the playing item's position through its media session. */
internal interface SessionPort {
    /** Jumps to [positionMillis] (kept inside the item); says why when it could not. */
    fun seekTo(positionMillis: Long): Execution

    /** Moves [deltaMillis] (negative is backward) from where playback is now; says why when it could not. */
    fun skip(deltaMillis: Long): Execution
}

/** The apps installed on the TV, by Android package name. */
internal interface AppPort {
    /** Opens the app [packageName] (its Leanback launcher, else its launcher activity); false when it has neither. */
    fun launch(packageName: String): Boolean

    /** The packages the TV can open, sorted and without duplicates. */
    fun launchable(): List<String>
}

/** Reads the TV's state for a forced attribute query. */
internal fun interface StatePort {
    /** Samples the TV and posts the report, tagged with [requestId], later; must not block the caller. */
    fun reportState(requestId: String)
}

/** Why a command could not run on this TV. [reason] is what the log says, in words the owner can act on. */
internal enum class ExecutionFailure(val reason: String) {
    /** `make setup-tv` has not been run: Android would silently drop the launch. */
    BACKGROUND_LAUNCH_NOT_ALLOWED("background launch is not allowed; run make setup-tv"),

    /** The app that plays the media is not installed. */
    APP_NOT_INSTALLED("the app that plays this media is not installed"),

    /** `make setup-tv` has not enabled the accessibility service that presses keys and captures the screen. */
    ACCESSIBILITY_NOT_ENABLED("the accessibility service is not enabled; run make setup-tv"),

    /** The accessibility service is on but the system did not perform the key. */
    KEY_NOT_PERFORMED("the system did not perform the key"),

    /** Below Android 13 the service can only act on the focused element, and there was none to act on. */
    KEY_NEEDS_FOCUS(
        "no focused element to act on, and this Android has no D-pad actions; enable network debugging on the TV"
    ),

    /** The TV has no touch input, so Android drops accessibility taps, and local adb is not available. */
    TAP_NEEDS_TOUCH_OR_ADB("this TV has no touch input and network debugging is off, so a tap cannot be delivered"),

    /** Android cancelled the tap gesture. */
    TAP_CANCELLED("Android cancelled the tap gesture, or never finished it"),

    /** Android refused to start the tap gesture, or the service is not connected yet. */
    TAP_NOT_DISPATCHED("Android did not start the tap gesture"),

    /** The default display could not be read, so a tap could not be placed. */
    DISPLAY_UNAVAILABLE("the display size is unavailable"),

    /** Without notification-listener access Android hides other apps' media sessions (`make setup-tv` grants it). */
    MEDIA_ACCESS_NOT_ALLOWED("media session access is not granted, so no session can be seeked; run make setup-tv"),

    /** No app holds a media session. */
    NO_MEDIA_SESSION("no app has a media session to seek"),

    /** The playing app does not advertise `ACTION_SEEK_TO`, so Android would ignore a seek. */
    SEEK_NOT_SUPPORTED("the playing app does not let other apps seek"),

    /** A skip is relative to the position, and the playing app reports none. */
    POSITION_UNKNOWN("the playing app reports no position to skip from"),

    /** The package is not installed, or has no launcher activity to open. */
    APP_NOT_LAUNCHABLE("the app is not installed or has no launcher activity")
}

/** Result of executing a command. */
internal sealed interface Execution {
    data object Done : Execution

    data class Failed(val failure: ExecutionFailure) : Execution
}

/** Executes decoded commands against the TV. */
internal class CommandExecutor(
    private val device: DevicePort,
    private val screen: ScreenPort,
    private val state: StatePort,
    private val sessions: SessionPort,
    private val apps: AppPort
) {
    // One branch per command: a flat dispatch table, which splitting would only scatter.
    @Suppress("CyclomaticComplexMethod")
    fun execute(command: Command): Execution = when (command) {
        is Command.PlayMedia -> launch(command.target)
        is Command.ShowNotification -> fromBackground {
            device.showNotification(command.title, command.body, command.displayMillis)
        }
        is Command.PlayUri -> fromBackground { device.playUri(command.uri, command.title, command.offsetMillis) }
        is Command.Transport -> done { device.mediaKey(command.action) }
        is Command.Seek -> sessions.seekTo(command.positionMillis)
        is Command.Skip -> sessions.skip(command.deltaMillis)
        is Command.LaunchApp -> launchApp(command.packageName)
        is Command.SetVolume -> done { device.setVolume(toIndex(command.percent)) }
        is Command.AdjustVolume -> done { device.setVolume(toIndex(currentPercent() + command.delta)) }
        is Command.SetMute -> done { device.setMuted(command.muted) }
        is Command.SendKey -> screen.pressKey(command.key)
        is Command.Tap -> screen.tap(command.x, command.y)
        is Command.CaptureScreen -> withAccessibility { done { screen.captureScreen(command.requestId) } }
        is Command.ReportState -> done { state.reportState(command.requestId) }
    }

    private inline fun withAccessibility(action: () -> Execution): Execution =
        if (screen.isAccessibilityEnabled()) action() else Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED)

    private fun launch(target: LaunchTarget): Execution = when {
        !device.canLaunchFromBackground() -> Execution.Failed(ExecutionFailure.BACKGROUND_LAUNCH_NOT_ALLOWED)
        !device.isInstalled(target.packageName) -> Execution.Failed(ExecutionFailure.APP_NOT_INSTALLED)
        else -> done { device.launch(target) }
    }

    private fun launchApp(packageName: String): Execution = when {
        !device.canLaunchFromBackground() -> Execution.Failed(ExecutionFailure.BACKGROUND_LAUNCH_NOT_ALLOWED)
        !apps.launch(packageName) -> Execution.Failed(ExecutionFailure.APP_NOT_LAUNCHABLE)
        else -> Execution.Done
    }

    // Starting an activity or the playback service from the background needs the same permission as a launch.
    private inline fun fromBackground(action: () -> Unit): Execution = if (device.canLaunchFromBackground()) {
        done(action)
    } else {
        Execution.Failed(ExecutionFailure.BACKGROUND_LAUNCH_NOT_ALLOWED)
    }

    private inline fun done(action: () -> Unit): Execution {
        action()
        return Execution.Done
    }

    private fun currentPercent(): Int {
        val (current, max) = device.volume()
        return if (max == 0) 0 else (current * PERCENT.toDouble() / max).roundToInt()
    }

    private fun toIndex(percent: Int): Int {
        val max = device.volume().second
        return (percent.coerceIn(0, PERCENT) * max.toDouble() / PERCENT).roundToInt()
    }

    private companion object {
        const val PERCENT = 100
    }
}
