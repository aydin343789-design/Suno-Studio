package com.aydin.sunostudio

import android.app.Activity
import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class SunoInfo(
    val id: String,
    val title: String,
    val artist: String,
    val image: String,
    val candidates: List<String>,
    val page: String,
    val web: SunoWeb?
)

/**
 * Suno downloader with several independent strategies (each is tried until one produces real audio):
 *  1. Hidden WebView reads the page (a real browser is not blocked) -> direct download with the
 *     browser's User-Agent and cookies.
 *  2. Same WebView downloads the bytes from inside the page.
 *  3. The bundled yt-dlp engine (it has its own maintained Suno extractor).
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

    private fun splitTitle(ogTitle: String): Pair<String, String> {
        var t = ogTitle.replace(Regex("\\s*[|\\-–—]\\s*Suno.*$", RegexOption.IGNORE_CASE), "").trim()
        var artist = ""
        if (t.contains(" by @")) {
            artist = t.substringAfter(" by @").trim()
            t = t.substringBefore(" by @").trim()
        }
        return Pair(t, artist)
    }

    fun resolve(act: Activity, page: String): SunoInfo {
        var id = UUID.find(page.substringBefore('?'))?.value?.lowercase() ?: ""
        var title = ""
        var artist = ""
        var image = ""
        val found = LinkedHashSet<String>()
        var pageError = ""

        // 1) real browser
        val web = SunoWeb(act)
        val wd = try {
            web.load(page)
        } catch (_: Throwable) {
            null
        }
        if (wd != null) {
            if (id.isEmpty()) id = UUID.find(wd.href.substringBefore('?'))?.value?.lowercase() ?: ""
            val sp = splitTitle(wd.title)
            title = sp.first
            artist = sp.second
            image = wd.image
            found.addAll(wd.urls)
        }

        // 2) plain HTTP page read (works when the WebView could not read it)
        if (title.isBlank() || found.isEmpty()) {
            try {
                val res = Jsoup.connect(page)
                    .userAgent(web.userAgent)
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
                    for (key in listOf("og:audio", "og:audio:secure_url", "twitter:player:stream")) {
                        val v = meta(doc, key)
                        if (v.startsWith("http")) found.add(v)
                    }
                    for (m in CDN_IN_PAGE.findAll(res.body())) found.add(m.value.replace("\\/", "/"))
                    if (title.isBlank()) {
                        val sp = splitTitle(meta(doc, "og:title").ifBlank { doc.title() })
                        title = sp.first
                        artist = sp.second.ifBlank { meta(doc, "author").trimStart('@') }
                    }
                    if (image.isBlank()) image = meta(doc, "og:image")
                } else {
                    pageError = "HTTP " + res.statusCode()
                }
            } catch (e: Exception) {
                pageError = e.message ?: e.javaClass.simpleName
            }
        }

        val candidates = ArrayList<String>()
        for (c in found) {
            if (!mediaHostOk(c)) continue
            if (id.isNotEmpty() && !c.lowercase().contains(id)) continue
            if (!candidates.contains(c)) candidates.add(c)
        }
        // Do not invent a CDN URL from the song UUID. Suno media URLs are signed/managed
        // and a guessed cdn1.suno.ai/{id}.mp3 commonly returns HTTP 403.
        if (id.isBlank()) {
            web.close()
            val why = if (pageError.isNotBlank()) " ($pageError)" else ""
            throw IllegalStateException(
                "شناسهٔ آهنگ از لینک پیدا نشد$why. لینک صفحهٔ آهنگ را به شکل suno.com/song/… وارد کنید."
            )
        }
        val canonical = if (id.isNotEmpty()) "https://suno.com/song/$id" else page
        return SunoInfo(id, title.ifBlank { "Suno-" + id.take(8) }, artist, image, candidates, canonical, web)
    }

    /** Blocking. progress < 0 means "indeterminate". Returns (uri, file name). */
    fun download(ctx: Context, info: SunoInfo, onProgress: (Float, String) -> Unit): Pair<Uri, String> {
        val tmp = File(ctx.cacheDir, "suno_" + System.currentTimeMillis() + ".part")
        val errors = ArrayList<String>()
        val web = info.web
        try {
            for (cand in info.candidates) {
                // A) direct download with the browser's identity
                onProgress(0f, "در حال دریافت فایل صوتی…")
                val cookies = web?.cookieHeader(cand) ?: ""
                val ua = web?.userAgent ?: Net.UA
                val errA = fetch(cand, tmp, ua, cookies) { p -> onProgress(p, "در حال دریافت فایل صوتی…") }
                if (errA == null) {
                    val ok = publishIfAudio(ctx, info, tmp, errors)
                    if (ok != null) return ok
                } else {
                    errors.add("مستقیم: $errA")
                }

                // B) download from inside the page
                if (web != null) {
                    onProgress(0f, "تلاش با مرورگر داخلی…")
                    val errB = web.fetchViaPage(cand, tmp) { p -> onProgress(p, "تلاش با مرورگر داخلی…") }
                    if (errB == null) {
                        val ok = publishIfAudio(ctx, info, tmp, errors)
                        if (ok != null) return ok
                    } else {
                        errors.add("مرورگر: " + errB.take(60))
                    }
                }
            }

            // C) Third-party downloader fallback. Its own server fetches the public Suno
            // track; the app does not try to bypass Suno CDN access controls.
            onProgress(-1f, "تلاش از مسیر جایگزین Suno…")
            try {
                val proxyError = downloadViaService(info, tmp) { p ->
                    onProgress(p, "دریافت از سرویس جایگزین Suno…")
                }
                if (proxyError == null) {
                    val ok = publishIfAudio(ctx, info, tmp, errors)
                    if (ok != null) return ok
                } else {
                    errors.add("سرویس جایگزین: $proxyError")
                }
            } catch (e: Throwable) {
                errors.add("سرویس جایگزین: " + (e.message ?: e.javaClass.simpleName).take(120))
            }

            // D) yt-dlp
            if (Engine.state == Engine.State.READY) {
                onProgress(-1f, "تلاش با موتور دانلود…")
                try {
                    val f = Media.download(ctx, info.page, 0, true, "suno-" + System.currentTimeMillis()) { fr, _ ->
                        if (fr >= 0f) onProgress(fr, "دریافت با موتور دانلود…")
                    }
                    try {
                        val name = Store.safeName(info.title, "Suno-" + info.id.take(8)) + "." + f.extension.lowercase()
                        val uri = Store.publish(ctx, f, name, Store.Kind.AUDIO)
                        return Pair(uri, name)
                    } finally {
                        f.parentFile?.deleteRecursively()
                    }
                } catch (e: Throwable) {
                    errors.add("yt-dlp: " + (e.message ?: "").lines().lastOrNull { it.isNotBlank() }.orEmpty().take(110))
                }
            } else {
                errors.add("موتور دانلود آماده نبود")
            }

            throw IllegalStateException(
                "دانلود انجام نشد.\n" + errors.distinct().joinToString("\n") +
                    "\nمطمئن شوید آهنگ در Suno روی حالت Public است و اینترنت/VPN وصل است."
            )
        } finally {
            web?.close()
            tmp.delete()
        }
    }


    /**
     * Uses the same public API endpoints referenced by the supplied Suno downloader page:
     * GET /api/info?url=... then GET /api/download?url=...&format=mp3&title=...
     * The remote service performs the Suno-side fetch. Only public song-page URLs are sent.
     */
    private fun downloadViaService(info: SunoInfo, dest: File, onProgress: (Float) -> Unit): String? {
        val base = "https://sunodownloader.ai"
        val encodedPage = URLEncoder.encode(info.page, "UTF-8")
        val infoConn = (URL("$base/api/info?url=$encodedPage").openConnection() as HttpURLConnection)
        try {
            infoConn.instanceFollowRedirects = true
            infoConn.connectTimeout = 20000
            infoConn.readTimeout = 30000
            infoConn.setRequestProperty("User-Agent", Net.UA)
            infoConn.setRequestProperty("Accept", "application/json")
            infoConn.setRequestProperty("Referer", "$base/fa/")
            val status = infoConn.responseCode
            if (status !in 200..299) return "بررسی آهنگ: HTTP $status"
            val body = infoConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(256_000) }
            val json = try { JSONObject(body) } catch (_: Exception) {
                return "پاسخ اطلاعات آهنگ قابل‌خواندن نبود"
            }
            if (!json.optBoolean("ok", false)) {
                return json.optString("error").ifBlank { "سرویس آهنگ را تأیید نکرد" }.take(140)
            }
        } finally {
            infoConn.disconnect()
        }

        val title = info.title.ifBlank { "suno-${info.id.take(8)}" }
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val downloadUrl = "$base/api/download?url=$encodedPage&format=mp3&title=$encodedTitle"
        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 180000
            conn.setRequestProperty("User-Agent", Net.UA)
            conn.setRequestProperty("Accept", "audio/mpeg,audio/*,application/octet-stream,*/*;q=0.8")
            conn.setRequestProperty("Referer", "$base/fa/")
            val status = conn.responseCode
            if (status !in 200..299) {
                val detail = try { conn.errorStream?.bufferedReader()?.use { it.readText().take(180) } } catch (_: Exception) { null }
                return "دانلود سرویس: HTTP $status" + if (detail.isNullOrBlank()) "" else " ($detail)"
            }
            val type = (conn.contentType ?: "").lowercase()
            if (type.contains("text/html") || type.contains("application/json")) {
                val detail = try { conn.inputStream.bufferedReader().use { it.readText().take(180) } } catch (_: Exception) { "" }
                return "سرویس به‌جای فایل صوتی پاسخ متنی داد" + if (detail.isBlank()) "" else ": $detail"
            }
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    output.flush()
                }
            }
            if (done < 50_000L) return "فایل دریافتی از سرویس خیلی کوچک بود ($done bytes)"
            if (sniffExtension(dest) == null) return "خروجی سرویس فایل صوتی معتبر نبود (نوع: $type)"
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun publishIfAudio(ctx: Context, info: SunoInfo, tmp: File, errors: MutableList<String>): Pair<Uri, String>? {
        val ext = sniffExtension(tmp)
        if (ext == null) {
            errors.add("پاسخ سرور فایل صوتی نبود")
            return null
        }
        if (tmp.length() < 50_000L) {
            errors.add("فایل دریافتی بسیار کوچک بود")
            return null
        }
        val name = Store.safeName(info.title, "Suno-" + info.id.take(8)) + "." + ext
        val uri = Store.publish(ctx, tmp, name, Store.Kind.AUDIO)
        return Pair(uri, name)
    }

    private fun fetch(urlStr: String, dest: File, ua: String, cookies: String, onProgress: (Float) -> Unit): String? {
        val c: HttpURLConnection = try {
            URL(urlStr).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return e.message ?: "آدرس نامعتبر"
        }
        try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Referer", "https://suno.com/")
            c.setRequestProperty("Origin", "https://suno.com")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (cookies.isNotBlank()) c.setRequestProperty("Cookie", cookies)
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
