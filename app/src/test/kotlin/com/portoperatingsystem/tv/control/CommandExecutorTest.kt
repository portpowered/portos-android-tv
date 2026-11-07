package com.portoperatingsystem.tv.control

import com.portoperatingsystem.tv.protocol.command.Command
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import com.portoperatingsystem.tv.protocol.command.TransportAction
import com.portoperatingsystem.tv.protocol.media.LaunchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeDevice(
    var backgroundLaunch: Boolean = true,
    val installed: Set<String> = setOf("com.google.android.youtube.tv"),
    var volumeIndex: Int = 5,
    val maxVolume: Int = 15,
    var accessibility: Boolean = true
) : DevicePort,
    ScreenPort,
    StatePort,
    SessionPort,
    AppPort {
    var keyResult: Execution = Execution.Done
    var tapResult: Execution = Execution.Done
    val launched = mutableListOf<LaunchTarget>()
    val keys = mutableListOf<TransportAction>()
    val notifications = mutableListOf<Triple<String, String, Long>>()
    val audio = mutableListOf<Triple<String, String?, Long>>()
    val files = mutableListOf<Command.PlayFile>()
    var muted: Boolean? = null
    val pressed = mutableListOf<NavigationKey>()
    val captures = mutableListOf<String>()
    val taps = mutableListOf<Pair<Double, Double>>()
    val stateReads = mutableListOf<String>()
    var sessionResult: Execution = Execution.Done
    val seeks = mutableListOf<Long>()
    val skips = mutableListOf<Long>()
    var openable: Set<String> = setOf("com.netflix.ninja")
    val opened = mutableListOf<String>()

    override fun canLaunchFromBackground() = backgroundLaunch

    override fun isInstalled(packageName: String) = packageName in installed

    override fun launch(target: LaunchTarget) {
        launched += target
    }

    override fun showNotification(title: String, body: String, displayMillis: Long) {
        notifications += Triple(title, body, displayMillis)
    }

    override fun playUri(uri: String, title: String?, offsetMillis: Long) {
        audio += Triple(uri, title, offsetMillis)
    }

    override fun playFile(url: String, mimeType: String, video: Boolean, loop: Boolean) {
        files += Command.PlayFile(url, mimeType, video, loop)
    }

    override fun mediaKey(action: TransportAction) {
        keys += action
    }

    override fun volume() = volumeIndex to maxVolume

    override fun setVolume(index: Int) {
        volumeIndex = index
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun isAccessibilityEnabled() = accessibility

    override fun pressKey(key: NavigationKey): Execution {
        pressed += key
        return keyResult
    }

    override fun tap(x: Double, y: Double): Execution {
        taps += x to y
        return tapResult
    }

    override fun captureScreen(requestId: String) {
        captures += requestId
    }

    override fun reportState(requestId: String) {
        stateReads += requestId
    }

    override fun seekTo(positionMillis: Long): Execution {
        seeks += positionMillis
        return sessionResult
    }

    override fun skip(deltaMillis: Long): Execution {
        skips += deltaMillis
        return sessionResult
    }

    override fun launch(packageName: String): Boolean {
        opened += packageName
        return packageName in openable
    }

    override fun launchable() = openable.sorted()
}

private fun executorFor(device: FakeDevice) = CommandExecutor(device, device, device, device, device)

class CommandExecutorTest {
    @Test
    fun tapsTheScreen() {
        val device = FakeDevice()
        assertEquals(Execution.Done, executorFor(device).execute(Command.Tap(0.13, 0.45)))
        assertEquals(listOf(0.13 to 0.45), device.taps)
    }

    @Test
    fun seeksAndSkipsThroughTheMediaSession() {
        val device = FakeDevice()
        assertEquals(Execution.Done, executorFor(device).execute(Command.Seek(90_000)))
        assertEquals(Execution.Done, executorFor(device).execute(Command.Skip(-10_000)))
        assertEquals(listOf(90_000L), device.seeks)
        assertEquals(listOf(-10_000L), device.skips)
    }

    @Test
    fun reportsASeekTheSessionRefused() {
        val failed = Execution.Failed(ExecutionFailure.SEEK_NOT_SUPPORTED)
        val device = FakeDevice().apply { sessionResult = failed }
        assertEquals(failed, executorFor(device).execute(Command.Seek(0)))
        assertEquals(failed, executorFor(device).execute(Command.Skip(5_000)))
    }

    @Test
    fun launchesAnInstalledApp() {
        val device = FakeDevice()
        assertEquals(Execution.Done, executorFor(device).execute(Command.LaunchApp("com.netflix.ninja")))
        assertEquals(listOf("com.netflix.ninja"), device.opened)
    }

    @Test
    fun failsTypedWhenThePackageCannotBeLaunched() {
        val device = FakeDevice()
        assertEquals(
            Execution.Failed(ExecutionFailure.APP_NOT_LAUNCHABLE),
            executorFor(device).execute(Command.LaunchApp("com.example.missing"))
        )
    }

    @Test
    fun doesNotLaunchAnAppWithoutBackgroundLaunchPermission() {
        val device = FakeDevice(backgroundLaunch = false)
        assertEquals(
            Execution.Failed(ExecutionFailure.BACKGROUND_LAUNCH_NOT_ALLOWED),
            executorFor(device).execute(Command.LaunchApp("com.netflix.ninja"))
        )
        assertEquals(emptyList(), device.opened)
    }

    @Test
    fun reportsATapThatCouldNotBeDelivered() {
        val failed = Execution.Failed(ExecutionFailure.TAP_NEEDS_TOUCH_OR_ADB)
        val device = FakeDevice().apply { tapResult = failed }
        assertEquals(failed, executorFor(device).execute(Command.Tap(0.5, 0.5)))
    }

    private val youtube = LaunchTarget("https://www.youtube.com/watch?v=x", "com.google.android.youtube.tv")

    @Test
    fun launchesMedia() {
        val device = FakeDevice()
        assertEquals(Execution.Done, executorFor(device).execute(Command.PlayMedia("id", youtube)))
        assertEquals(listOf(youtube), device.launched)
    }

    @Test
    fun refusesLaunchWithoutBackgroundPermission() {
        val device = FakeDevice(backgroundLaunch = false)
        assertEquals(
            Execution.Failed(ExecutionFailure.BACKGROUND_LAUNCH_NOT_ALLOWED),
            executorFor(device).execute(Command.PlayMedia("id", youtube))
        )
        assertTrue(device.launched.isEmpty())
    }

    @Test
    fun refusesLaunchWhenAppMissing() {
        val device = FakeDevice(installed = emptySet())
        assertEquals(
            Execution.Failed(ExecutionFailure.APP_NOT_INSTALLED),
            executorFor(device).execute(Command.PlayMedia("id", youtube))
        )
    }

    @Test
    fun showsNotifications() {
        val device = FakeDevice()
        assertEquals(
            Execution.Done,
            executorFor(device).execute(Command.ShowNotification("Dinner", "Ready", 15_000))
        )
        assertEquals(listOf(Triple("Dinner", "Ready", 15_000L)), device.notifications)
    }

    @Test
    fun playsAudioUris() {
        val device = FakeDevice()
        val uri = "https://example.com/chime.mp3"
        assertEquals(Execution.Done, executorFor(device).execute(Command.PlayUri(uri, "Chime", 1500)))
        assertEquals(listOf(Triple(uri, "Chime" as String?, 1500L)), device.audio)
    }

    @Test
    fun refusesNotificationsAndAudioWithoutBackgroundPermission() {
        val device = FakeDevice(backgroundLaunch = false)
        val executor = executorFor(device)
        val denied = Execution.Failed(ExecutionFailure.BACKGROUND_LAUNCH_NOT_ALLOWED)
        assertEquals(denied, executor.execute(Command.ShowNotification("t", "b", 1)))
        assertEquals(denied, executor.execute(Command.PlayUri("https://example.com/a.mp3", null, 0)))
        assertTrue(device.notifications.isEmpty() && device.audio.isEmpty())
    }

    @Test
    fun playsDownloadedFiles() {
        val device = FakeDevice()
        val video = Command.PlayFile("https://example.com/dance.mp4", "video/mp4", video = true, loop = true)
        val audio = Command.PlayFile("https://example.com/song.mp3", "audio/mpeg", video = false, loop = false)
        assertEquals(Execution.Done, executorFor(device).execute(video))
        assertEquals(Execution.Done, executorFor(device).execute(audio))
        assertEquals(listOf(video, audio), device.files)
    }

    @Test
    fun refusesFilesWithoutBackgroundPermission() {
        val device = FakeDevice(backgroundLaunch = false)
        val file = Command.PlayFile("https://example.com/dance.mp4", "video/mp4", video = true, loop = false)
        assertEquals(
            Execution.Failed(ExecutionFailure.BACKGROUND_LAUNCH_NOT_ALLOWED),
            executorFor(device).execute(file)
        )
        assertTrue(device.files.isEmpty())
    }

    @Test
    fun sendsMediaKeys() {
        val device = FakeDevice()
        executorFor(device).execute(Command.Transport(TransportAction.PAUSE))
        assertEquals(listOf(TransportAction.PAUSE), device.keys)
    }

    @Test
    fun mapsVolumePercentToStreamIndex() {
        val device = FakeDevice()
        val executor = executorFor(device)
        executor.execute(Command.SetVolume(100))
        assertEquals(15, device.volumeIndex)
        executor.execute(Command.SetVolume(50))
        assertEquals(8, device.volumeIndex)
        executor.execute(Command.SetVolume(0))
        assertEquals(0, device.volumeIndex)
    }

    @Test
    fun adjustsVolumeRelativeAndClamps() {
        val device = FakeDevice(volumeIndex = 6)
        val executor = executorFor(device)
        executor.execute(Command.AdjustVolume(20))
        assertEquals(9, device.volumeIndex)
        executor.execute(Command.AdjustVolume(100))
        assertEquals(15, device.volumeIndex)
        executor.execute(Command.AdjustVolume(-100))
        assertEquals(0, device.volumeIndex)
    }

    @Test
    fun setsMute() {
        val device = FakeDevice()
        executorFor(device).execute(Command.SetMute(true))
        assertEquals(true, device.muted)
    }

    @Test
    fun pressesRemoteKeys() {
        val device = FakeDevice()
        assertEquals(Execution.Done, executorFor(device).execute(Command.SendKey(NavigationKey.SELECT)))
        assertEquals(listOf(NavigationKey.SELECT), device.pressed)
    }

    @Test
    fun reportsAKeyTheSystemDidNotPerform() {
        val failed = Execution.Failed(ExecutionFailure.KEY_NOT_PERFORMED)
        val device = FakeDevice().apply { keyResult = failed }
        assertEquals(
            failed,
            executorFor(device).execute(Command.SendKey(NavigationKey.UP))
        )
    }

    @Test
    fun capturesTheScreenThroughTheAccessibilityService() {
        val device = FakeDevice()
        assertEquals(Execution.Done, executorFor(device).execute(Command.CaptureScreen("req-1")))
        assertEquals(listOf("req-1"), device.captures)
    }

    @Test
    fun refusesCapturesWhileTheAccessibilityServiceIsOff() {
        val device = FakeDevice(accessibility = false)
        val off = Execution.Failed(ExecutionFailure.ACCESSIBILITY_NOT_ENABLED)
        assertEquals(off, executorFor(device).execute(Command.CaptureScreen("req-2")))
        assertTrue(device.captures.isEmpty())
    }

    @Test
    fun stateReadsDoNotNeedTheAccessibilityService() {
        val device = FakeDevice(accessibility = false)
        assertEquals(Execution.Done, executorFor(device).execute(Command.ReportState("req-3")))
        assertEquals(listOf("req-3"), device.stateReads)
    }
}
