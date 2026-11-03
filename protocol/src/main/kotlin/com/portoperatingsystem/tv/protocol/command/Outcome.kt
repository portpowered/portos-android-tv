package com.portoperatingsystem.tv.protocol.command

/** What decoding a message body produced: a command to run or the reason it is not one. */
internal sealed interface Outcome {
    data class Accept(val command: Command) : Outcome

    data class Reject(val rejection: Rejection) : Outcome
}

internal fun invalid(reason: String): Outcome = Outcome.Reject(Rejection.InvalidMessage(reason))
