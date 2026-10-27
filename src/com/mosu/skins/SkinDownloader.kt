package com.mosu.skins

import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a skin archive using the session (cookies) created by the WebView where the user solved the captcha.
 */
object SkinDownloader {

    private val client = OkHttpClient()

    /**
     * @param onProgress Called with a 0..1 progress (or -1 when the size is unknown) and the speed in bytes/second.
     */
    suspend fun download(
        url: String,
        userAgent: String,
        referer: String,
        target: File,
        onProgress: (Float, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {

        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl == null || httpUrl.scheme != "https" || !OsuSkinsClient.isTrustedHost(httpUrl.host)) {
            throw IOException("Untrusted download URL")
        }

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .apply { CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) } }
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            val type = response.header("Content-Type").orEmpty()
            if (type.startsWith("text/html")) {
                throw IOException("The server did not return a skin file")
            }

            val total = response.body!!.contentLength()
            val started = System.nanoTime()
            var read = 0L

            response.body!!.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        read += count

                        val seconds = ((System.nanoTime() - started) / 1e9).coerceAtLeast(0.001)
                        onProgress(if (total > 0) read.toFloat() / total else -1f, (read / seconds).toLong())
                    }
                }
            }
        }
    }

    
}
