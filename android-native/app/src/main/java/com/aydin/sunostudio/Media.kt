package com.aydin.sunostudio

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

class MediaInfo(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnail: String
)

/** Thin wrapper around the bundled yt-dlp engine (YouTube, Instagram, Facebook, ...). */
object Media {
    private fun isYoutube(url: String): Boolean {
        val h = Net.hostOf(url)
        return h.contains("youtube") || h.contains("youtu.be")
    }

    private fun common(req: YoutubeDLRequest, url: String, withEjs: Boolean) {
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--socket-timeout", "25")
        req.addOption("--retries", "5")
        req.addOption("--fragment-retries", "5")
        if (withEjs && isYoutube(url)) {
            // yt-dlp needs the "EJS" challenge solver for current YouTube players.
            req.addOption("--remote-components", "ejs:github")
        }
    }

    private fun isUnknownOption(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        return m.contains("no such option") || m.contains("unrecognized arguments")
    }

    fun fetchInfo(url: String): MediaInfo {
        var lastError: Throwable? = null
        for (withEjs in listOf(true, false)) {
            try {
                val req = YoutubeDLRequest(url)
                common(req, url, withEjs)
                req.addOption("--dump-single-json")
                req.addOption("--skip-download")
                val resp = YoutubeDL.getInstance().execute(req)
                val out = (resp.out ?: "").trim()
                val start = out.indexOf('{')
                if (start < 0) throw IllegalStateException("پاسخ اطلاعات ویدیو خالی بود.")
                var json = JSONObject(out.substring(start))
                val entries = json.optJSONArray("entries")
                if (entries != null && entries.length() > 0) {
                    json = entries.optJSONObject(0) ?: json
                }
                val uploader = json.optString("uploader").ifBlank {
                    json.optString("channel").ifBlank { json.optString("uploader_id") }
                }
                return MediaInfo(
                    title = json.optString("title").ifBlank { "video" },
                    uploader = uploader,
                    durationSec = json.optDouble("duration", 0.0).toLong(),
                    thumbnail = json.optString("thumbnail")
                )
            } catch (t: Throwable) {
                lastError = t
                if (!isUnknownOption(t)) break
            }
        }
        throw lastError ?: IllegalStateException("خطای نامشخص")
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
        val dir = File(ctx.cacheDir, "dl_" + System.currentTimeMillis())
        dir.mkdirs()
        var lastError: Throwable? = null
        for (withEjs in listOf(true, false)) {
            try {
                val req = YoutubeDLRequest(url)
                common(req, url, withEjs)
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
                if (result == null) {
                    dir.deleteRecursively()
                    throw IllegalStateException("فایل خروجی ساخته نشد.")
                }
                return result
            } catch (t: Throwable) {
                lastError = t
                if (!isUnknownOption(t)) break
            }
        }
        dir.deleteRecursively()
        throw lastError ?: IllegalStateException("خطای نامشخص")
    }

    fun cancel(processId: String) {
        try {
            val ydl = YoutubeDL.getInstance()
            val m = ydl.javaClass.methods.firstOrNull { it.name == "destroyProcessById" && it.parameterTypes.size == 1 }
            m?.invoke(ydl, processId)
        } catch (_: Throwable) {
        }
    }

    /** Turns raw yt-dlp errors into short, actionable Persian messages. */
    fun friendlyError(raw: String): String {
        val m = raw.lowercase()
        return when {
            m.contains("sign in to confirm") || m.contains("not a bot") ->
                "یوتیوب برای این ویدیو تأیید هویت (ضدربات) خواست. چند دقیقه بعد دوباره امتحان کنید یا دکمهٔ به‌روزرسانی موتور را بزنید."
            m.contains("private video") || m.contains("this video is private") || m.contains("private") ->
                "این محتوا خصوصی است و بدون ورود به حساب قابل دانلود نیست."
            m.contains("login required") || m.contains("log in") || m.contains("login") ||
                m.contains("cookies") || m.contains("rate-limit") || m.contains("restricted video") ->
                "این پلتفرم برای این محتوا ورود به حساب یا محدودیت دسترسی دارد؛ فقط پست‌های عمومی قابل دانلود هستند. چند دقیقه بعد هم دوباره امتحان کنید."
            m.contains("unsupported url") ->
                "این لینک پشتیبانی نمی‌شود. لینک مستقیم ویدیو یا ریل را وارد کنید."
            m.contains("no video") || m.contains("no video could be found") ->
                "در این لینک ویدیویی پیدا نشد (ممکن است فقط عکس باشد)."
            m.contains("video unavailable") || m.contains("not available") || m.contains("has been removed") ->
                "این ویدیو در دسترس نیست (حذف شده یا محدودیت منطقه‌ای دارد)."
            m.contains("429") || m.contains("too many requests") ->
                "درخواست‌ها زیاد بود؛ چند دقیقه صبر کنید و دوباره امتحان کنید."
            m.contains("timed out") || m.contains("urlopen error") || m.contains("unable to download") ||
                m.contains("network is unreachable") || m.contains("name or service not known") ->
                "اتصال اینترنت برقرار نیست یا ناپایدار است."
            else -> {
                val last = raw.lines().lastOrNull { it.isNotBlank() } ?: raw
                "دانلود ناموفق بود.\n" + last.take(170)
            }
        }
    }
}
