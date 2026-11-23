package com.aydin.sunostudio

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SunoInfo(
    val id: String,
    val title: String,
    val artist: String,
    val image: String,
    val candidates: List<String>
)

/**
 * Suno downloader.
 *
 * Why the old version failed: it found the public audio addresses in the page but then threw them away
 * and only asked an *authenticated* Suno API for an M4A file. Without a logged-in session that call
 * always fails, so nothing was ever saved. A public song page already publishes its audio file
 * (og:audio meta tag / the public cdn1.suno.ai/<song-id>.mp3 file that the web player itself plays),
 * so we use those, validate the bytes, and save the MP3 directly.
 */
object Suno {
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val CDN_IN_PAGE = Regex("https:(?:\\\\/|/){2}cdn\\d*\\.suno\\.ai(?:\\\\/|/)[^\"'\\\\\\s<>]+?\\.(?:mp3|m4a|wav)")

    private fun hostOk(host: String): Boolean {
        val h = host.lowercase()
        return h == "suno.com" || h.endsWith(".suno.com") || h == "suno.ai" || h.endsWith(".suno.ai")
    }

    private fun mediaHostOk(raw: String): Boolean = try {
        val u = URL(raw)
        val h = u.host.lowercase()
        u.protocol == "https" && (hostOk(h) || h.endsWith(".cloudfront.net"))
    } catch (_: Exception) {
        false
    }

    /** Returns a clean https page URL, or null when the text is not a Suno link. */
    fun normalizeUrl(raw: String): String? {
        val text = Net.extractUrl(raw)
        if (text.isBlank()) return null
        val url = try {
            URL(Net.withScheme(text))
        } catch (_: Exception) {
            return null
        }
        if (!hostOk(url.host)) return null
        val q = if (url.query != null) "?" + url.query else ""
        return "https://" + url.host + url.path + q
    }

    private fun meta(doc: Document, key: String): String =
        doc.select("meta[property=$key], meta[name=$key]").firstOrNull()?.attr("content")?.trim() ?: ""

