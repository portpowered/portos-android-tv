package com.portoperatingsystem.tv.control

import dadb.AdbException
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The app's own adb key, generated on first use and kept in [directory] (app-private, never backed up, never sent
 * anywhere). The TV's "Allow USB debugging?" prompt shows this key's fingerprint; allowing it is the owner opting in.
 */
internal class AdbKeys(private val directory: File) {
    private val privateKey get() = File(directory, "adbkey")
    private val publicKey get() = File(directory, "adbkey.pub")

    @Synchronized
    fun load(): AdbKeyPair {
        if (!privateKey.exists() || !publicKey.exists()) AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey)
    }
}

/**
 * [AdbTransport] to the adb daemon on this TV, over loopback TCP (the daemon's "network debugging" port) with the
 * `dadb` client. That client does not speak the TLS of Android's "wireless debugging" pairing.
 */
internal class DadbTransport(private val keys: AdbKeys, private val port: Int = ADB_PORT) : AdbTransport {
    private var session: Dadb? = null

    override fun probe(): AdbReply {
        if (!listening()) return AdbReply.NotListening
        return try {
            Dadb.create(HOST, port, keys.load(), CONNECT_TIMEOUT_MILLIS, PROMPT_WAIT_MILLIS).use { it.shell("true") }
            // The probe connection authenticated, so the key is allowed; commands open their own connection.
            AdbReply.Ok(0, "")
        } catch (_: AdbException) {
            // The port accepted a connection, so a handshake that failed or timed out is the daemon waiting for,
            // or refusing, this key.
            AdbReply.NotAuthorized
        } catch (error: IOException) {
            AdbReply.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    @Synchronized
    override fun run(command: String): AdbReply = try {
        val dadb = session ?: Dadb.create(HOST, port, keys.load(), CONNECT_TIMEOUT_MILLIS, COMMAND_TIMEOUT_MILLIS)
            .also { session = it }
        val response = dadb.shell(command)
        AdbReply.Ok(response.exitCode, response.allOutput)
    } catch (error: IOException) {
        // Drop a connection that may be half dead; the next probe builds a new one.
        session?.close()
        session = null
        AdbReply.Failed(error.message ?: error.javaClass.simpleName)
    }

    private fun listening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MILLIS) }
        true
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val ADB_PORT = 5555
        const val CONNECT_TIMEOUT_MILLIS = 3_000

        /** The owner needs time to pick up the remote and press Allow. */
        const val PROMPT_WAIT_MILLIS = 60_000

        const val COMMAND_TIMEOUT_MILLIS = 5_000
    }
}
