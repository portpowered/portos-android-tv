package com.portoperatingsystem.tv.cloud

import com.portoperatingsystem.tv.auth.SoftwareSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After

class PortOsClientTest {
    private val server = MockWebServer().apply { start() }
    private val signer = SoftwareSigner()
    private val client = PortOsClient(
        http = OkHttpClient(),
        baseUrl = server.url("").toString().trimEnd('/'),
        clientId = "port1/systems/zero/oauth-clients/android-tv",
        pluginId = "port1/systems/zero/plugins/tv",
        signer = signer
    )

    @After
    fun stop() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun deviceAuthorizationSendsKeyAndDpop() {
        server.enqueue(
            json(
                200,
                """{"device_code":"d","user_code":"ABCD-EFGH","verification_uri":"https://x/device","expires_in":600}"""
            )
        )
        val auth = client.authorizeDevice()
        assertEquals("ABCD-EFGH", auth.userCode)
        assertEquals(5, auth.interval)
        val request = server.takeRequest()
        assertEquals("/auth/device-authorization", request.path)
        assertTrue(signer.verify(assertNotNull(request.getHeader("DPoP"))))
        assertTrue(request.body.readUtf8().contains("client_id=port1%2Fsystems%2Fzero%2Foauth-clients%2Fandroid-tv"))
    }

    @Test
    fun pollingMapsOAuthErrors() {
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(json(400, """{"error":"access_denied","error_description":"denied by owner"}"""))
        server.enqueue(json(200, """{"access_token":"a","refresh_token":"r","expires_in":3600}"""))
        assertEquals(PollResult.Pending, client.pollToken("d"))
        assertEquals(PollResult.SlowDown, client.pollToken("d"))
        assertEquals(PollResult.Failed("denied by owner"), client.pollToken("d"))
        assertEquals(PollResult.Approved(TokenResponse("a", "r", 3600)), client.pollToken("d"))
    }

    @Test
    fun establishSendsRegistrationWithIdempotencyKey() {
        server.enqueue(json(200, """{"id":"x","etag":"e","status":"ACTIVE","authLinkId":"link-1"}"""))
        val linkId = client.establish("token", Registration("fcm", "dev", "Living Room TV", "TCL"), authLinkId = null)
        assertEquals("link-1", linkId)
        val request = server.takeRequest()
        assertEquals("/plugin-auth-code-exchanges", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertNotNull(request.getHeader("Idempotency-Key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("~self", body["principalId"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("authLinkId"))
        val data = assertNotNull(body["pluginAuthenticationData"]).jsonObject
        assertEquals("fcm", data["fcmToken"]?.jsonPrimitive?.content)
        assertEquals(signer.publicJwk, data["publicKeyJwk"]?.jsonObject)
    }

    @Test
    fun postMessageSendsTheMessageWithBearerAndIdempotencyKey() {
        server.enqueue(json(202, """{"receiptId":"r-1"}"""))
        val message = buildJsonObject { put("header", buildJsonObject { put("name", "report-state") }) }
        client.postMessage("token", message, "key-1")
        val request = server.takeRequest()
        assertEquals("/messages", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("key-1", request.getHeader("Idempotency-Key"))
        assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals(message, Json.parseToJsonElement(request.body.readUtf8()))
    }

    @Test
    fun serverErrorsAreRetryableClientErrorsAreNot() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(assertFailsWith<PortOsException> { client.postMessage("t", buildJsonObject {}, "k") }.retryable)
        assertFalse(assertFailsWith<PortOsException> { client.postMessage("t", buildJsonObject {}, "k") }.retryable)
    }
}
