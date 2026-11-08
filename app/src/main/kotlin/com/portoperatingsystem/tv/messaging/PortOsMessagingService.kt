package com.portoperatingsystem.tv.messaging

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.portoperatingsystem.tv.PortOsApplication
import com.portoperatingsystem.tv.control.Execution
import com.portoperatingsystem.tv.playback.PlaybackService
import com.portoperatingsystem.tv.protocol.command.Command
import com.portoperatingsystem.tv.protocol.command.CommandDecoder
import com.portoperatingsystem.tv.protocol.command.Decoded
import com.portoperatingsystem.tv.protocol.command.OwnPlayerCommand
import com.portoperatingsystem.tv.state.CommandOutcome
import com.portoperatingsystem.tv.state.FailureCode
import com.portoperatingsystem.tv.state.TokenRotationWorker

/** Receives Port OS commands over FCM, runs them, and asks for a state report so the result is visible. */
internal class PortOsMessagingService : FirebaseMessagingService() {
    private val graph get() = (application as PortOsApplication).graph

    override fun onMessageReceived(message: RemoteMessage) {
        var stateRead = false
        when (val decoded = CommandDecoder.decode(message.data)) {
            is Decoded.Accepted -> {
                val delivery = decoded.delivery
                stateRead = delivery.command is Command.ReportState
                val execution = graph.executor.execute(delivery.command, delivery.messageId)
                if (!stateRead) graph.reporter.commandRan()
                when (execution) {
                    Execution.Done -> Log.i(TAG, "command ${delivery.messageId} done")
                    is Execution.Failed -> {
                        Log.w(TAG, "command ${delivery.messageId} failed: ${execution.failure}")
                        // The caller of a command that is answered with an outcome is waiting for one.
                        if (delivery.command is OwnPlayerCommand) {
                            PlaybackService.outcomes.answer(
                                delivery.messageId,
                                CommandOutcome.Failed(FailureCode.PLAYBACK_FAILED, execution.failure.reason)
                            )
                        }
                    }
                }
            }
            is Decoded.Rejected -> Log.w(TAG, "command ${decoded.messageId} rejected: ${decoded.rejection}")
        }
        // A state read answers itself with a tagged report; a second, untagged one would only repeat it.
        if (!stateRead) graph.requestReport()
    }

    override fun onNewToken(token: String) {
        val request = OneTimeWorkRequestBuilder<TokenRotationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(TokenRotationWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val TAG = "PortOsCommand"
    }
}
