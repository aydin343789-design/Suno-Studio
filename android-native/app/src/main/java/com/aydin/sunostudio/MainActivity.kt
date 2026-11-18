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
            addJavascriptInterface(SunoDownloadBridge(), "SunoDownloadBridge")
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
              let expectedDuration = 0;
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
                if (Array.isArray(value)) {
                  if (/^media_urls$/i.test(key || '')) {
                    value.forEach(v => {
                      if (v && typeof v.url === 'string') add(v.url, 'media_urls:' + (v.content_type || ''));
                    });
                  }
                  value.forEach(v => scanPublicState(v, key, depth + 1));
                  return;
                }
                if (typeof value === 'object') {
                  if (typeof value.duration === 'number' &&
                      (value.id === location.pathname.split('/').pop() || value.entity_type === 'song_schema')) {
                    expectedDuration = value.duration;
                  }
                  Object.keys(value).forEach(k => scanPublicState(value[k], k, depth + 1));
                }
              };
              document.querySelectorAll('script[type="application/json"],script#__NEXT_DATA__').forEach(el => {
                try { scanPublicState(JSON.parse(el.textContent || ''), 'page-state', 0); } catch(e) {}
              });
              document.querySelectorAll('script:not([src])').forEach(el => {
                const txt = (el.textContent || '').replace(/\\"/g, '"').replace(/\\u0026/g, '&').replace(/\\\//g, '/');
                const re = /["'](?:audio_url|audioUrl|stream_audio_url|streamAudioUrl|audio_src|audioSrc)["']\s*:\s*["']([^"']+)["']/gi;
                let m;
                while ((m = re.exec(txt)) !== null) {
                  try { add(m[1], 'public-state:embedded-audio-url'); } catch(e) {}
                }
                // Suno exposes the playable public file under media_urls in the page's Flight state.
                const mediaRe = /["']media_urls["']\s*:\s*\[\s*\{\s*["']url["']\s*:\s*["']([^"']+)/gi;
                while ((m = mediaRe.exec(txt)) !== null) {
                  try { add(m[1], 'public-state:media_urls'); } catch(e) {}
                }
                if (txt.indexOf(location.pathname.split('/').pop()) >= 0) {
                  const durationMatch = /["']duration["']\s*:\s*([0-9]+(?:\.[0-9]+)?)/i.exec(txt);
                  if (durationMatch) expectedDuration = Math.max(expectedDuration, parseFloat(durationMatch[1]));
                }
              });
              ['og:audio','og:audio:secure_url','twitter:player:stream'].forEach(k => {
                const e = document.querySelector('meta[property="'+k+'"],meta[name="'+k+'"]');
                if (e) add(e.content, k);
              });
              performance.getEntriesByType('resource').forEach(e => add(e.name, e.initiatorType));
              return JSON.stringify({title,description,image,artist,url:location.href,candidates,expectedDuration});
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { encoded ->
            if (!working) return@evaluateJavascript
            try {
                val jsonText = if (encoded == null || encoded == "null") "" else org.json.JSONTokener(encoded).nextValue() as? String ?: ""
                if (jsonText.isNotBlank()) {
                    val parsed = JSONObject(jsonText)
                    handlePageExtraction(jsonText)
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
        val title = data.optString("title").ifBlank { "Suno-audio" }
        val artist = data.optString("artist")
        val description = data.optString("description")
        val image = data.optString("image")
        val expectedDurationMs = (data.optDouble("expectedDuration", 0.0) * 1000.0).toLong()
        extractionInfo.text = buildString {
            append("اطلاعات صفحه استخراج شد\nعنوان: ").append(title)
            if (artist.isNotBlank()) append("\nسازنده: ").append(artist)
            if (description.isNotBlank()) append("\nتوضیحات: ").append(description.take(220))
            if (image.isNotBlank()) append("\nتصویر کاور: شناسایی شد")
            if (expectedDurationMs > 0) append("\nمدت آهنگ: ").append(expectedDurationMs / 1000).append(" ثانیه")
            append("\nروش دریافت: API رسمی دانلود Suno (M4A)")
        }
        setWorking(true, "در حال درخواست لینک دانلود رسمی M4A از Suno…")
        requestOfficialM4a(title, expectedDurationMs)
    }

    // Uses the same official endpoint and ready/processing protocol as Suno's web client.
    // Authentication and download permissions remain enforced by Suno.
    private fun requestOfficialM4a(title: String, expectedDurationMs: Long) {
        val web = extractionWebView ?: run {
            setWorking(false, "مرورگر داخلی آماده نیست.")
            return
        }
        val jsTitle = JSONObject.quote(title)
        val script = """
            (function() {
              const clipId = location.pathname.split('/').filter(Boolean).pop() || '';
              const title = $jsTitle;
              const expectedDurationMs = $expectedDurationMs;
              const send = (data) => {
                try { window.SunoDownloadBridge.onResult(JSON.stringify(data)); } catch (e) {}
              };
              (async () => {
                try {
                  if (!/^[0-9a-f-]{36}$/i.test(clipId)) {
                    send({ok:false,message:'شناسه آهنگ از صفحه استخراج نشد.'}); return;
                  }
                  for (let attempt = 0; attempt < 90; attempt++) {
                    const response = await fetch('/api/download/clip/' + encodeURIComponent(clipId) + '?format=m4a', {
                      method:'GET', credentials:'include', headers:{'Accept':'application/json'}
                    });
                    let body = null;
                    try { body = await response.json(); } catch (_) {}
                    const result = body && (body.data ?? body.error ?? body);
                    if (!response.ok) {
                      const detail = result && (result.detail || result.message);
                      send({ok:false,message:'API رسمی Suno پاسخ HTTP ' + response.status + (detail ? ': ' + detail : '')});
                      return;
                    }
                    if (result && result.status === 'ready' && result.download_url) {
                      send({ok:true,downloadUrl:result.download_url,title,expectedDurationMs}); return;
                    }
                    if (result && result.status === 'error') {
                      send({ok:false,message:result.detail || result.message || 'آماده‌سازی فایل ناموفق بود.'}); return;
                    }
                    if (result && result.reason && result.reason !== 'rate_limited' && result.ok === false) {
                      send({ok:false,message:result.detail || result.message || result.reason}); return;
                    }
                    const delay = result && result.reason === 'rate_limited'
                      ? 1200 + Math.floor(Math.random() * 900) : 2000;
                    await new Promise(resolve => setTimeout(resolve, delay));
                  }
                  send({ok:false,message:'آماده‌سازی دانلود توسط Suno بیش از حد طول کشید.'});
                } catch (e) {
                  send({ok:false,message:'ارتباط با API رسمی Suno ناموفق بود: ' + (e && e.message ? e.message : 'خطای ناشناخته')});
                }
              })();
              return true;
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private inner class SunoDownloadBridge {
        @JavascriptInterface
        fun onResult(payload: String) {
            ui.post {
                if (!working) return@post
                try {
                    val result = JSONObject(payload)
                    if (!result.optBoolean("ok")) {
                        setWorking(false, "دانلود رسمی آماده نشد: " + result.optString("message", "خطای نامشخص"))
                        return@post
                    }
                    val downloadUrl = result.optString("downloadUrl")
                    val title = result.optString("title").ifBlank { "Suno-audio" }
                    val expectedDurationMs = result.optLong("expectedDurationMs", 0L)
                    if (!isOfficialDownloadUrl(downloadUrl)) {
                        setWorking(false, "Suno آدرس دانلودی با دامنهٔ معتبر برنگرداند؛ فایل ذخیره نشد.")
                        return@post
                    }
                    setWorking(true, "لینک رسمی آماده شد؛ در حال دریافت و اعتبارسنجی فایل کامل…")
                    thread {
                        try {
                            val fileName = safeName(title) + ".m4a"
                            saveRemote(downloadUrl, fileName, extractionPageUrl, expectedDurationMs)
                            ui.post { setWorking(false, "دانلود رسمی کامل شد: $fileName در پوشه Music/Suno Studio ذخیره شد.") }
                        } catch (e: Exception) {
                            ui.post { setWorking(false, "دریافت فایل رسمی ناموفق بود: " + (e.message ?: "خطای نامشخص")) }
                        }
                    }
                } catch (e: Exception) {
                    setWorking(false, "پاسخ API دانلود Suno قابل‌خواندن نبود: " + (e.message ?: "خطا"))
                }
            }
        }
    }

    private fun saveRemote(link: String, name: String, referer: String, expectedDurationMs: Long) {
        val songId = songIdFromPage(referer)
        require(isOfficialDownloadUrl(link)) { "آدرس دانلود رسمی Suno معتبر نیست." }
        val mimeType = audioMime(link)
        val uri = outputUri(name, mimeType)
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
            require(isOfficialDownloadUrl(c.url.toString())) { "مسیر دانلود به دامنهٔ غیرمجاز منتقل شد." }
            val mime = (c.contentType ?: "").lowercase()
            require(mime.startsWith("audio/") || mime == "application/octet-stream") {
                "پاسخ CDN فایل صوتی نیست (نوع محتوا: " + mime + "). ممکن است لینک منقضی یا آهنگ در دسترس نباشد."
            }
            val declaredSize = c.contentLengthLong
            // Reject tiny preview/placeholder responses instead of presenting them as the full song.
            var copied = 0L
            val output = contentResolver.openOutputStream(uri, "w")
                ?: error("اندروید نتوانست فایل خروجی را باز کند.")
            output.use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                    }
                    out.flush()
                }
            }
            require(copied > 32_000L) {
                "فایل دریافتی بسیار کوچک است و احتمالاً نمونهٔ کوتاه یا فایل جایگزین است؛ آهنگ ذخیره نشد."
            }
            if (declaredSize >= 0) require(copied == declaredSize) { "دانلود ناقص بود؛ اندازهٔ فایل با مقدار اعلام‌شده برابر نیست." }
            c.disconnect()
            validateM4aContainer(uri)
            publish(uri, copied)
            validateAudioDuration(uri, expectedDurationMs, copied)
            val savedName = querySavedName(uri)
            require(savedName.isNotBlank()) { "فایل دریافت شد، اما در حافظهٔ رسانه‌ای گوشی قابل مشاهده نیست." }
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
                publish(result, temp.length())
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
    private fun publish(uri: Uri, expectedBytes: Long) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        val updated = contentResolver.update(uri, v, null, null)
        require(updated == 1) { "اندروید فایل را دریافت کرد اما نتوانست آن را در پوشهٔ Music منتشر کند." }
        var visible = false
        var actualSize = 0L
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                actualSize = cursor.getLong(0)
                val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                visible = pendingIndex < 0 || cursor.getInt(pendingIndex) == 0
            }
        }
        require(visible && actualSize > 0L && actualSize == expectedBytes) {
            "فایل در حافظهٔ گوشی تأیید نشد؛ حجم مورد انتظار: $expectedBytes بایت، حجم ثبت‌شده: $actualSize بایت."
        }
    }
    private fun querySavedName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: ""
        }
        return ""
    }
    private fun isSunoHost(host: String) = host.equals("suno.com", true) || host.endsWith(".suno.com", true)
    private fun isOfficialDownloadUrl(raw: String): Boolean = try {
        val u = URL(raw)
        val host = u.host.lowercase()
        val cloudfront = host.endsWith(".cloudfront.net")
        val s3 = host.endsWith(".amazonaws.com") && host.split('.').any { it == "s3" || it.startsWith("s3-") }
        val suno = isSunoHost(host) || host == "suno.ai" || host.endsWith(".suno.ai")
        u.protocol == "https" && (cloudfront || s3 || suno)
    } catch (_: Exception) { false }

    private fun songIdFromPage(raw: String): String =
        Regex("/song/([0-9a-fA-F-]{36})").find(raw)?.groupValues?.get(1) ?: ""
    private fun isSunoAudio(raw: String, songId: String = ""): Boolean = try {
        val u = URL(raw)
        val sunoOwned = isSunoHost(u.host) || u.host.equals("suno.ai", true) || u.host.endsWith(".suno.ai", true)
        val verifiedPublicMedia = u.host.equals("d2lwuy8qc234o3.cloudfront.net", true) &&
            songId.isNotBlank() && u.path.contains(songId) &&
            u.path.matches(Regex(".*\\.(m4a|mp3|aac|wav|ogg|opus|flac)$", RegexOption.IGNORE_CASE))
        u.protocol == "https" && (sunoOwned || verifiedPublicMedia)
    } catch (_: Exception) { false }
    private fun audioExtension(raw: String): String {
        val path = try { URL(raw).path.lowercase() } catch (_: Exception) { "" }
        return listOf(".m4a", ".mp3", ".aac", ".wav", ".ogg", ".opus", ".flac")
            .firstOrNull { path.endsWith(it) } ?: ".m4a"
    }
    private fun audioMime(raw: String): String = when (audioExtension(raw)) {
        ".mp3" -> "audio/mpeg"
        ".wav" -> "audio/wav"
        ".ogg", ".opus" -> "audio/ogg"
        ".aac" -> "audio/aac"
        else -> "audio/mp4"
    }
    private fun validateM4aContainer(uri: Uri) {
        val header = contentResolver.openInputStream(uri)?.use { input ->
            ByteArray(32).also { buffer ->
                val count = input.read(buffer)
                if (count < 12) error("فایل دریافتی ساختار M4A ندارد.")
            }
        } ?: error("خواندن فایل دریافتی ممکن نشد.")
        val hasFtyp = (4..(header.size - 8).coerceAtLeast(4)).any { i ->
            header[i] == 'f'.code.toByte() && header[i + 1] == 't'.code.toByte() &&
            header[i + 2] == 'y'.code.toByte() && header[i + 3] == 'p'.code.toByte()
        }
        require(hasFtyp) { "پاسخ دانلود، فایل M4A معتبر نیست؛ احتمالاً پاسخ خطا یا فایل جایگزین است." }
    }

    private fun validateAudioDuration(uri: Uri, expectedDurationMs: Long, bytes: Long) {
        val retriever = android.media.MediaMetadataRetriever()
        var actualDurationMs = 0L
        try {
            retriever.setDataSource(this, uri)
            actualDurationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // Some Android 10 builds cannot inspect Opus-in-M4A metadata; size validation remains active.
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        require(bytes > 32_000L) { "فایل صوتی بیش از حد کوچک است." }
        if (expectedDurationMs > 0L && actualDurationMs > 0L) {
            val tolerance = maxOf(5000L, (expectedDurationMs * 0.03).toLong())
            require(kotlin.math.abs(actualDurationMs - expectedDurationMs) <= tolerance) {
                "مدت فایل با مدت آهنگ صفحه مطابقت ندارد؛ مورد کوتاه/جایگزین ذخیره نشد."
            }
        } else if (actualDurationMs in 1L..10_000L) {
            error("فایل فقط چند ثانیه است و آهنگ کامل نیست؛ ذخیره نشد.")
        }
    }
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
