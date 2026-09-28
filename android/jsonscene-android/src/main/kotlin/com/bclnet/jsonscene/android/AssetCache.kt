/*
 * AssetCache.kt
 * JsonScene (Android)
 *
 * Fetches remote models and sounds into the app cache once, so the
 * renderers can hand a local file to Filament, the Spatial SDK or MediaPlayer.
 */
package com.bclnet.jsonscene.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class AssetCache(context: Context) {
    val directory: File = File(context.cacheDir, "JsonScene").apply { mkdirs() }

    /** A local file for `uri` (itself when it is a file), downloading it once. */
    suspend fun localFile(uri: URI): File = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext File(uri)
        val extension = uri.path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 } ?: "bin"
        val target = File(directory, "${Integer.toHexString(uri.toString().hashCode())}.$extension")
        if (target.exists() && target.length() > 0) return@withContext target
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode} for $uri")
            val temp = File(directory, target.name + ".part")
            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
            target
        } finally {
            connection.disconnect()
        }
    }
}
