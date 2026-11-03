package com.portoperatingsystem.tv.control

import android.app.Application
import android.graphics.Bitmap
import com.portoperatingsystem.tv.cloud.PortOsException
import com.portoperatingsystem.tv.protocol.command.NavigationKey
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeScreen(private val bitmap: Bitmap?) : ScreenControl {
    override fun press(key: NavigationKey) = true

    override fun capture(onCaptured: (Bitmap?) -> Unit) = onCaptured(bitmap)
}

private val Inline = Executor { it.run() }

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ScreenCaptureTest {
    private val posted = mutableListOf<JsonObject>()
    private val image = EncodedImage(byteArrayOf(1, 2, 3), width = 8, height = 4)

    private fun capture(
        screen: ScreenControl?,
        encoded: EncodedImage? = image,
        post: (JsonObject) -> Unit = { posted += it }
    ) = ScreenCapture(
        screen = { screen },
        post = post,
        background = Inline,
        encode = { encoded },
        clock = { 1_000L }
    )

    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun postsTheEncodedScreenshotTaggedWithTheRequest() {
        capture(FakeScreen(bitmap())).capture("req-1")

        assertEquals(1, posted.size)
        val claims = posted[0]
        assertEquals(JsonPrimitive(1_000L), claims["iat"])
        assertTrue(claims.getValue("jti").jsonPrimitive.content.isNotEmpty())
        assertEquals(
            Json.parseToJsonElement("""{"requestId":"req-1","width":8,"height":4,"data":"AQID"}"""),
            claims["snapshot"]
        )
    }

    @Test
    fun postsNothingWhenTheServiceIsNotConnected() {
        capture(screen = null).capture("req-2")

        assertTrue(posted.isEmpty())
    }

    @Test
    fun postsNothingWhenTheScreenCouldNotBeCaptured() {
        capture(FakeScreen(null)).capture("req-3")

        assertTrue(posted.isEmpty())
    }

    @Test
    fun postsNothingWhenNoEncodingFitsTheLimit() {
        capture(FakeScreen(bitmap()), encoded = null).capture("req-4")

        assertTrue(posted.isEmpty())
    }

    @Test
    fun anUploadFailureIsLoggedNotThrown() {
        capture(FakeScreen(bitmap()), post = { throw PortOsException("down", retryable = true) }).capture("req-5")

        assertTrue(posted.isEmpty())
    }

    @Test
    fun theClaimsCarryTheStandardBase64OfTheJpeg() {
        val claims = snapshotClaims("r", EncodedImage(ByteArray(4) { 0xFB.toByte() }, 2, 2), nowSeconds = 7, jti = "j")

        assertEquals(
            Json.parseToJsonElement(
                """{"iat":7,"jti":"j","snapshot":{"requestId":"r","width":2,"height":2,"data":"+/v7+w=="}}"""
            ),
            claims
        )
    }
}
