package com.aydin.sunostudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Shared text often contains a caption + link; pull out the first http(s) link. */
    fun extractUrl(s: String): String {
        val m = Regex("https?://[^\\s]+").find(s)
        val v = (m?.value ?: s).trim()
        return v.trimEnd('.', ',', ')', ']', '"', '\'')
    }

    fun withScheme(u: String): String =
        if (u.startsWith("http://", true) || u.startsWith("https://", true)) u else "https://$u"

    fun hostOf(u: String): String = try {
        URL(withScheme(u)).host.lowercase()
    } catch (_: Exception) {
        ""
    }

    fun hostMatches(host: String, domains: List<String>): Boolean =
        domains.any { host == it || host.endsWith(".$it") }

    fun loadBitmap(url: String, maxPx: Int): Bitmap? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxPx * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            null
        }
    }
}

/** Saves finished files into the public Music / Movies folders using MediaStore (no permissions needed). */
object Store {
    enum class Kind { AUDIO, VIDEO }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    fun kindFor(name: String): Kind = if (mimeFor(name).startsWith("video/")) Kind.VIDEO else Kind.AUDIO

    fun safeName(s: String, fallback: String): String {
        val cleaned = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trim('.').take(80).trim()
        return cleaned.ifBlank { fallback }
    }

    fun publish(
        ctx: Context,
        file: File,
        displayName: String,
        kind: Kind,
        onProgress: ((Float) -> Unit)? = null
    ): Uri {
        val resolver = ctx.contentResolver
        val collection = if (kind == Kind.AUDIO) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val dir = if (kind == Kind.AUDIO) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Suno Studio")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("اندروید نتوانست فایل خروجی را بسازد.")
        try {
            val total = file.length().coerceAtLeast(1L)
            var done = 0L
            val out = resolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("اندروید نتوانست فایل خروجی را باز کند.")
            out.use { o ->
                file.inputStream().use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done.toFloat() / total)
                    }
                    o.flush()
                }
            }
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, v, null, null)
            return uri
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }
    }
}

fun humanSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1048576.0
    return if (mb >= 1) String.format(java.util.Locale.US, "%.1f MB", mb)
    else String.format(java.util.Locale.US, "%d KB", (bytes / 1024).toInt())
}

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}
