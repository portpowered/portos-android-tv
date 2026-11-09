package com.portoperatingsystem.tv.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.portoperatingsystem.tv.cloud.PortOsClient
import com.portoperatingsystem.tv.cloud.Registration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

// A TV paired before device tokens carried the auth link migrates by renewing its installation and refreshing once.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class LinkClaimTest {
    private val server = MockWebServer().apply { start() }
    private val now = 1_000L
    private val store = CredentialStore(ApplicationProvider.getApplicationContext())
    private val client = PortOsClient(
        http = OkHttpClient(),
        baseUrl = server.url("").toString().trimEnd('/'),
        clientId = "client",
        pluginId = "plugin",
        signer = SoftwareSigner(),
        clock = { now }
    )
    private val session = Session(client, store, { Registration("fcm-1", store.deviceId, "TV", "TCL") }, { now })

    @After
    fun stop() = server.shutdown()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun jwt(claims: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val parts = listOf("""{"alg":"ES256"}""", claims, "sig")
        return parts.joinToString(".") { part -> encoder.encodeToString(part.toByteArray()) }
    }

    @Test
    fun readsTheLinkClaimFromTheTokenPayload() {
        assertTrue(Session.carriesLinkClaim(jwt("""{"sub":"p","${Session.LINK_CLAIM}":"link-1"}""")))
        assertFalse(Session.carriesLinkClaim(jwt("""{"sub":"p"}""")))
        assertFalse(Session.carriesLinkClaim(jwt("""{"${Session.LINK_CLAIM}":""}""")))
        assertFalse(Session.carriesLinkClaim("opaque"))
        assertFalse(Session.carriesLinkClaim("a.%%%.c"))
    }

    @Test
    fun aTokenWithoutTheClaimRenewsTheInstallationThenRefreshes() = runTest {
        val unlinked = jwt("""{"sub":"p"}""")
        val linked = jwt("""{"sub":"p","${Session.LINK_CLAIM}":"link-1"}""")
        store.save(Credentials(unlinked, "r1", accessTokenExpiresAtSeconds = now + 3600, authLinkId = "link-1"))
        respond(200, """{"id":"x","etag":"e","status":"ACTIVE","authLinkId":"link-1"}""")
        respond(200, """{"access_token":"$linked","refresh_token":"r2","expires_in":3600}""")

        session.ensureLinkClaim()

        val renewal = server.takeRequest()
        assertEquals("/plugin-auth-code-exchanges", renewal.path)
        assertEquals("Bearer $unlinked", renewal.getHeader("Authorization"))
        val body = Json.parseToJsonElement(renewal.body.readUtf8()).jsonObject
        assertEquals("link-1", body["authLinkId"]?.jsonPrimitive?.content)
        assertEquals("/auth/token", server.takeRequest().path)
        assertEquals(Credentials(linked, "r2", now + 3600, "link-1"), store.load())
    }

    @Test
    fun aTokenWithTheClaimSendsNothing() = runTest {
        val linked = jwt("""{"sub":"p","${Session.LINK_CLAIM}":"link-1"}""")
        store.save(Credentials(linked, "r1", accessTokenExpiresAtSeconds = now + 3600, authLinkId = "link-1"))

        session.ensureLinkClaim()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnpairedTvSendsNothing() = runTest {
        session.ensureLinkClaim()

        assertEquals(0, server.requestCount)
    }
}
