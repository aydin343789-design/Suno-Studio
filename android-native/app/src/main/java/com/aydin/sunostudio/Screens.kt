package com.aydin.sunostudio

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.concurrent.thread

abstract class Screen(val act: MainActivity) {
    val root: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(18), act.dp(14), act.dp(18), act.dp(10))
    }

    open fun onShown() {}
    open fun acceptSharedUrl(url: String) {}

    protected fun openFile(uri: Uri, name: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, Store.mimeFor(name))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(act, "برنامه‌ای برای باز کردن این فایل پیدا نشد.", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Suno
// ---------------------------------------------------------------------------------------------
class SunoScreen(act: MainActivity) : Screen(act) {
    private val colors = intArrayOf(Pal.VIOLET, Pal.MAGENTA)
    private val field = UrlField(act, "https://suno.com/song/…", Pal.VIOLET) { start() }
    private val button = GradientButton(act, colors, R.drawable.ic_download, "دانلود MP3")
    private val preview = PreviewCard(act, R.drawable.ic_music, Pal.VIOLET)
    private val status = StatusView(act, colors)

    @Volatile
    private var working = false

    init {
        root.addBlock(
            screenHeader(act, R.drawable.ic_music, colors, "Suno Studio", "دانلود آهنگ از لینک عمومی Suno با فرمت MP3"),
            WRAP
        )
        root.addBlock(field, act.dp(56), 20)
        root.addBlock(button, act.dp(56), 12)
        root.addBlock(preview, WRAP, 18)
        root.addBlock(status, WRAP, 16)
        preview.placeholder("پیش‌نمایش آهنگ", "پس از بررسی لینک، عنوان و کاور اینجا نمایش داده می‌شود.")
        status.info("لینک صفحهٔ یک آهنگ عمومی Suno را بچسبانید و دکمه را بزنید.")
        button.setOnClickListener { start() }
    }

    override fun acceptSharedUrl(url: String) {
        field.text = url
    }

    private fun start() {
        if (working) return
        val page = Suno.normalizeUrl(field.text)
        if (page == null) {
            status.bad("لینک معتبر Suno وارد کنید؛ مثل suno.com/song/…")
            return
        }
        act.hideKeyboard()
        setWorking(true)
        status.bar.setIndeterminate()
        status.info("در حال خواندن اطلاعات آهنگ…")
        thread(name = "suno-download") {
            try {
                val info = Suno.resolve(page)
                act.runOnUiThread {
                    preview.filled(info.title, info.artist.ifBlank { "Suno" })
                    status.info("در حال دریافت فایل صوتی…")
                }
                if (info.image.isNotBlank()) {
                    val bmp = Net.loadBitmap(info.image, 300)
                    if (bmp != null) act.runOnUiThread { preview.setCover(bmp) }
                }
                val result = Suno.download(act, info) { p, msg ->
                    act.runOnUiThread {
                        status.bar.setProgress(p)
                        val pct = if (p in 0.01f..0.99f) " " + (p * 100).toInt().toString().fa() + "٪" else ""
                        status.info(msg + pct)
                    }
                }
                act.runOnUiThread {
                    status.bar.setProgress(1f)
                    status.good("ذخیره شد: " + result.second + "\nپوشهٔ Music/Suno Studio — برای پخش روی کارت بزنید.")
                    preview.setOnClickListener { openFile(result.first, result.second) }
                    setWorking(false)
                }
            } catch (e: Throwable) {
                act.runOnUiThread {
                    status.bar.reset()
                    status.bad(e.message ?: "خطای ناشناخته")
                    setWorking(false)
                }
            }
        }
    }

    private fun setWorking(v: Boolean) {
        working = v
        button.setBusy(v)
        act.keepAwake(v)
    }
}

// ---------------------------------------------------------------------------------------------
// YouTube / Instagram / Facebook
// ---------------------------------------------------------------------------------------------
class PlatformCfg(
    val title: String,
    val subtitle: String,
    val hint: String,
    val iconRes: Int,
    val colors: IntArray,
    val accent: Int,
    val hosts: List<String>,
    val options: List<String>,
    val heights: IntArray,
    val wrongLinkMessage: String
)

class PlatformScreen(act: MainActivity, private val cfg: PlatformCfg) : Screen(act) {
    private val field = UrlField(act, cfg.hint, cfg.accent) { start() }
    private val seg = Segmented(act, cfg.options, cfg.colors) { }
    private val button = GradientButton(act, cfg.colors, R.drawable.ic_download, "دانلود")
    private val preview = PreviewCard(act, cfg.iconRes, cfg.accent)
    private val status = StatusView(act, cfg.colors)
    private val cancelColors = intArrayOf(Color.parseColor("#E5484D"), Color.parseColor("#B8324A"))

    @Volatile
    private var working = false

    @Volatile
    private var processId = ""

    @Volatile
    private var cancelled = false

    init {
        root.addBlock(
            screenHeader(act, cfg.iconRes, cfg.colors, cfg.title, cfg.subtitle, R.drawable.ic_refresh) { updateEngine() },
            WRAP
        )
        root.addBlock(field, act.dp(56), 20)
        root.addBlock(seg, act.dp(44), 12)
        root.addBlock(button, act.dp(56), 12)
        root.addBlock(preview, WRAP, 18)
        root.addBlock(status, WRAP, 16)
        preview.placeholder("پیش‌نمایش", "عنوان، تصویر و مدت ویدیو پس از بررسی لینک اینجا نمایش داده می‌شود.")
        button.setOnClickListener { start() }
        refreshEngineStatus()
        Engine.addListener { if (!working) refreshEngineStatus() }
    }

    override fun acceptSharedUrl(url: String) {
        field.text = url
    }

    private fun refreshEngineStatus() {
        when (Engine.state) {
            Engine.State.INIT -> status.info("موتور دانلود در حال آماده‌سازی است (فقط بار اول چند ثانیه طول می‌کشد)…")
            Engine.State.READY -> status.info("لینک را بچسبانید، کیفیت را انتخاب کنید و دانلود را بزنید.")
            Engine.State.FAILED -> status.bad("موتور دانلود روی این دستگاه راه‌اندازی نشد: " + Engine.error.take(120))
        }
    }

    private fun updateEngine() {
        if (working) return
        if (Engine.state != Engine.State.READY) {
            refreshEngineStatus()
            return
        }
        status.bar.setIndeterminate()
        status.info("در حال به‌روزرسانی موتور دانلود…")
        thread(name = "engine-update") {
            val msg = Engine.update(act)
            act.runOnUiThread {
                status.bar.reset()
                status.info(msg)
            }
        }
    }

    private fun start() {
        if (working) {
            cancelled = true
            Media.cancel(processId)
            status.info("در حال لغو…")
            return
        }
        val url = Net.withScheme(Net.extractUrl(field.text))
        if (field.text.isBlank() || !Net.hostMatches(Net.hostOf(url), cfg.hosts)) {
            status.bad(cfg.wrongLinkMessage)
            return
        }
        if (Engine.state == Engine.State.INIT) {
            status.info("موتور دانلود هنوز آماده نیست؛ چند ثانیه بعد دوباره بزنید.")
            return
        }
        if (Engine.state == Engine.State.FAILED) {
            status.bad("موتور دانلود روی این دستگاه راه‌اندازی نشد: " + Engine.error.take(120))
            return
        }

        val idx = seg.selected
        val height = cfg.heights[idx]
        val audioOnly = height == 0
        cancelled = false
        processId = "dl-" + System.currentTimeMillis()
        act.hideKeyboard()
        setWorking(true)
        status.bar.setIndeterminate()
        status.info("در حال بررسی لینک…")

        thread(name = "media-download") {
            try {
                val info = Media.fetchInfo(url)
                if (cancelled) throw IllegalStateException("دانلود لغو شد.")
                val meta = listOf(info.uploader, formatDuration(info.durationSec).fa())
                    .filter { it.isNotBlank() }.joinToString("  •  ")
                act.runOnUiThread {
                    preview.filled(info.title, meta)
                    status.info("در حال دانلود…")
                }
                if (info.thumbnail.isNotBlank()) {
                    val bmp = Net.loadBitmap(info.thumbnail, 300)
                    if (bmp != null) act.runOnUiThread { preview.setCover(bmp) }
                }

                var lastPct = -1
                val file = Media.download(act, url, if (height <= 0) 100000 else height, audioOnly, processId) { frac, line ->
                    val processing = line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                        line.contains("[VideoConvertor]") || line.contains("[Fixup")
                    if (processing) {
                        act.runOnUiThread {
                            status.bar.setIndeterminate()
                            status.info("در حال پردازش و ادغام صدا و تصویر…")
                        }
                    } else if (frac >= 0f) {
                        val pct = (frac * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            act.runOnUiThread {
                                status.bar.setProgress(frac)
                                status.info("در حال دانلود… " + pct.toString().fa() + "٪")
                            }
                        }
                    }
                }
                if (cancelled) {
                    file.parentFile?.deleteRecursively()
                    throw IllegalStateException("دانلود لغو شد.")
                }
                act.runOnUiThread {
                    status.bar.setIndeterminate()
                    status.info("در حال ذخیره در گالری/موسیقی…")
                }
                val name = Store.safeName(file.nameWithoutExtension, "download") + "." + file.extension.lowercase()
                val uri = try {
                    Store.publish(act, file, name, Store.kindFor(name))
                } finally {
                    file.parentFile?.deleteRecursively()
                }
                val folder = if (Store.kindFor(name) == Store.Kind.VIDEO) "Movies/Suno Studio" else "Music/Suno Studio"
                act.runOnUiThread {
                    status.bar.setProgress(1f)
                    status.good("ذخیره شد: $name\nپوشهٔ $folder — برای پخش روی کارت بزنید.")
                    preview.setOnClickListener { openFile(uri, name) }
                    setWorking(false)
                }
            } catch (e: Throwable) {
                val raw = e.message ?: e.javaClass.simpleName
                act.runOnUiThread {
                    status.bar.reset()
                    if (cancelled) status.info("دانلود لغو شد.") else status.bad(Media.friendlyError(raw))
                    setWorking(false)
                }
            }
        }
    }

    private fun setWorking(v: Boolean) {
        working = v
        button.setBusy(false)
        if (v) button.setStyle(cancelColors, R.drawable.ic_stop, "لغو")
        else button.setStyle(cfg.colors, R.drawable.ic_download, "دانلود")
        field.edit.isEnabled = !v
        seg.alpha = if (v) 0.5f else 1f
        seg.isEnabled = !v
        act.keepAwake(v)
    }
}

// ---------------------------------------------------------------------------------------------
// Converter
// ---------------------------------------------------------------------------------------------
class ConverterScreen(act: MainActivity) : Screen(act) {
    private val colors = intArrayOf(Pal.CYAN, Pal.GREEN)
    private val picker = PreviewCard(act, R.drawable.ic_folder, Pal.CYAN)
    private val seg = Segmented(act, listOf("۱۲۸ kbps", "۱۹۲ kbps", "۳۲۰ kbps"), colors) { }
    private val button = GradientButton(act, colors, R.drawable.ic_convert, "تبدیل به MP3")
    private val status = StatusView(act, colors)
    private var uri: Uri? = null
    private var name = ""

    @Volatile
    private var working = false

    init {
        root.addBlock(
            screenHeader(act, R.drawable.ic_convert, colors, "مبدل MP3", "تبدیل WAV و هر فایل صوتی یا ویدیویی به MP3"),
            WRAP
        )
        root.addBlock(picker, WRAP, 20)
        root.addBlock(makeText(act, "کیفیت خروجی", 12f, Pal.MUTED), WRAP, 16)
        root.addBlock(seg, act.dp(44), 6)
        root.addBlock(button, act.dp(56), 14)
        root.addBlock(status, WRAP, 16)
        seg.select(1, false)
        resetPicker()
        picker.setOnClickListener { choose() }
        button.setOnClickListener { convert() }
        status.info("ابتدا فایل را انتخاب کنید؛ خروجی در Music/Suno Studio ذخیره می‌شود.")
    }

    private fun resetPicker() {
        picker.placeholder("انتخاب فایل", "برای انتخاب فایل صوتی یا ویدیویی از حافظهٔ گوشی اینجا بزنید.")
        picker.setOnClickListener { choose() }
    }

    private fun choose() {
        if (working) return
        act.pickMedia { u ->
            uri = u
            var n = "audio"
            var size = 0L
            try {
                act.contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) n = c.getString(ni) ?: n
                        if (si >= 0) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {
            }
            name = n
            picker.filled(n, humanSize(size).fa())
            picker.setOnClickListener { choose() }
            status.info("آمادهٔ تبدیل. کیفیت را انتخاب و دکمه را بزنید.")
            status.bar.reset()
        }
    }

    private fun convert() {
        if (working) return
        val src = uri
        if (src == null) {
            status.bad("ابتدا یک فایل انتخاب کنید.")
            return
        }
        val kbps = when (seg.selected) {
            0 -> 128
            2 -> 320
            else -> 192
        }
        working = true
        button.setBusy(true)
        act.keepAwake(true)
        status.bar.setIndeterminate()
        status.info("در حال شروع…")
        thread(name = "mp3-convert") {
            try {
                val r = Mp3.convert(act, src, name, kbps) { p, msg ->
                    act.runOnUiThread {
                        if (p < 0f) status.bar.setIndeterminate() else status.bar.setProgress(p)
                        val pct = if (p in 0.01f..0.99f) " " + (p * 100).toInt().toString().fa() + "٪" else ""
                        status.info(msg + pct)
                    }
                }
                act.runOnUiThread {
                    status.bar.setProgress(1f)
                    val extra = if (r.note.isNotBlank()) "\n" + r.note else ""
                    status.good("ذخیره شد: " + r.name + "\nپوشهٔ Music/Suno Studio" + extra)
                    picker.setOnClickListener { openFile(r.uri, r.name) }
                    finish()
                }
            } catch (e: Throwable) {
                act.runOnUiThread {
                    status.bar.reset()
                    status.bad(e.message ?: "تبدیل ناموفق بود.")
                    finish()
                }
            }
        }
    }

    private fun finish() {
        working = false
        button.setBusy(false)
        act.keepAwake(false)
    }
}
