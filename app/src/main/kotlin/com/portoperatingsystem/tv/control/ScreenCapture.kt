package com.portoperatingsystem.tv.control

import android.graphics.Bitmap
import android.util.Log
import com.portoperatingsystem.tv.cloud.PortOsException
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.Executor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Answers a `snapshot/get-snapshot` push: captures the screen through the accessibility service, shrinks it to fit
 * the message limit, and posts it with [requestId] so the plugin can complete the request that is waiting.
 * FCM is one-way, which is why the answer travels through `POST /messages` rather than back along the push.
 */
internal class ScreenCapture(
    private val screen: () -> ScreenControl?,
    private val post: (JsonObject) -> Unit,
    private val background: Executor,
    private val encode: (Bitmap) -> EncodedImage? = ::encodeBitmap
) {
    fun capture(requestId: String) {
        val control = screen()
        if (control == null) {
            Log.w(TAG, "snapshot $requestId skipped: the accessibility service is not connected")
            return
        }
        // The callback arrives on the main thread; encoding and the upload do not belong there.
        control.capture { bitmap -> background.execute { send(requestId, bitmap) } }
    }

    private fun send(requestId: String, bitmap: Bitmap?) {
        val image = bitmap?.let { captured -> encode(captured).also { captured.recycle() } }
        if (image == null) {
            Log.w(TAG, "snapshot $requestId failed: no image fits the upload limit")
            return
        }
        try {
            post(snapshotPayload(requestId, image))
            if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, "snapshot $requestId sent (${image.bytes.size} bytes)")
        } catch (error: PortOsException) {
            Log.w(TAG, "snapshot $requestId upload failed: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "PortOsSnapshot"
    }
}

/** The `report-state` payload that carries one screenshot (protocol in the README). */
internal fun snapshotPayload(requestId: String, image: EncodedImage): JsonObject = buildJsonObject {
    put(
        "snapshot",
        buildJsonObject {
            put("requestId", requestId)
            put("width", image.width)
            put("height", image.height)
            put("data", Base64.getEncoder().encodeToString(image.bytes))
        }
    )
}

/** Scales [bitmap] down through the [ScreenshotEncoder] ladder and compresses each attempt as JPEG. */
internal fun encodeBitmap(bitmap: Bitmap): EncodedImage? =
    ScreenshotEncoder.encode(bitmap.width, bitmap.height) { width, height, quality ->
        val scaled = if (width == bitmap.width) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bytes
    }
