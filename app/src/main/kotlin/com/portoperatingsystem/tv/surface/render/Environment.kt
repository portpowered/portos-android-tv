package com.portoperatingsystem.tv.surface.render

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.portoperatingsystem.tv.surface.SurfaceDisplay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val MILLIS_PER_SECOND = 1000L

/** Loads the pixels of an Image component's URL. Null is a failed load, which renders the muted placeholder. */
internal fun interface ImageSource {
    suspend fun load(url: String): ImageBitmap?
}

/** The default [ImageSource]: an OkHttp download decoded with [BitmapFactory], off the main thread. */
internal class HttpImageSource(private val client: OkHttpClient) : ImageSource {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val bytes = if (response.isSuccessful) response.body?.bytes() else null
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A bad URL, a network error or an undecodable body all mean the same to the screen: a placeholder.
            null.also { Log.w("PortOsSurface", "image load failed: ${error.message}") }
        }
    }
}

/** The clock behind Timer components: the wall time, and a suspension that returns when the display should tick. */
internal interface TimerClock {
    fun nowMillis(): Long

    suspend fun awaitTick()
}

/** Ticks on each wall-clock second, so a countdown never skips a number. */
internal object SystemTimerClock : TimerClock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override suspend fun awaitTick() = delay(MILLIS_PER_SECOND - nowMillis() % MILLIS_PER_SECOND)
}

/** A clock fixed at [millis] that never ticks, for screenshots and tests. */
internal class FrozenTimerClock(private val millis: Long) : TimerClock {
    override fun nowMillis(): Long = millis

    override suspend fun awaitTick() = awaitCancellation()
}

internal val LocalImageSource = compositionLocalOf<ImageSource> { ImageSource { null } }
internal val LocalTimerClock = compositionLocalOf<TimerClock> { SystemTimerClock }

/**
 * Lays everything out at 960 x 540 dp whatever the panel: the density is the panel width over 960, and the font scale
 * is pinned so the user's system setting cannot reflow a surface the agent sized for the TV.
 */
@Composable
internal fun PortosTheme(panelWidthPx: Int, content: @Composable () -> Unit) {
    val density = Density(density = SurfaceDisplay.density(panelWidthPx), fontScale = 1f)
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
