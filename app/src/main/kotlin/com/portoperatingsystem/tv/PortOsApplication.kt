package com.portoperatingsystem.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.portoperatingsystem.tv.auth.CredentialStore
import com.portoperatingsystem.tv.auth.KeystoreSigner
import com.portoperatingsystem.tv.auth.Session
import com.portoperatingsystem.tv.cloud.MessageSender
import com.portoperatingsystem.tv.cloud.PortOsClient
import com.portoperatingsystem.tv.cloud.PortOsException
import com.portoperatingsystem.tv.cloud.Registration
import com.portoperatingsystem.tv.control.AdbKeys
import com.portoperatingsystem.tv.control.AndroidApps
import com.portoperatingsystem.tv.control.AndroidDevice
import com.portoperatingsystem.tv.control.AndroidScreen
import com.portoperatingsystem.tv.control.CommandExecutor
import com.portoperatingsystem.tv.control.DadbTransport
import com.portoperatingsystem.tv.control.InputRouter
import com.portoperatingsystem.tv.control.LocalAdb
import com.portoperatingsystem.tv.control.PortOsAccessibilityService
import com.portoperatingsystem.tv.control.RealDisplay
import com.portoperatingsystem.tv.control.ScreenCapture
import com.portoperatingsystem.tv.control.ServiceInput
import com.portoperatingsystem.tv.control.SessionControl
import com.portoperatingsystem.tv.control.StatePort
import com.portoperatingsystem.tv.control.activePlayerSessions
import com.portoperatingsystem.tv.control.hasTouchInput
import com.portoperatingsystem.tv.playback.PlaybackService
import com.portoperatingsystem.tv.state.CoalescedRequests
import com.portoperatingsystem.tv.state.HeartbeatWorker
import com.portoperatingsystem.tv.state.OutcomeSink
import com.portoperatingsystem.tv.state.PlayingReports
import com.portoperatingsystem.tv.state.ReportWorker
import com.portoperatingsystem.tv.state.StateReporter
import com.portoperatingsystem.tv.surface.Capability
import com.portoperatingsystem.tv.surface.GatewayMessage
import com.portoperatingsystem.tv.surface.SurfaceActivity
import com.portoperatingsystem.tv.surface.SurfaceController
import com.portoperatingsystem.tv.surface.SurfaceDisplay
import com.portoperatingsystem.tv.surface.SurfaceEffects
import com.portoperatingsystem.tv.surface.gateway.GatewayConnector
import com.portoperatingsystem.tv.surface.gateway.TokenSource
import com.portoperatingsystem.tv.surface.render.HttpImageSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** Holds the app's object graph. There is no DI framework: the graph is small and built once here. */
internal class PortOsApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        if (graph.session.isPaired) {
            graph.scheduleHeartbeat()
            // Every start (and so every upgrade) registers what this build renders, with the real display.
            graph.requestReport()
            graph.connectGateway()
        }
        graph.probeAdbSoon()
    }
}

/** Every long-lived object, wired together. */
internal class AppGraph(private val context: Context) {
    /** False until the Firebase app id and API key are filled in (see the README's Configuration section). */
    val messagingConfigured: Boolean = initFirebase()

    val store = CredentialStore(context)
    val device = AndroidDevice(context)
    private val adb = LocalAdb(DadbTransport(AdbKeys(context.noBackupFilesDir)), SystemClock::elapsedRealtime)
    private val realDisplay = RealDisplay(context)
    private val input = InputRouter(
        adb = adb,
        accessibility = ServiceInput(context),
        display = realDisplay::size,
        sdkInt = Build.VERSION.SDK_INT,
        hasTouchInput = hasTouchInput(context.packageManager)
    )
    private val apps = AndroidApps(context)
    val executor = CommandExecutor(
        device,
        AndroidScreen(context, input) { requestId -> screenCapture.capture(requestId) },
        StatePort { requestId -> background.execute { reporter.reportRequested(requestId) } },
        SessionControl { activePlayerSessions(context) },
        apps
    )

    private val signer = KeystoreSigner()
    private val background = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private val http = OkHttpClient.Builder()
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val client = PortOsClient(
        http = http,
        baseUrl = BuildConfig.API_BASE_URL,
        clientId = BuildConfig.OAUTH_CLIENT_ID,
        pluginId = BuildConfig.PLUGIN_ID,
        signer = signer
    )

    val session = Session(client, store, ::registration, newIdentity = signer::reset)
    private val sender = MessageSender(client, session) { store.deviceId }
    val reporter: StateReporter = StateReporter(
        context,
        device,
        sender,
        session,
        generativeUi = { Capability.registration(displayInfo()) },
        launchableApps = apps::launchable,
        inputCapabilities = {
            probeAdbSoon()
            input.capabilities()
        },
        onReported = { playing -> playingReports.update(playing) }
    )

    init {
        // The player answers the commands that wait for what happened on the TV.
        PlaybackService.outcomes = OutcomeSink { commandId, outcome ->
            background.execute { reporter.reportOutcome(commandId, outcome) }
        }
    }

