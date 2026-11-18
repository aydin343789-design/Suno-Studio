package com.aydin.sunostudio

import androidx.activity.ComponentActivity
import android.content.ContentValues
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
        page.addView(button("بررسی و دانلود فایل صوتی", true) { download() }, params())
        page.addView(text("فقط فایل عمومی و مستقیم Suno دریافت می‌شود؛ دور زدن ورود، پرداخت یا محدودیت محتوای خصوصی انجام نمی‌شود.", 12f, Color.GRAY, false).pad(0, 10, 0, 22))

        page.addView(text("۲. تبدیل فایل‌های داخل گوشی", 19f, Color.WHITE, true).pad(0, 8, 0, 12))
        page.addView(text("فایل صوتی را انتخاب کن. تبدیل روی خود گوشی انجام می‌شود و اینترنت لازم ندارد.", 14f, Color.LTGRAY, false).pad(0, 0, 0, 10))
        page.addView(button("انتخاب فایل صوتی", false) { if (!working) picker.launch(arrayOf("audio/*")) }, params())
        chosen = text("هنوز فایلی انتخاب نشده است.", 13f, Color.GRAY, false).pad(0, 12, 0, 12)
        page.addView(chosen)
        page.addView(button("تبدیل به WAV و ذخیره در گوشی", true) { convert() }, params())
        page.addView(text("در این نسخه تبدیل محلی به WAV (PCM شانزده‌بیتی) فعال است. دانلود Suno با فرمت اصلی انجام می‌شود؛ خروجی MP3 برای تبدیل فایل‌های محلی در مرحلهٔ بعدی اضافه خواهد شد.", 12f, Color.GRAY, false).pad(0, 10, 0, 22))
        busyBar = ProgressBar(this).apply { visibility = View.GONE }
        page.addView(busyBar, params())
        status = text("آماده به کار", 14f, Color.rgb(190, 178, 255), true).pad(0, 12, 0, 0)
        page.addView(status)
        page.addView(text("پردازش محلی • بدون سرور واسطه", 11f, Color.GRAY, false).apply { gravity = Gravity.CENTER }.pad(0, 26, 0, 0))
        setContentView(scroll)
    }

    private fun download() {
        if (working) return
        val raw = urlBox.text.toString().trim()
        val pageUrl = try { URL(raw) } catch (_: Exception) { message("لطفاً لینک معتبر Suno وارد کن."); return }
        if (pageUrl.protocol != "https" || !isSunoHost(pageUrl.host)) {
            message("فقط لینک HTTPS از دامنهٔ رسمی suno.com پذیرفته می‌شود."); return
        }
        setWorking(true, "در حال بررسی لینک عمومی Suno…")
        thread {
            try {
                // Only use a direct audio URL explicitly exposed by the public page metadata.
                // Do not guess CDN URLs from song IDs or access private/restricted resources.
                val doc = Jsoup.connect(raw).userAgent("Mozilla/5.0 Android SunoStudio").timeout(18000).get()
                require(isSunoHost(URL(doc.location()).host)) { "صفحه به دامنهٔ دیگری منتقل شد." }
                val keys = listOf("meta[property=og:audio]", "meta[property=og:audio:secure_url]", "meta[name=twitter:player:stream]")
                var audio: String? = null
                for (key in keys) {
                    val candidate = doc.selectFirst(key)?.attr("content")?.trim()
                    if (!candidate.isNullOrBlank() && isSunoAudio(candidate)) { audio = candidate; break }
                }
                require(audio != null) { "این صفحه نشانی مستقیم و عمومی صوتی ارائه نکرده است؛ از دانلود رسمی Suno استفاده کن." }
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.take(80) ?: "Suno-audio"
                val name = safeName(title) + ".mp3"
                saveRemote(audio!!, name, raw)
                ui.post { setWorking(false, "دانلود کامل شد: $name در Music/Suno Studio") }
            } catch (e: Exception) {
                ui.post { setWorking(false, e.message ?: "دانلود انجام نشد.") }
            }
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
            require(declaredSize < 0 || declaredSize >= 8192) {
                "فایل برگشتی بیش از حد کوچک است و احتمالاً صدای جایگزین/خطاست، نه آهنگ اصلی."
            }
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
            require(copied >= 8192) {
                "فایل دریافتی فقط $copied بایت است؛ آهنگ کامل دریافت نشده است."
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
