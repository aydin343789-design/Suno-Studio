package com.aydin.sunostudio

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MediaInfo(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnail: String
)

/** Thin wrapper around the bundled yt-dlp engine (YouTube, Instagram, Facebook, Suno ...). */
object Media {
    /** One way of asking YouTube for the video. Different "player clients" get different bot checks. */
    private class Attempt(val clientArg: String?, val ejs: Boolean)

    private val youtubeAttempts = listOf(
        Attempt(null, true),
        Attempt("youtube:player_client=android_vr", false),
        Attempt("youtube:player_client=tv_simply", false),
        Attempt("youtube:player_client=mweb", true),
        Attempt("youtube:player_client=ios", false),
        Attempt(null, false)
    )

    @Volatile
    private var goodAttempt: Attempt? = null

    @Volatile
    private var cancelRequested = false

    private fun isYoutube(url: String): Boolean {
        val h = Net.hostOf(url)
        return h.contains("youtube") || h.contains("youtu.be")
    }

    private fun common(req: YoutubeDLRequest, a: Attempt) {
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--socket-timeout", "25")
        req.addOption("--retries", "5")
        req.addOption("--fragment-retries", "5")
        if (a.ejs) req.addOption("--remote-components", "ejs:github")
        val c = a.clientArg
        if (c != null) req.addOption("--extractor-args", c)
    }

    private fun shouldRetry(t: Throwable): Boolean {
        if (cancelRequested) return false
        val m = (t.message ?: "").lowercase()
        // Only stop early for errors that another YouTube client cannot fix.
        return !(m.contains("private video") || m.contains("video is private") || m.contains("unsupported url"))
    }

    private fun <T> withAttempts(url: String, block: (Attempt) -> T): T {
        val list: List<Attempt> = if (isYoutube(url)) {
            val g = goodAttempt
            if (g != null) listOf(g) + youtubeAttempts.filter { it !== g } else youtubeAttempts
        } else {
            listOf(Attempt(null, false))
        }
        var last: Throwable? = null
        for (a in list) {
            try {
                val r = block(a)
                if (isYoutube(url)) goodAttempt = a
                return r
            } catch (t: Throwable) {
                last = t
                if (!shouldRetry(t)) break
            }
        }
        throw last ?: IllegalStateException("خطای نامشخص")
    }

    fun fetchInfo(url: String): MediaInfo = withAttempts(url) { a ->
        val req = YoutubeDLRequest(url)
        common(req, a)
        req.addOption("--dump-single-json")
        req.addOption("--skip-download")
        val resp = YoutubeDL.getInstance().execute(req)
        val out = (resp.out ?: "").trim()
        val start = out.indexOf('{')
        if (start < 0) throw IllegalStateException("پاسخ اطلاعات ویدیو خالی بود (empty).")
        var json = JSONObject(out.substring(start))
        val entries = json.optJSONArray("entries")
        if (entries != null && entries.length() > 0) {
            json = entries.optJSONObject(0) ?: json
        }
        val uploader = json.optString("uploader").ifBlank {
            json.optString("channel").ifBlank { json.optString("uploader_id") }
        }
        MediaInfo(
            title = json.optString("title").ifBlank { "video" },
            uploader = uploader,
            durationSec = json.optDouble("duration", 0.0).toLong(),
            thumbnail = json.optString("thumbnail")
        )
    }