    private val adbProbe = Executors.newSingleThreadExecutor()
    private val adbProbing = AtomicBoolean(false)

    /** While a session plays, the TV reports every minute so the position stays close. */
    private val playingReports: PlayingReports = PlayingReports(
        delayMillis = PLAYING_REPORT_MILLIS,
        schedule = { delay, task ->
            val pending = scheduler.schedule(task, delay, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { pending.cancel(false) }
            cancel
        },
        report = { reporter.report() }
    )

    private val screenCapture = ScreenCapture(
        screen = { PortOsAccessibilityService.instance },
        post = { claims -> if (session.authLinkId != null) sender.reportState(claims) },
        background = Executors.newSingleThreadExecutor()
    )

    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Loads the pictures of Image components. */
    val images = HttpImageSource(http)

    /** The one surface the TV holds, driven by the gateway's messages. */
    val surface: SurfaceController = SurfaceController(
        scope = gatewayScope,
        effects = object : SurfaceEffects {
            override fun showForeground() = context.startActivity(SurfaceActivity.intent(context))

            // The surface activity watches the controller and finishes itself when the surface is gone.
            override fun closeForeground() = Unit

            override fun reply(correlationToken: String, payload: JsonObject) = background.execute {
                try {
                    sender.replyToGetSurface(correlationToken, payload)
                } catch (error: PortOsException) {
                    Log.w(TAG, "get-surface reply failed: ${error.message}")
                }
            }
        },
        display = ::displayInfo,
        onGap = { gateway.reconnect() }
    )

    /** The WebSocket to the device gateway; connects on start and on an FCM `generative-ui/connect`. */
    val gateway = GatewayConnector(
        http = http,
        gatewayUrl = BuildConfig.GATEWAY_URL,
        authLinkId = { session.authLinkId },
        tokens = TokenSource { forceRefresh -> session.accessToken(forceRefresh) },
        position = surface::position,
        onText = { text -> GatewayMessage.parse(text)?.let(surface::receive) },
        scope = gatewayScope
    )

    /** Connects to the gateway unless already connected; does nothing until the TV is paired. */
    fun connectGateway() {
        if (session.isPaired) gateway.wake()
    }

    /** The screen as the registration and `get-surface-response` describe it: real pixels, 960 x 540 dp layout. */
    private fun displayInfo(): JsonObject {
        val size = realDisplay.size()
        val metrics = context.resources.displayMetrics
        return SurfaceDisplay.info(size?.width ?: metrics.widthPixels, size?.height ?: metrics.heightPixels)
    }

    /** Human-readable name shown in Port OS, such as "Living Room TV". */
    val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * Looks for the TV's own adb daemon in the background when a look is due, and reports again if what the TV can
     * do changed. Never prompts more than the probe schedule allows, and never touches the network when not due.
     */
    fun probeAdbSoon() {
        if (!adb.probeDue() || !adbProbing.compareAndSet(false, true)) return
        adbProbe.execute {
            try {
                if (adb.refresh()) requestReport()
            } finally {
                adbProbing.set(false)
            }
        }
    }

    /**
     * Collapses the burst of media-session callbacks one change fires (often 2 to 3 in the same second) into a single
     * report that reads the state after the last of them.
     */
    private val coalescedReports = CoalescedRequests(
        delayMillis = REPORT_COALESCE_MILLIS,
        schedule = { delay, task -> scheduler.schedule(task, delay, TimeUnit.MILLISECONDS) },
        action = ::enqueueReport
    )

    /** Asks for a state report soon; bursts of changes collapse into one report. */
    fun requestReport() = coalescedReports.request()

    private fun enqueueReport() {
        if (!session.isPaired || session.needsPairing.value) return
        val request = OneTimeWorkRequestBuilder<ReportWorker>()
            .setConstraints(networkConstraint())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ReportWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Reports state periodically so Port OS notices a TV that went quiet. */
    fun scheduleHeartbeat() {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(HEARTBEAT_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HeartbeatWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelBackgroundWork() = WorkManager.getInstance(context).cancelAllWork()

    private suspend fun registration(): Registration {
        check(messagingConfigured) { "Firebase is not configured in this build" }
        return Registration(
            fcmToken = fcmToken(),
            deviceId = store.deviceId,
            deviceName = deviceName,
            model = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    private suspend fun fcmToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    private fun initFirebase(): Boolean {
        val configured = BuildConfig.FIREBASE_APPLICATION_ID != UNSET && BuildConfig.FIREBASE_API_KEY != UNSET
        if (configured && FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        return configured
    }

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val TAG = "PortOsGraph"
        const val UNSET = "unset"
        const val HTTP_TIMEOUT_SECONDS = 20L

        /** WorkManager's minimum period. */
        const val HEARTBEAT_MINUTES = 15L

        /** Report cadence while a session plays. */
        const val PLAYING_REPORT_MILLIS = 60_000L

        /** Media-session callbacks for one change arrive within a second or two; wait that out before reporting. */
        const val REPORT_COALESCE_MILLIS = 2_000L
    }
}
