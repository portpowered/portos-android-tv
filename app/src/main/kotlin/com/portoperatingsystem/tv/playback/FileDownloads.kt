package com.portoperatingsystem.tv.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Downloads the files `play-video`, `play-audio` and the slideshow messages point at into [directory], one at a time
 * and off the main thread. The link expires within minutes, so the player plays the local copy and a loop never fetches
 * again.
 */
internal class FileDownloads(private val directory: File, private val client: OkHttpClient = OkHttpClient()) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Fetches [url] and calls [done], on the main thread, with the saved file, or null when the download failed. */
    fun download(url: String, mimeType: String, done: (File?) -> Unit) =
        downloadAll(listOf(url to mimeType)) { saved -> done(saved?.single()) }

    /**
     * Fetches every file of [files] (url and mimeType), in order, and calls [done], on the main thread, with the saved
     * files in the same order, or with null, keeping none, when any download failed. After [close] nothing is kept
     * and [done] is not called, so a download that was still running leaves no file behind.
     */
    fun downloadAll(files: List<Pair<String, String>>, done: (List<File>?) -> Unit) {
        executor.execute {
            val saved = mutableListOf<File>()
            for ((url, mimeType) in files) {
                val file = fetch(url, mimeType)?.also(saved::add)
                if (file == null || executor.isShutdown) break
            }
            when {
                executor.isShutdown -> saved.forEach(File::delete)
                saved.size < files.size -> {
                    saved.forEach(File::delete)
                    main.post { done(null) }
                }
                else -> main.post { done(saved) }
            }
        }
    }

    /** Stops downloading and deletes every file, saved or half written. */
    fun close() {
        executor.shutdownNow()
        deleteAll()
    }

    /** Deletes every file in the directory, such as those a killed process left behind. */
    fun deleteAll() {
        directory.listFiles()?.forEach(File::delete)
    }

    private fun fetch(url: String, mimeType: String): File? {
        directory.mkdirs()
        val file = File(directory, "${UUID.randomUUID()}.${extension(mimeType)}")
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { save(it, file) }
        } catch (error: IOException) {
            Log.w(TAG, "download failed: ${error.message}")
            file.delete()
            null
        }
    }

    private fun save(response: Response, file: File): File? {
        val body = response.body
        if (!response.isSuccessful || body == null) {
            Log.w(TAG, "download failed with HTTP ${response.code}")
            return null
        }
        file.outputStream().use { out -> body.byteStream().copyTo(out) }
        return file
    }

    private fun extension(mimeType: String): String = when (mimeType) {
        "audio/mpeg" -> "mp3"
        else -> mimeType.substringAfter('/').filter(Char::isLetterOrDigit).ifEmpty { "bin" }
    }

    private companion object {
        const val TAG = "PortOsPlayback"
    }
}
