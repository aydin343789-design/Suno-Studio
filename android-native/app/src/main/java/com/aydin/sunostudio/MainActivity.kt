package com.aydin.sunostudio

import androidx.activity.ComponentActivity
import android.content.ContentValues
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.activity.result.contract.ActivityResultContracts
import org.jsoup.Jsoup
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var urlBox: EditText
    private lateinit var status: TextView
    private lateinit var chosen: TextView
    private lateinit var busyBar: ProgressBar
    private lateinit var extractionInfo: TextView
    private var extractionWebView: WebView? = null
    private var extractionAttempt = 0
    private var extractionPageUrl = ""
    private var audioUri: Uri? = null
    private var working = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            chosen.text = "فایل انتخاب‌شده: " + displayName(uri)
            message("فایل برای تبدیل آفلاین آماده است.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(17, 18, 27))
        }
        val scroll = ScrollView(this)
        scroll.addView(page)
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)).apply {
                gravity = Gravity.CENTER
                bottomMargin = dp(6)
            }
            contentDescription = "Suno Studio"
        }
        page.addView(brand)
        page.addView(text("SUNO STUDIO", 25f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        page.addView(text("دانلودر و مبدل صوتی مستقل از سرور", 14f, Color.LTGRAY, false).apply {
            gravity = Gravity.CENTER
        }.pad(0, 6, 0, 24))

        page.addView(text("۱. دانلود از لینک عمومی Suno", 19f, Color.WHITE, true).pad(0, 8, 0, 12))
        page.addView(text("لینک صفحهٔ آهنگ یا لینک اشتراک‌گذاری:", 14f, Color.LTGRAY, false))
        urlBox = EditText(this).apply {
            hint = "https://suno.com/song/..."
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(36, 38, 53))
        }
        page.addView(urlBox, params().apply { topMargin = dp(10); bottomMargin = dp(10) })
        page.addView(button("بارگذاری، استخراج اطلاعات و دانلود", true) { download() }, params())
        extractionInfo = text("ابتدا صفحهٔ آهنگ بارگذاری و اطلاعات و منابع صوتی عمومی آن بررسی می‌شود.", 13f, Color.rgb(190, 178, 255), false).pad(0, 12, 0, 12)
        page.addView(extractionInfo)
        extractionWebView = WebView(this).apply {
            visibility = View.INVISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (isSunoHost(URL(url).host)) {
                        extractionAttempt = 0
                        schedulePageExtraction(view)
                    }
                }
            }
        }
        page.addView(extractionWebView, LinearLayout.LayoutParams(1, 1))

        page.addView(text("۲. تبدیل فایل‌های داخل گوشی", 19f, Color.WHITE, true).pad(0, 8, 0, 12))
        page.addView(button("انتخاب فایل صوتی", false) { if (!working) picker.launch(arrayOf("audio/*")) }, params())
        chosen = text("هنوز فایلی انتخاب نشده است.", 13f, Color.GRAY, false).pad(0, 12, 0, 12)
        page.addView(chosen)
        page.addView(button("تبدیل به WAV و ذخیره در گوشی", true) { convert() }, params())
        busyBar = ProgressBar(this).apply { visibility = View.GONE }
        page.addView(busyBar, params())
        status = text("آماده به کار", 14f, Color.rgb(190, 178, 255), true).pad(0, 12, 0, 0)
        page.addView(status)
        setContentView(scroll)
    }

    private fun download() {
        if (working) return
        val raw = urlBox.text.toString().trim()
        val pageUrl = try { URL(raw) } catch (_: Exception) { message("لطفاً لینک معتبر Suno وارد کن."); return }
        if (pageUrl.protocol != "https" || !isSunoHost(pageUrl.host) || !pageUrl.path.startsWith("/song/")) {
            message("لطفاً لینک HTTPS صفحهٔ آهنگ از دامنهٔ رسمی suno.com وارد کن."); return
        }
        extractionPageUrl = raw
        extractionAttempt = 0
        extractionInfo.text = "مرحلهٔ ۱ از ۲: صفحهٔ آهنگ در مرورگر داخلی بارگذاری می‌شود…"
        setWorking(true, "در حال بارگذاری صفحهٔ عمومی آهنگ Suno…")
        val web = extractionWebView ?: run { setWorking(false, "مرورگر داخلی آماده نیست."); return }
        web.stopLoading()
        web.loadUrl(raw)
    }

    private fun schedulePageExtraction(view: WebView) {
        if (!working || extractionAttempt >= 8) return
        extractionAttempt++
        val script = """
            (function() {
              const meta = (sel) => document.querySelector(sel)?.content || '';
              const title = meta('meta[property="og:title"]') || document.title || '';
              const description = meta('meta[property="og:description"]') || meta('meta[name="description"]');
              const image = meta('meta[property="og:image"]') || meta('meta[name="twitter:image"]');
              const artist = meta('meta[name="author"]') || meta('meta[property="music:musician"]');
              const candidates = [];
              const add = (u, kind) => {
                if (!u || typeof u !== 'string') return;
                try {
                  const x = new URL(u, location.href);
                  if (x.protocol !== 'https:' || /^(blob:|data:)/i.test(u)) return;
                  if (/\.m3u8($|\?)/i.test(x.href)) return;
                  if (/\.(mp3|m4a|aac|wav|ogg|opus|flac)(\?|$)/i.test(x.href) || /audio/i.test(kind || '')) {
                    if (!candidates.some(v => v.url === x.href)) candidates.push({url:x.href,kind:kind||''});
                  }
                } catch(e) {}
              };
              document.querySelectorAll('audio, audio source, video, video source').forEach(e => {
                add(e.currentSrc || e.src || e.getAttribute('src'), e.type || e.tagName);
              });
              // Inspect structured public page state for canonical audio URLs; never synthesize CDN URLs.
              const scanPublicState = (value, key, depth) => {
                if (depth > 7 || value == null) return;
                if (typeof value === 'string') {
                  if (/(audio|stream).*(url|src)|(url|src).*(audio|stream)/i.test(key || '')) add(value, 'public-state:' + key);
                  return;
                }
                if (Array.isArray(value)) { value.forEach(v => scanPublicState(v, key, depth + 1)); return; }
                if (typeof value === 'object') Object.keys(value).forEach(k => scanPublicState(value[k], k, depth + 1));
              };
              document.querySelectorAll('script[type="application/json"],script#__NEXT_DATA__').forEach(el => {
                try { scanPublicState(JSON.parse(el.textContent || ''), 'page-state', 0); } catch(e) {}
              });
              document.querySelectorAll('script:not([src])').forEach(el => {
                const txt = el.textContent || '';
                const re = /["'](?:audio_url|audioUrl|stream_audio_url|streamAudioUrl|audio_src|audioSrc)["']\s*:\s*["']([^"']+)["']/gi;
                let m;
                while ((m = re.exec(txt)) !== null) {
                  try { add(m[1].replace(/\\u0026/g, '&').replace(/\\\//g, '/'), 'public-state:embedded-audio-url'); } catch(e) {}
                }
              });
              ['og:audio','og:audio:secure_url','twitter:player:stream'].forEach(k => {
                const e = document.querySelector('meta[property="'+k+'"],meta[name="'+k+'"]');
                if (e) add(e.content, k);
              });
              performance.getEntriesByType('resource').forEach(e => add(e.name, e.initiatorType));
              return JSON.stringify({title,description,image,artist,url:location.href,candidates});
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { encoded ->
            if (!working) return@evaluateJavascript
            try {
                val jsonText = if (encoded == null || encoded == "null") "" else org.json.JSONTokener(encoded).nextValue() as? String ?: ""
                if (jsonText.isNotBlank()) {
                    val parsed = JSONObject(jsonText)
                    val found = parsed.optJSONArray("candidates")?.length() ?: 0
                    if (found > 0 || extractionAttempt >= 8) handlePageExtraction(jsonText)
                    else {
                        val title = parsed.optString("title").ifBlank { "در حال شناسایی آهنگ…" }
                        extractionInfo.text = "صفحه بارگذاری شد؛ در حال استخراج اطلاعات و منابع صوتی…\\nعنوان: $title"
                        retryExtraction(view)
                    }
                } else retryExtraction(view)
            } catch (_: Exception) { retryExtraction(view) }
        }
    }

    private fun retryExtraction(view: WebView) {
        if (extractionAttempt < 8 && working) ui.postDelayed({ schedulePageExtraction(view) }, 2500)
        else if (working) {
            extractionInfo.text = "صفحه بارگذاری شد، اما منبع صوتی عمومی قابل‌شناسایی پیدا نشد."
            setWorking(false, "استخراج انجام شد؛ صفحه منبع مستقیم و عمومی صوتی ارائه نکرد. از دانلود رسمی Suno استفاده کن.")
        }
    }

    private fun handlePageExtraction(rawJson: String) {
        val data = JSONObject(rawJson)
        val title = data.optString("title").ifBlank { "عنوان در دسترس نیست" }
        val artist = data.optString("artist")
        val description = data.optString("description")
        val image = data.optString("image")
        val candidates = data.optJSONArray("candidates")
        val summary = buildString {
            append("استخراج اطلاعات صفحه انجام شد\nعنوان: ").append(title)
            if (artist.isNotBlank()) append("\nسازنده: ").append(artist)
            if (description.isNotBlank()) append("\nتوضیحات: ").append(description.take(220))
            if (image.isNotBlank()) append("\nتصویر کاور: شناسایی شد")
            append("\nمنابع صوتی شناسایی‌شده: ").append(candidates?.length() ?: 0)
            if (candidates != null) {
                for (i in 0 until minOf(candidates.length(), 3)) {
                    val item = candidates.optJSONObject(i) ?: continue
                    val u = try { URL(item.optString("url")) } catch (_: Exception) { null }
                    if (u != null) append("\nنامزد ").append(i + 1).append(": ").append(u.host).append(u.path.takeLast(70))
                        .append(" [").append(item.optString("kind")).append("]")
                }
            }
        }
        extractionInfo.text = summary
        if (candidates == null || candidates.length() == 0) {
            setWorking(false, "اطلاعات صفحه استخراج شد، اما فایل صوتی مستقیم و عمومی در صفحه پیدا نشد؛ از دانلود رسمی Suno استفاده کن.")
            return
        }
        setWorking(true, "مرحلهٔ ۲ از ۲: منبع صوتی شناسایی شد؛ دریافت و بررسی فایل…")
        val safeTitle = safeName(title)
        thread {
            var lastError = "منبع صوتی قابل دریافت نبود."
            var saved = false
            for (i in 0 until candidates.length()) {
                val item = candidates.optJSONObject(i) ?: continue
                val candidate = item.optString("url")
                try {
                    if (!isSunoAudio(candidate)) continue
                    saveRemote(candidate, safeTitle + ".mp3", extractionPageUrl)
                    saved = true
                    break
                } catch (e: Exception) { lastError = e.message ?: lastError }
            }
            if (saved) ui.post { setWorking(false, "پاسخ صوتی دریافت و ذخیره شد: $safeTitle.mp3 در Music/Suno Studio (حتی اگر کوتاه یا نمونه باشد).") }
            else ui.post { setWorking(false, "اطلاعات آهنگ استخراج شد، اما دریافت منبع صوتی موفق نبود: $lastError") }
        }
    }

    private fun saveRemote(link: String, name: String, referer: String) {
        require(isSunoAudio(link)) { "دامنهٔ فایل صوتی مجاز نیست." }
        val uri = outputUri(name, "audio/mpeg")
        try {
            val c = URL(link).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 45000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept", "audio/avif,audio/webm,audio/apng,audio/*,*/*;q=0.8")
            c.setRequestProperty("Referer", referer)
            c.setRequestProperty("Origin", "https://suno.com")
            c.setRequestProperty("Sec-Fetch-Dest", "audio")
            c.setRequestProperty("Sec-Fetch-Mode", "cors")
            c.setRequestProperty("Accept-Encoding", "identity")
            val browserCookie = CookieManager.getInstance().getCookie(link)
            if (!browserCookie.isNullOrBlank()) c.setRequestProperty("Cookie", browserCookie)
            c.connect()
            require(c.responseCode in 200..299) {
                if (c.responseCode == 403) "سرور Suno/CDN درخواست دانلود مستقیم را با HTTP 403 رد کرد؛ احتمالاً این لینک به دسترسی مرورگر یا نشانی موقت نیاز دارد."
                else "دریافت فایل ناموفق بود؛ HTTP " + c.responseCode
            }
            require(isSunoAudio(c.url.toString())) { "مسیر دانلود به دامنهٔ غیرمجاز منتقل شد." }
            val mime = (c.contentType ?: "").lowercase()
            require(mime.startsWith("audio/") || mime == "application/octet-stream") {
                "پاسخ CDN فایل صوتی نیست (نوع محتوا: " + mime + "). ممکن است لینک منقضی یا آهنگ در دسترس نباشد."
            }
            val declaredSize = c.contentLengthLong
            // Preserve tiny audio responses for inspection; they may be preview or placeholder files.
            var copied = 0L
            contentResolver.openOutputStream(uri, "w")!!.use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                    }
                }
            }
            require(copied > 0) {
                "پاسخ صوتی خالی بود؛ هیچ داده‌ای دریافت نشد."
            }
            if (declaredSize >= 0) require(copied == declaredSize) { "دانلود ناقص بود؛ اندازهٔ فایل با مقدار اعلام‌شده برابر نیست." }
            c.disconnect()
            publish(uri)
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    private fun convert() {
        val source = audioUri ?: run { message("ابتدا یک فایل صوتی انتخاب کن."); return }
        if (working) return
        setWorking(true, "در حال تبدیل فایل روی گوشی…")
        thread {
            var result: Uri? = null
            val temp = File(cacheDir, "suno_" + System.currentTimeMillis() + ".wav")
            try {
                val base = safeName(displayName(source).substringBeforeLast('.', "audio"))
                decodeWav(source, temp)
                result = outputUri(base + ".wav", "audio/wav")
                contentResolver.openOutputStream(result, "w")!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
                publish(result)
                ui.post { setWorking(false, "تبدیل انجام شد؛ " + base + ".wav در پوشه Music/Suno Studio ذخیره شد.") }
            } catch (e: Exception) {
                if (result != null) try { contentResolver.delete(result, null, null) } catch (_: Exception) {}
                ui.post { setWorking(false, "تبدیل ناموفق بود: " + (e.message ?: "فرمت پشتیبانی نشد")) }
            } finally { temp.delete() }
        }
    }

    private fun decodeWav(source: Uri, file: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var out: RandomAccessFile? = null
        try {
            extractor.setDataSource(this, source, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            require(track >= 0 && fmt != null) { "ترک صوتی پیدا نشد." }
            extractor.selectTrack(track)
            val inputFormat = fmt!!
            codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            out = RandomAccessFile(file, "rw")
            out.setLength(0)
            out.write(ByteArray(44))
            val info = MediaCodec.BufferInfo()
            var inputEnd = false
            var outputEnd = false
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var total = 0L
            while (!outputEnd) {
                if (!inputEnd) {
                    val ix = codec.dequeueInputBuffer(10000)
                    if (ix >= 0) {
                        val b = codec.getInputBuffer(ix)!!
                        val n = extractor.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ix, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnd = true
                        } else {
                            codec.queueInputBuffer(ix, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ox = codec.dequeueOutputBuffer(info, 10000)
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (ox >= 0) {
                    val b = codec.getOutputBuffer(ox)
                    if (b != null && info.size > 0) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val of = codec.outputFormat
                        val encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = b.slice().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                            val pcm = java.nio.ByteBuffer.allocate(floats.remaining() * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            while (floats.hasRemaining()) {
                                val sample = floats.get().coerceIn(-1f, 1f)
                                pcm.putShort((sample * (if (sample < 0f) 32768f else 32767f)).toInt().toShort())
                            }
                            out.write(pcm.array())
                            total += pcm.position()
                        } else {
                            val bytes = ByteArray(b.remaining())
                            b.get(bytes)
                            out.write(bytes)
                            total += bytes.size
                        }
                    }
                    outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(ox, false)
                }
            }
            require(total > 0) { "دادهٔ صوتی قابل تبدیل دریافت نشد." }
            require(total <= 0xFFFFFFFFL - 36) { "فایل برای قالب WAV بیش از حد بزرگ است." }
            out.seek(0)
            wavHeader(out, total, rate, channels)
            out.fd.sync()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
            try { out?.close() } catch (_: Exception) {}
        }
    }

    private fun wavHeader(f: RandomAccessFile, size: Long, rate: Int, channels: Int) {
        f.writeBytes("RIFF"); intLE(f, (size + 36).toInt()); f.writeBytes("WAVEfmt ")
        intLE(f, 16); shortLE(f, 1); shortLE(f, channels); intLE(f, rate)
        intLE(f, rate * channels * 2); shortLE(f, channels * 2); shortLE(f, 16)
        f.writeBytes("data"); intLE(f, size.toInt())
    }
    private fun intLE(f: RandomAccessFile, n: Int) {
        f.write(n and 255); f.write(n ushr 8 and 255); f.write(n ushr 16 and 255); f.write(n ushr 24 and 255)
    }
    private fun shortLE(f: RandomAccessFile, n: Int) { f.write(n and 255); f.write(n ushr 8 and 255) }

    private fun outputUri(name: String, mime: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Suno Studio")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: error("ساخت فایل خروجی ممکن نشد.")
    }
    private fun publish(uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        contentResolver.update(uri, v, null, null)
    }
    private fun isSunoHost(host: String) = host.equals("suno.com", true) || host.endsWith(".suno.com", true)
    private fun isSunoAudio(raw: String): Boolean = try {
        val u = URL(raw)
        u.protocol == "https" && (isSunoHost(u.host) || u.host.equals("suno.ai", true) || u.host.endsWith(".suno.ai", true))
    } catch (_: Exception) { false }
    private fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(70).ifBlank { "Suno-audio" }
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "audio"
    }
    private fun setWorking(value: Boolean, msg: String) {
        working = value
        busyBar.visibility = if (value) View.VISIBLE else View.GONE
        message(msg)
    }
    private fun message(s: String) { status.text = s }
    private fun text(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(s: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
        background = rounded(if (primary) Color.rgb(112, 83, 220) else Color.rgb(47, 49, 67))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun params() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun TextView.pad(l: Int, t: Int, r: Int, b: Int) = apply { setPadding(dp(l), dp(t), dp(r), dp(b)) }
}
