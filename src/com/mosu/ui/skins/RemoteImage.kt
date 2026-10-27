package com.mosu.ui.skins

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal remote image loader with an in-memory cache, enough for skin previews.
 */
private object RemoteImages {

    val client = OkHttpClient()

    // Sized in KiB, keeps roughly 24 MiB of decoded previews.
    val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
}

@Composable
fun rememberRemoteImage(url: String): State<ImageBitmap?> =
    produceState<ImageBitmap?>(RemoteImages.cache[url]?.asImageBitmap(), url) {

        if (value != null) return@produceState

        val bitmap = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()

                RemoteImages.client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) BitmapFactory.decodeStream(response.body!!.byteStream()) else null
                }
            } catch (_: Exception) {
                null
            }
        }

        if (bitmap != null) {
            RemoteImages.cache.put(url, bitmap)
            value = bitmap.asImageBitmap()
        }
    }
