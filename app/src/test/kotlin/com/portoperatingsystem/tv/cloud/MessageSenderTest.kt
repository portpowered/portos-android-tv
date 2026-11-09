package com.portoperatingsystem.tv.cloud

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.auth.CredentialStore
import com.portoperatingsystem.tv.auth.Credentials
import com.portoperatingsystem.tv.auth.Session
import com.portoperatingsystem.tv.auth.SoftwareSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class MessageSenderTest {
    private val server = MockWebServer().apply { start() }
    private val store = CredentialStore(ApplicationProvider.getApplicationContext()).also {
        it.save(Credentials("access-1", "refresh-1", 10_000L, "link-1"))
    }
    private val client = PortOsClient(
        http = OkHttpClient(),
        baseUrl = server.url("").toString().trimEnd('/'),
        clientId = "client",
        pluginId = "plugin",
        signer = SoftwareSigner(),
        clock = { 1_000L }
    )
    private val session = Session(client, store, { Registration("fcm", "dev-1", "TV", "TCL") }, { 1_000L })
    private val sender = MessageSender(client, session) { "dev-1" }

    @After
    fun stop() = server.shutdown()

    private fun accepted() = MockResponse().setResponseCode(202).setBody("""{"receiptId":"r"}""")

    private fun body(request: okhttp3.mockwebserver.RecordedRequest): JsonObject =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun reportStatePostsTheMessageToTheTvRoute() {
        server.enqueue(accepted())

        sender.reportState(buildJsonObject { put("sampledAtMs", 5L) })

        val request = server.takeRequest()
        assertEquals("/messages", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertEquals(36, request.getHeader("Idempotency-Key")?.length)
        val message = body(request)
        val header = message.getValue("header").jsonObject
        assertEquals("port1/systems/zero/plugins/android-tv", header.getValue("namespace").jsonPrimitive.content)
        assertEquals("report-state", header.getValue("name").jsonPrimitive.content)
        assertEquals("1.0", header.getValue("version").jsonPrimitive.content)
        assertEquals(36, header.getValue("id").jsonPrimitive.content.length)
        assertEquals("ROUTE", message.getValue("target").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("dev-1", message.getValue("target").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("5", message.getValue("payload").jsonObject.getValue("sampledAtMs").jsonPrimitive.content)
        assertNull(message["context"])
    }

    @Test
    fun getSurfaceResponseCopiesTheCorrelationTokenIntoContext() {
        server.enqueue(accepted())

        sender.replyToGetSurface("corr-1", buildJsonObject { put("surfaceId", "s") })

        val message = body(server.takeRequest())
        val header = message.getValue("header").jsonObject
        assertEquals(
            "port1/systems/zero/capability-interfaces/generative-ui",
            header.getValue("namespace").jsonPrimitive.content
        )
        assertEquals("get-surface-response", header.getValue("name").jsonPrimitive.content)
        assertEquals(
            "corr-1",
            message.getValue("context").jsonObject.getValue("correlationToken").jsonPrimitive.content
        )
        assertEquals("s", message.getValue("payload").jsonObject.getValue("surfaceId").jsonPrimitive.content)
    }

    @Test
    fun aRetriedMessageKeepsItsIdempotencyKeySoItIsDeliveredOnce() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(accepted())

        sender.replyToGetSurface("corr-1", buildJsonObject {})

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        assertEquals(body(first), body(second))
    }

    @Test
    fun separateMessagesGetSeparateKeys() {
        server.enqueue(accepted())
        server.enqueue(accepted())

        sender.reportState(buildJsonObject {})
        sender.reportState(buildJsonObject {})

        assertNotEquals(
            server.takeRequest().getHeader("Idempotency-Key"),
            server.takeRequest().getHeader("Idempotency-Key")
        )
    }

    @Test
    fun aClientErrorIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(accepted())

        val error = assertFailsWith<PortOsException> { sender.reportState(buildJsonObject {}) }

        assertEquals(false, error.retryable)
        assertEquals(1, server.requestCount)
    }
}