    fun resolve(page: String): SunoInfo {
        var id = UUID.find(page.substringBefore('?'))?.value?.lowercase() ?: ""
        var title = ""
        var artist = ""
        var image = ""
        val found = LinkedHashSet<String>()
        var pageError = ""

        try {
            val res = Jsoup.connect(page)
                .userAgent(Net.UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .referrer("https://www.google.com/")
                .timeout(20000)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .maxBodySize(0)
                .execute()
            if (id.isEmpty()) {
                id = UUID.find(res.url().toString().substringBefore('?'))?.value?.lowercase() ?: ""
            }
            if (res.statusCode() in 200..299) {
                val doc = res.parse()
                val body = res.body()

                for (key in listOf("og:audio", "og:audio:secure_url", "twitter:player:stream")) {
                    val v = meta(doc, key)
                    if (v.startsWith("http")) found.add(v)
                }
                for (m in CDN_IN_PAGE.findAll(body)) {
                    found.add(m.value.replace("\\/", "/"))
                }

                val ogTitle = meta(doc, "og:title").ifBlank { doc.title() }
                var t = ogTitle.replace(Regex("\\s*[|\\-–—]\\s*Suno.*$", RegexOption.IGNORE_CASE), "").trim()
                if (t.contains(" by @")) {
                    artist = t.substringAfter(" by @").trim()
                    t = t.substringBefore(" by @").trim()
                }
                title = t
                if (artist.isBlank()) artist = meta(doc, "author").trimStart('@')
                image = meta(doc, "og:image")
            } else {
                pageError = "HTTP " + res.statusCode()
            }
        } catch (e: Exception) {
            pageError = e.message ?: e.javaClass.simpleName
        }

        val candidates = ArrayList<String>()
        // Page-provided addresses first (only those that belong to this song when we know its id).
        for (c in found) {
            if (!mediaHostOk(c)) continue
            if (id.isNotEmpty() && !c.lowercase().contains(id)) continue
            candidates.add(c)
        }
        // Public CDN file that the Suno web player itself streams for shared songs.
        if (id.isNotEmpty()) {
            val cdn = "https://cdn1.suno.ai/$id.mp3"
            if (!candidates.contains(cdn)) candidates.add(cdn)
        }

        if (candidates.isEmpty()) {
            val why = if (pageError.isNotBlank()) " ($pageError)" else ""
            throw IllegalStateException(
                "شناسهٔ آهنگ از لینک پیدا نشد$why. لینک صفحهٔ آهنگ را به شکل suno.com/song/… وارد کنید."
            )
        }
        val safeTitle = title.ifBlank { "Suno-" + id.take(8) }
        return SunoInfo(id, safeTitle, artist, image, candidates)
    }

    /** Blocking. Returns (uri, file name). Throws with a Persian message on failure. */
    fun download(ctx: Context, info: SunoInfo, onProgress: (Float, String) -> Unit): Pair<Uri, String> {
        val tmp = File(ctx.cacheDir, "suno_" + System.currentTimeMillis() + ".part")
        val errors = ArrayList<String>()
        try {
            for (cand in info.candidates) {
                onProgress(0f, "در حال دریافت فایل صوتی…")
                val err = fetch(cand, tmp) { p -> onProgress(p, "در حال دریافت فایل صوتی…") }
                if (err != null) {
                    errors.add(err)
                    continue
                }
                val ext = sniffExtension(tmp)
                if (ext == null) {
                    errors.add("پاسخ سرور فایل صوتی نبود")
                    continue
                }
                if (tmp.length() < 50_000L) {
                    errors.add("فایل دریافتی بسیار کوچک بود")
                    continue
                }
                onProgress(1f, "در حال ذخیره در حافظهٔ گوشی…")
                val name = Store.safeName(info.title, "Suno-" + info.id.take(8)) + "." + ext
                val uri = Store.publish(ctx, tmp, name, Store.Kind.AUDIO)
                return Pair(uri, name)
            }
            val detail = errors.distinct().joinToString("، ")
            throw IllegalStateException(
                "دانلود انجام نشد: $detail.\nمعمولاً یعنی آهنگ عمومی نیست یا اینترنت/سرور Suno مشکل دارد؛ " +
                    "مطمئن شوید آهنگ در Suno روی حالت Public است."
            )
        } finally {
            tmp.delete()
        }
    }

    private fun fetch(urlStr: String, dest: File, onProgress: (Float) -> Unit): String? {
        val c: HttpURLConnection = try {
            URL(urlStr).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return e.message ?: "آدرس نامعتبر"
        }
        try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", Net.UA)
            c.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Referer", "https://suno.com/")
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            if (code !in 200..299) return "HTTP $code"
            val type = (c.contentType ?: "").lowercase()
            if (type.startsWith("text/") || type.contains("json")) return "نوع پاسخ نامعتبر ($type)"
            val total = c.contentLengthLong
            var done = 0L
            var last = 0f
            dest.outputStream().use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = done.toFloat() / total
                            if (p - last >= 0.01f) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                    out.flush()
                }
            }
            if (total > 0 && done != total) return "دانلود ناقص ماند"
            return null
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            c.disconnect()
        }
    }

    /** Looks at the first bytes so we never save an HTML error page as "music". */
    private fun sniffExtension(f: File): String? {
        val h = ByteArray(12)
        val n = try {
            f.inputStream().use { it.read(h) }
        } catch (_: Exception) {
            -1
        }
        if (n < 12) return null
        fun s(from: Int, len: Int) = String(h, from, len, Charsets.ISO_8859_1)
        val b0 = h[0].toInt() and 0xFF
        val b1 = h[1].toInt() and 0xFF
        return when {
            s(0, 3) == "ID3" -> "mp3"
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> "mp3"
            s(4, 4) == "ftyp" -> "m4a"
            s(0, 4) == "RIFF" && s(8, 4) == "WAVE" -> "wav"
            s(0, 4) == "OggS" -> "ogg"
            s(0, 4) == "fLaC" -> "flac"
            else -> null
        }
    }
}
