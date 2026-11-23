package com.aydin.sunostudio

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class SunoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.start(this)
    }
}

/**
 * Wraps the bundled yt-dlp / FFmpeg engine. It is initialised once in the background when the
 * app starts (while the splash is playing), then silently updated at most every 12 hours.
 */
object Engine {
    enum class State { INIT, READY, FAILED }

    @Volatile var state = State.INIT
    @Volatile var error = ""
    @Volatile var ffmpegReady = false
    @Volatile var updating = false

    private val ui = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    private fun fire() {
        ui.post { listeners.forEach { it() } }
    }

    fun start(app: Application) {
        thread(name = "engine-init") {
            try {
                YoutubeDL.getInstance().init(app)
                state = State.READY
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                state = State.FAILED
            }
            try {
                FFmpeg.getInstance().init(app)
                ffmpegReady = true
            } catch (_: Throwable) {
                ffmpegReady = false
            }
            fire()
            if (state == State.READY) {
                val prefs = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
                val last = prefs.getLong("last_update", 0L)
                if (System.currentTimeMillis() - last > 12L * 3600L * 1000L) {
                    update(app)
                }
            }
        }
    }

    /** Blocking. Returns a short Persian message describing the outcome. */
    fun update(ctx: Context): String {
        if (updating) return "به‌روزرسانی در حال انجام است…"
        updating = true
        try {
            val ydl = YoutubeDL.getInstance()
            val method = ydl.javaClass.methods.firstOrNull {
                it.name == "updateYoutubeDL" && it.parameterTypes.size == 2
            } ?: return "این نسخهٔ موتور به‌روزرسانی داخلی ندارد."
            val channelType = method.parameterTypes[1]
            val channel = findChannel(channelType, "NIGHTLY") ?: findChannel(channelType, "STABLE")
                ?: return "کانال به‌روزرسانی پیدا نشد."
            val result = method.invoke(ydl, ctx.applicationContext, channel)
            ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
                .edit().putLong("last_update", System.currentTimeMillis()).apply()
            val s = result?.toString() ?: ""
            return when {
                s.contains("ALREADY", true) -> "موتور دانلود از قبل به‌روز است."
                s.contains("DONE", true) -> "موتور دانلود با موفقیت به‌روزرسانی شد."
                else -> "به‌روزرسانی انجام شد."
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val m = e.targetException?.message ?: "خطای نامشخص"
            return "به‌روزرسانی ناموفق بود (اینترنت را بررسی کنید): " + m.take(120)
        } catch (t: Throwable) {
            return "به‌روزرسانی ناموفق بود: " + (t.message ?: "").take(120)
        } finally {
            updating = false
        }
    }

    private fun findChannel(type: Class<*>, name: String): Any? {
        for (n in listOf("_$name", name)) {
            try {
                val v = type.getField(n).get(null)
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        try {
            val nested = type.declaredClasses.firstOrNull { it.simpleName == name }
            val inst = nested?.getField("INSTANCE")?.get(null)
            if (inst != null) return inst
        } catch (_: Throwable) {
        }
        return null
    }
}
