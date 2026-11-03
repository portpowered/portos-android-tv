package com.portoperatingsystem.tv.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.cloud.PortOsClient
import com.portoperatingsystem.tv.cloud.Registration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A plain Application: the real one builds the full graph (Keystore, WorkManager), which this test does not need.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class SessionTest {
    private val server = MockWebServer().apply { start() }
    private var now = 1_000L
    private val store = CredentialStore(ApplicationProvider.getApplicationContext())
    private val client = PortOsClient(
        http = OkHttpClient(),
        baseUrl = server.url("").toString().trimEnd('/'),
        clientId = "client",
        pluginId = "plugin",
        signer = SoftwareSigner(),
        clock = { now }
    )
    private var fcmToken = "fcm-1"
    private val session = Session(client, store, { Registration(fcmToken, store.deviceId, "TV", "TCL") }, { now })

    @After
    fun stop() = server.shutdown()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun pairsThroughDeviceCodeAndEstablishesInstallation() = runTest {
        respond(200, """{"device_code":"d","user_code":"U","verification_uri":"v","expires_in":600,"interval":1}""")
        respond(400, """{"error":"authorization_pending"}""")
        respond(200, """{"access_token":"a1","refresh_token":"r1","expires_in":3600}""")
        respond(200, """{"id":"x","etag":"e","status":"ACTIVE","authLinkId":"link-1"}""")
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        assertEquals(PairingState.Starting, states.first())
        assertIs<PairingState.AwaitingApproval>(states[1])
        assertEquals(PairingState.Paired, states.last())
        assertTrue(session.isPaired)
        assertEquals("link-1", session.authLinkId)
        assertEquals(Credentials("a1", "r1", now + 3600, "link-1"), store.load())
    }

    @Test
    fun deniedPairingFailsWithoutCredentials() = runTest {
        respond(200, """{"device_code":"d","user_code":"U","verification_uri":"v","expires_in":600,"interval":1}""")
        respond(400, """{"error":"access_denied"}""")
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        assertEquals(PairingState.Failed("access_denied"), states.last())
        assertFalse(session.isPaired)
    }

    @Test
    fun deviceAuthorizationRejectionShowsTheOAuthErrorDescription() = runTest {
        respond(400, """{"error":"invalid_request","error_description":"device_pubkey is not a valid JWK"}""")
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        assertEquals(PairingState.Failed("device_pubkey is not a valid JWK"), states.last())
    }

    @Test
    fun deviceAuthorizationWithOnlyAnOAuthErrorShowsTheError() = runTest {
        respond(400, """{"error":"invalid_client"}""")
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        assertEquals(PairingState.Failed("invalid_client"), states.last())
    }

    @Test
    fun establishFailureShowsThePortOsErrorMessage() = runTest {
        respond(200, """{"device_code":"d","user_code":"U","verification_uri":"v","expires_in":600,"interval":1}""")
        respond(200, """{"access_token":"a1","refresh_token":"r1","expires_in":3600}""")
        respond(500, """{"family":"INTERNAL","code":"INTERNAL","message":"installation could not be created"}""")
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        assertEquals(PairingState.Failed("installation could not be created"), states.last())
        assertFalse(session.isPaired)
    }

    @Test
    fun anUnreadableErrorBodyShowsTheHttpStatus() = runTest {
        respond(502, "<html>bad gateway</html>")
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        assertEquals(PairingState.Failed("HTTP 502"), states.last())
    }

    @Test
    fun aTokenEndpointServerErrorShowsItsMessage() = runTest {
        respond(200, """{"device_code":"d","user_code":"U","verification_uri":"v","expires_in":600,"interval":1}""")
        respond(500, """{"error":"server_error","error_description":"signing key unavailable"}""")
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        assertEquals(PairingState.Failed("signing key unavailable"), states.last())
    }

    @Test
    fun unreachablePortOsShowsACantReachMessage() = runTest {
        server.shutdown()
        val states = mutableListOf<PairingState>()

        session.pair { states += it }

        val failed = assertIs<PairingState.Failed>(states.last())
        assertTrue(failed.reason.startsWith("can't reach Port OS: "), failed.reason)
    }

    @Test
    fun everyPairingAttemptStartsFromANewIdentity() = runTest {
        val requestsAtNewIdentity = mutableListOf<Int>()
        val rekeying = Session(client, store, { Registration(fcmToken, store.deviceId, "TV", "TCL") }, { now }) {
            requestsAtNewIdentity += server.requestCount
        }
        store.save(Credentials("old", "old", now + 3600, "link-old"))
        respond(200, """{"device_code":"d","user_code":"U","verification_uri":"v","expires_in":600,"interval":1}""")
        respond(400, """{"error":"access_denied"}""")
        respond(200, """{"device_code":"d2","user_code":"U2","verification_uri":"v","expires_in":600,"interval":1}""")
        respond(400, """{"error":"access_denied"}""")

        rekeying.pair {}
        assertFalse(rekeying.isPaired)
        rekeying.pair {}

        assertEquals(listOf(0, 2), requestsAtNewIdentity)
    }

    @Test
    fun renewalRefreshesNearExpiryAndKeepsTheSameLink() = runTest {
        store.save(Credentials("a1", "r1", accessTokenExpiresAtSeconds = now + 60, authLinkId = "link-1"))
        respond(200, """{"access_token":"a2","expires_in":3600}""")
        respond(200, """{"id":"x","etag":"e","status":"ACTIVE","authLinkId":"link-1"}""")
        fcmToken = "fcm-2"

        session.renewInstallation()

        assertEquals("/auth/token", server.takeRequest().path)
        val establish = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("link-1", establish["authLinkId"]?.jsonPrimitive?.content)
        assertEquals(
            "fcm-2",
            establish["pluginAuthenticationData"]?.jsonObject?.get("fcmToken")?.jsonPrimitive?.content
        )
        assertEquals(Credentials("a2", "r1", now + 3600, "link-1"), store.load())
    }

    @Test
    fun unpairKeepsDeviceId() {
        val id = store.deviceId
        store.save(Credentials("a", "r", 0, "link"))
        session.unpair()
        assertFalse(session.isPaired)
        assertEquals(id, store.deviceId)
    }
}
