package com.aydin.sunostudio

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var content: FrameLayout
    private lateinit var nav: NavBar
    private val screens = ArrayList<Screen>()
    private var current = -1
    private var awake = 0
    private var pendingPick: ((Uri) -> Unit)? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingPick?.invoke(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val rootView = LinearLayout(this)
        rootView.orientation = LinearLayout.VERTICAL
        rootView.setBackgroundColor(Pal.BG)
        rootView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        screens.add(SunoScreen(this))
        screens.add(
            PlatformScreen(
                this,
                PlatformCfg(
                    title = "یوتیوب",
                    subtitle = "دانلود ویدیو و موسیقی از یوتیوب",
                    hint = "https://youtu.be/…",
                    iconRes = R.drawable.ic_play,
                    colors = intArrayOf(Color.parseColor("#FF2D55"), Color.parseColor("#FF6A3D")),
                    accent = Pal.RED,
                    hosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
                    options = listOf("ویدیو 1080", "ویدیو 720", "فقط صدا MP3"),
                    heights = intArrayOf(1080, 720, 0),
                    wrongLinkMessage = "لینک یوتیوب وارد کنید (youtube.com یا youtu.be)."
                )
            )
        )
        screens.add(
            PlatformScreen(
                this,
                PlatformCfg(
                    title = "اینستاگرام",
                    subtitle = "دانلود ریلز و ویدیوهای عمومی",
                    hint = "https://www.instagram.com/reel/…",
                    iconRes = R.drawable.ic_camera,
                    colors = intArrayOf(Color.parseColor("#C13584"), Color.parseColor("#FD8D32")),
                    accent = Pal.MAGENTA,
                    hosts = listOf("instagram.com", "instagr.am"),
                    options = listOf("ویدیو / ریلز", "فقط صدا MP3"),
                    heights = intArrayOf(100000, 0),
                    wrongLinkMessage = "لینک اینستاگرام وارد کنید (instagram.com/reel/… یا /p/…)."
                )
            )
        )
        screens.add(
            PlatformScreen(
                this,
                PlatformCfg(
                    title = "فیسبوک",
                    subtitle = "دانلود ویدیوهای عمومی فیسبوک",
                    hint = "https://www.facebook.com/watch/?v=…",
                    iconRes = R.drawable.ic_facebook,
                    colors = intArrayOf(Color.parseColor("#1F6BFF"), Color.parseColor("#2AB3FF")),
                    accent = Pal.BLUE,
                    hosts = listOf("facebook.com", "fb.watch", "fb.com"),
                    options = listOf("ویدیو", "فقط صدا MP3"),
                    heights = intArrayOf(100000, 0),
                    wrongLinkMessage = "لینک فیسبوک وارد کنید (facebook.com یا fb.watch)."
                )
            )
        )
        screens.add(ConverterScreen(this))
        for (s in screens) {
            s.root.visibility = View.GONE
            content.addView(s.root, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        val divider = View(this)
        divider.setBackgroundColor(Pal.STROKE)
        rootView.addView(divider, LinearLayout.LayoutParams(MATCH, 1))

        nav = NavBar(
            this,
            listOf(
                NavItem("Suno", R.drawable.ic_music, Pal.GOLD),
                NavItem("یوتیوب", R.drawable.ic_play, Pal.RED),
                NavItem("اینستاگرام", R.drawable.ic_camera, Pal.MAGENTA),
                NavItem("فیسبوک", R.drawable.ic_facebook, Pal.BLUE),
                NavItem("مبدل MP3", R.drawable.ic_convert, Pal.CYAN)
            )
        ) { select(it) }
        rootView.addView(nav, LinearLayout.LayoutParams(MATCH, dp(66)))

        setContentView(rootView)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeShown = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, if (imeShown) ime.bottom else 0)
            nav.visibility = if (imeShown) View.GONE else View.VISIBLE
            divider.visibility = nav.visibility
            nav.setInsetBottom(bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val controller = WindowInsetsControllerCompat(window, rootView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        addOnNewIntentListener { handleIntent(it) }
        select(0)
        handleIntent(intent)
    }

    fun select(i: Int) {
        if (i == current) return
        val old = if (current >= 0) screens[current].root else null
        val incoming = screens[i].root
        val dir = if (current >= 0 && i < current) 1f else -1f
        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationX = dpf(18f) * dir
        incoming.animate().alpha(1f).translationX(0f).setDuration(220).start()
        if (old != null) {
            old.animate().alpha(0f).setDuration(140).withEndAction {
                if (current < 0 || screens[current].root !== old) old.visibility = View.GONE
                old.alpha = 1f
            }.start()
        }
        current = i
        nav.select(i)
        screens[i].onShown()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Net.extractUrl(text)
        val host = Net.hostOf(url)
        val idx = when {
            Net.hostMatches(host, listOf("suno.com", "suno.ai")) -> 0
            Net.hostMatches(host, listOf("youtube.com", "youtu.be")) -> 1
            Net.hostMatches(host, listOf("instagram.com", "instagr.am")) -> 2
            Net.hostMatches(host, listOf("facebook.com", "fb.watch", "fb.com")) -> 3
            else -> -1
        }
        if (idx >= 0) {
            select(idx)
            screens[idx].acceptSharedUrl(url)
        }
    }

    fun keepAwake(on: Boolean) {
        awake = (awake + if (on) 1 else -1).coerceAtLeast(0)
        if (awake > 0) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun hideKeyboard() {
        val v = currentFocus ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    fun pickMedia(cb: (Uri) -> Unit) {
        pendingPick = cb
        picker.launch(arrayOf("audio/*", "video/*"))
    }
}
