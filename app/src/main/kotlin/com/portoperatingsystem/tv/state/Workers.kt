package com.portoperatingsystem.tv.state

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.portoperatingsystem.tv.PortOsApplication
import com.portoperatingsystem.tv.cloud.PortOsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val Context.graph get() = (applicationContext as PortOsApplication).graph

/** Sends one state report, retrying with backoff while Port OS is unreachable. */
internal class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (applicationContext.graph.reporter.report()) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "portos-state-report"
    }
}

/** The periodic report that tells Port OS this TV is still reachable. */
internal class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        applicationContext.graph.reporter.report()
        Result.success()
    }

    companion object {
        const val NAME = "portos-heartbeat"
    }
}

/** Re-submits the FCM token after Firebase rotates it, so commands keep reaching this TV. */
internal class TokenRotationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val session = applicationContext.graph.session
        if (!session.isPaired) return@withContext Result.success()
        try {
            session.renewInstallation()
            Result.success()
        } catch (error: PortOsException) {
            Log.w(TAG, "token rotation failed: ${error.message}")
            if (error.retryable) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val NAME = "portos-token-rotation"
        private const val TAG = "PortOsToken"
    }
}