    /**
     * Blocking download into a private cache folder; the caller moves the result into Music/Movies.
     * heightLimit: maximum video height; audioOnly: extract MP3.
     */
    fun download(
        ctx: Context,
        url: String,
        heightLimit: Int,
        audioOnly: Boolean,
        processId: String,
        onProgress: (Float, String) -> Unit
    ): File {
        cancelRequested = false
        return withAttempts(url) { a ->
            val dir = File(ctx.cacheDir, "dl_" + System.currentTimeMillis())
            dir.mkdirs()
            try {
                val req = YoutubeDLRequest(url)
                common(req, a)
                req.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
                req.addOption("-N", "4")
                if (audioOnly) {
                    req.addOption("-f", "ba/b")
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "0")
                } else {
                    val h = heightLimit
                    req.addOption(
                        "-f",
                        "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b"
                    )
                    req.addOption("--merge-output-format", "mp4")
                }
                YoutubeDL.getInstance().execute(req, processId) { progress, _, line ->
                    onProgress(progress / 100f, line ?: "")
                }
                val result = dir.listFiles()
                    ?.filter { f ->
                        f.isFile && f.length() > 0 &&
                            listOf("mp4", "mkv", "webm", "mp3", "m4a", "mov", "ogg", "opus", "aac")
                                .contains(f.extension.lowercase())
                    }
                    ?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException("فایل خروجی ساخته نشد (empty).")
                result
            } catch (t: Throwable) {
                dir.deleteRecursively()
                throw t
            }
        }
    }

    fun cancel(processId: String) {
        cancelRequested = true
        try {
            val ydl = YoutubeDL.getInstance()
            val m = ydl.javaClass.methods.firstOrNull { it.name == "destroyProcessById" && it.parameterTypes.size == 1 }
            m?.invoke(ydl, processId)
        } catch (_: Throwable) {
        }
    }

    /** Facebook "share" links and fb.watch short links are redirects; yt-dlp works better with the real address. */
    fun resolveRedirect(url: String): String {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", Net.UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val code = c.responseCode
            val finalUrl = c.url.toString()
            c.disconnect()
            val bad = finalUrl.contains("login") || finalUrl.contains("checkpoint")
            if (code in 200..399 && !bad) finalUrl else url
        } catch (_: Exception) {
            url
        }
    }

    /** Short, actionable Persian message plus the technical last line, so problems can be diagnosed. */
    fun friendlyError(raw: String): String {
        val m = raw.lowercase()
        val last = (raw.lines().lastOrNull { it.isNotBlank() } ?: raw).trim()
        val detail = "\n[" + last.take(150) + "]"
        val text = when {
            m.contains("sign in to confirm") || m.contains("not a bot") ->
                "یوتیوب این اتصال را ربات تشخیص داد (ضدربات). با VPN دیگر یا شبکهٔ دیگر امتحان کنید، یا چند دقیقه بعد دوباره بزنید."
            m.contains("private video") || m.contains("video is private") ->
                "این ویدیو خصوصی است و بدون ورود به حساب قابل دانلود نیست."
            m.contains("unsupported url") ->
                "این لینک پشتیبانی نمی‌شود. لینک مستقیم ویدیو یا ریل را وارد کنید."
            m.contains("no video formats") || m.contains("no video could be found") || m.contains("there is no video") ->
                "در این لینک ویدیویی پیدا نشد (ممکن است پست فقط عکس/متن باشد یا نیاز به ورود به حساب داشته باشد)."
            m.contains("video unavailable") || m.contains("has been removed") || m.contains("not available in your country") ->
                "این ویدیو در دسترس نیست (حذف شده یا محدودیت منطقه‌ای دارد)."
            m.contains("429") || m.contains("too many requests") ->
                "درخواست‌ها زیاد بود؛ چند دقیقه صبر کنید و دوباره امتحان کنید."
            m.contains("login") || m.contains("log in") || m.contains("cookies") || m.contains("rate-limit") ||
                m.contains("restricted") ->
                "این محتوا ورود به حساب می‌خواهد؛ فقط پست‌های کاملاً عمومی قابل دانلود هستند."
            m.contains("timed out") || m.contains("urlopen error") || m.contains("network is unreachable") ||
                m.contains("name or service not known") || m.contains("unable to download webpage") ->
                "اتصال اینترنت یا دسترسی به سایت برقرار نشد (VPN را بررسی کنید)."
            m.contains("cannot parse data") || m.contains("unable to extract") ->
                "ساختار سایت تغییر کرده؛ دکمهٔ به‌روزرسانی موتور (بالا) را بزنید و دوباره امتحان کنید."
            else -> "دانلود ناموفق بود."
        }
        return text + detail
    }
}
