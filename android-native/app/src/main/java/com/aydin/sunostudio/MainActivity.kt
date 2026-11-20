package com.aydin.sunostudio

import androidx.activity.ComponentActivity
import android.content.ContentValues
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.FrameLayout
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import org.jsoup.Jsoup
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
    private var working = false

    private lateinit var root: LinearLayout
    private lateinit var playerTitle: TextView
    private lateinit var playerStatus: TextView
    private lateinit var seekBar: SeekBar
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var selectedTrack: Uri? = null
    private var currentStatus: TextView? = null
    private val trackUris = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 18, 27))
        }
        val content = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(7), dp(6), dp(7))
            setBackgroundColor(Color.rgb(25, 26, 39))
        }
        root.addView(content)
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(68)))
        val pages = listOf("Suno", "Instagram", "YouTube", "TikTok")
        val icons = listOf("♫", "◎", "▶", "♪")
        fun showPage(index: Int) {
            content.removeAllViews()
            val page = when (index) {
                0 -> buildSunoPage()
                else -> buildSocialPage(pages[index], index)
            }
            content.addView(page)
            for (i in 0 until nav.childCount) {
                val item = nav.getChildAt(i) as? LinearLayout ?: continue
                for (childIndex in 0 until item.childCount) {
                    val label = item.getChildAt(childIndex) as? TextView ?: continue
                    label.setTextColor(if (i == index) Color.rgb(190, 160, 255) else Color.LTGRAY)
                    label.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
                }
            }
        }
        pages.forEachIndexed { index, name ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { showPage(index) }
            }
            item.addView(text(icons[index], 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            item.addView(text(name, 11f, Color.LTGRAY, false).apply { gravity = Gravity.CENTER })
            nav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        setContentView(root)
        showPage(0)
    }

    private fun buildSunoPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { gravity = Gravity.CENTER }
            contentDescription = "Suno Studio"
        }
        body.addView(brand)
        body.addView(text("SUNO STUDIO", 23f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        body.addView(text("دانلود آهنگ و پخش درون برنامه", 13f, Color.LTGRAY, false).apply {
            gravity = Gravity.CENTER
        }.pad(0, 4, 0, 18))
        body.addView(text("لینک آهنگ Suno", 17f, Color.WHITE, true).pad(0, 4, 0, 8))
        urlBox = EditText(this).apply {
            hint = "https://suno.com/song/..."
            setSingleLine(true); textSize = 14f; setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY); setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(36, 38, 53))
        }
        body.addView(urlBox, params().apply { bottomMargin = dp(10) })
        body.addView(button("استخراج و دانلود آهنگ", true) { download() }, params())
        extractionInfo = text("لینک عمومی آهنگ را وارد کن.", 13f, Color.rgb(190, 178, 255), false).pad(0, 12, 0, 8)
        body.addView(extractionInfo)
        extractionWebView = WebView(this).apply {
            visibility = View.GONE
            addJavascriptInterface(SunoDownloadBridge(), "SunoDownloadBridge")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (isSunoHost(URL(url).host)) { extractionAttempt = 0; schedulePageExtraction(view) }
                }
            }
        }
        body.addView(extractionWebView, LinearLayout.LayoutParams(1, 1))
        body.addView(text("پخش فایل‌های دانلودشده", 18f, Color.WHITE, true).pad(0, 18, 0, 8))
        playerTitle = text("هنوز آهنگی انتخاب نشده", 14f, Color.WHITE, true).pad(0, 6, 0, 4)
        body.addView(playerTitle)
        playerStatus = text("یک فایل از فهرست زیر انتخاب کن.", 12f, Color.LTGRAY, false)
        body.addView(playerStatus)
        seekBar = SeekBar(this)
        body.addView(seekBar)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("⏮ ۱۰ ثانیه", false) { seekBy(-10000) }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("▶ / توقف", true) { togglePlayback() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("۱۰ ثانیه ⏭", false) { seekBy(10000) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(controls)
        body.addView(button("به‌روزرسانی فهرست آهنگ‌ها", false) { refreshTracks() }.padTop(8), params())
        val listHeader = text("فایل‌های پوشه Music/Suno Studio", 13f, Color.LTGRAY, true).pad(0, 12, 0, 6)
        body.addView(listHeader)
        tracksContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tracksContainer)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        busyBar = ProgressBar(this).apply { visibility = View.GONE }
        page.addView(busyBar)
        status = text("آماده به کار", 13f, Color.rgb(190, 178, 255), true).pad(0, 8, 0, 0)
        currentStatus = status
        page.addView(status)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.let { if (it.isPlaying || it.duration > 0) it.seekTo(progress) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        refreshTracks()
        return page
    }

    private lateinit var tracksContainer: LinearLayout

    private fun buildSocialPage(service: String, index: Int): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(18))
        }
        page.addView(text(service, 25f, Color.WHITE, true).pad(0, 4, 0, 8))
        page.addView(text("دانلودر $service", 16f, Color.rgb(190, 178, 255), true).pad(0, 0, 0, 18))
        val field = EditText(this).apply {
            hint = "لینک پست یا لینک مستقیم فایل"
            setSingleLine(true); textSize = 14f; setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY); setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(36, 38, 53))
        }
        page.addView(field, params().apply { bottomMargin = dp(12) })
        page.addView(button("دانلود فایل مستقیم", true) {
            downloadDirectMedia(field.text.toString().trim(), service)
        }, params())
        page.addView(button("باز کردن لینک در مرورگر", false) {
            val raw = field.text.toString().trim()
            try {
                val u = URL(raw)
                if (u.protocol != "https") error("فقط لینک HTTPS مجاز است.")
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(raw)))
            } catch (e: Exception) { message("لینک معتبر وارد کن: " + (e.message ?: "")) }
        }.padTop(8), params())
        page.addView(text(
            "نکته: لینک صفحهٔ پست با لینک مستقیم فایل فرق دارد. این نسخه فقط فایل‌های مستقیم و عمومی را دریافت می‌کند؛ دانلود از محتوای خصوصی، نیازمند ورود، DRM یا محدودیت‌های خود پلتفرم دور زده نمی‌شود. برای YouTube لینک صفحه به‌تنهایی لینک مستقیم MP4 نیست.",
            13f, Color.LTGRAY, false
        ).pad(0, 20, 0, 0))
        page.addView(text("فایل‌های دانلودشده در پوشهٔ Download گوشی ذخیره می‌شوند.", 12f, Color.GRAY, false).pad(0, 12, 0, 0))
        val pageStatus = text("آماده به کار", 13f, Color.rgb(190, 178, 255), true).pad(0, 18, 0, 0)
        currentStatus = pageStatus
        page.addView(pageStatus)
        return page
    }

    private fun downloadDirectMedia(raw: String, service: String) {
        try {
            val u = URL(raw)
            require(u.protocol == "https") { "فقط HTTPS مجاز است." }
            val ext = u.path.substringAfterLast('.', "").lowercase()
            require(ext in setOf("mp4", "webm", "mov", "mp3", "m4a", "aac", "wav", "ogg")) {
                "این آدرس، لینک مستقیم فایل رسانه‌ای نیست؛ لینک صفحهٔ پست را نمی‌توان مستقیماً دانلود کرد."
            }
            val host = u.host.lowercase()
            require(host.endsWith("instagram.com") || host.endsWith("cdninstagram.com") ||
                host.endsWith("youtube.com") || host.endsWith("googlevideo.com") ||
                host.endsWith("youtu.be") || host.endsWith("tiktok.com") ||
                host.endsWith("tiktokcdn.com") || host.endsWith("tiktokv.com") ||
                host.endsWith("suno.com") || host.endsWith("cloudfront.net") ||
                host.endsWith("amazonaws.com")) { "دامنهٔ لینک با این دانلودر سازگار نیست." }
            val request = android.app.DownloadManager.Request(Uri.parse(raw))
                .setTitle("$service media")
                .setDescription("در حال دانلود فایل رسانه‌ای مستقیم")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName("$service-" + System.currentTimeMillis()) + ".$ext")
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            val manager = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            manager.enqueue(request)
            message("درخواست دانلود مستقیم $service ثبت شد؛ نتیجه را در اعلان‌های اندروید ببین.")
        } catch (e: Exception) { message("دانلود انجام نشد: " + (e.message ?: "لینک نامعتبر")) }
    }

    private fun refreshTracks() {
        if (!::tracksContainer.isInitialized) return
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(audioPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(audioPermission), 4102)
            tracksContainer.removeAllViews()
            tracksContainer.addView(text("برای نمایش و پخش آهنگ‌ها، اجازه دسترسی به فایل‌های صوتی را بده.", 12f, Color.LTGRAY, false))
            return
        }
        tracksContainer.removeAllViews()
        trackUris.clear()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        val selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
        try {
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection,
            arrayOf(Environment.DIRECTORY_MUSIC + "/Suno Studio%"), MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    c.getLong(0).toString())
                val name = c.getString(1) ?: "Audio"
                trackUris.add(uri)
                tracksContainer.addView(button("♫  $name", false) { playTrack(uri, name) }, params().apply { bottomMargin = dp(5) })
            }
        }
        if (trackUris.isEmpty()) tracksContainer.addView(text("هنوز فایلی در این پوشه پیدا نشد.", 12f, Color.GRAY, false))
        } catch (e: SecurityException) {
            tracksContainer.addView(text("دسترسی به فایل‌های صوتی داده نشده است.", 12f, Color.LTGRAY, false))
        } catch (e: Exception) {
            tracksContainer.addView(text("خواندن فهرست آهنگ‌ها ناموفق بود: " + (e.message ?: ""), 12f, Color.LTGRAY, false))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4102 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) refreshTracks()
    }

    private fun playTrack(uri: Uri, name: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener { mp ->
                    playerTitle.text = name
                    playerStatus.text = "در حال پخش"
                    seekBar.max = mp.duration
                    mp.start()
                }
                setOnCompletionListener { playerStatus.text = "پخش تمام شد" }
                setOnErrorListener { _, what, extra ->
                    playerStatus.text = "این فایل قابل پخش نیست ($what/$extra)"
                    true
                }
                prepareAsync()
            }
            playerTitle.text = name
            playerStatus.text = "در حال آماده‌سازی پخش…"
        } catch (e: Exception) { playerStatus.text = "پخش ناموفق بود: " + (e.message ?: "") }
    }

    private fun togglePlayback() {
        val p = mediaPlayer ?: run { playerStatus.text = "ابتدا یک آهنگ انتخاب کن."; return }
        if (p.isPlaying) { p.pause(); playerStatus.text = "متوقف شد" }
        else { p.start(); playerStatus.text = "در حال پخش" }
    }
    private fun seekBy(delta: Int) {
        mediaPlayer?.let { p -> p.seekTo((p.currentPosition + delta).coerceIn(0, p.duration)) }
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
                            ui.post { setWorking(false, "دانلود رسمی کامل شد: $fileName در پوشه Music/Suno Studio ذخیره شد."); refreshTracks() }
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
    private fun message(s: String) { currentStatus?.text = s; if (::status.isInitialized) status.text = s }
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
    private fun View.padTop(t: Int) = apply { setPadding(paddingLeft, dp(t), paddingRight, paddingBottom) }
}
