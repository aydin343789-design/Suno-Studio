package com.aydin.sunostudio

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebData(val title: String, val image: String, val href: String, val urls: List<String>)

/**
 * Hidden WebView used for Suno. Suno's servers reject plain Java/OkHttp requests (HTTP 403) because
 * they look like bots; a real Chromium WebView is accepted. We use it to (1) read the song page,
 * (2) provide the browser User-Agent + cookies for a direct download, and (3) as a last resort
 * download the audio bytes from inside the page itself.
 */
class SunoWeb(private val act: Activity) {
    private val ui = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    @Volatile
    var userAgent: String = Net.UA

    @Volatile
    private var out: FileOutputStream? = null

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var error: String? = null

    @Volatile
    private var total = 0
    private var written = 0L

    @Volatile
    private var progress: ((Float) -> Unit)? = null

    private val extractScript = """
        (function(){
          function m(k){var e=document.querySelector('meta[property="'+k+'"],meta[name="'+k+'"]');return e?(e.content||''):'';}
          var found=[];
          try{performance.getEntriesByType('resource').forEach(function(r){
            if(/cdn\d*\.suno\.ai/.test(r.name)&&/\.(mp3|m4a|wav)/.test(r.name)){found.push(r.name);}
          });}catch(e){}
          try{
            var html=document.documentElement.innerHTML;
            var re=/https:(?:\\\/|\/){2}cdn\d*\.suno\.ai(?:\\\/|\/)[^"'\\\s<>]+?\.(?:mp3|m4a|wav)/g;
            var x;var n=0;
            while((x=re.exec(html))!==null&&n<20){found.push(x[0].split('\\/').join('/'));n++;}
          }catch(e){}
          return JSON.stringify({
            title:m('og:title')||document.title||'',
            image:m('og:image'),
            href:location.href,
            audio:[m('og:audio'),m('og:audio:secure_url')],
            found:found
          });
        })()
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun load(page: String, timeoutMs: Long = 25000L): WebData? {
        val result = arrayOfNulls<WebData>(1)
        val done = CountDownLatch(1)
        ui.post {
            try {
                val w = WebView(act)
                web = w
                w.alpha = 0f
                w.setBackgroundColor(Color.TRANSPARENT)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.mediaPlaybackRequiresUserGesture = true
                userAgent = w.settings.userAgentString ?: Net.UA
                w.addJavascriptInterface(Bridge(), "SunoBridge")
                act.addContentView(w, ViewGroup.LayoutParams(1, 1))
                w.loadUrl(page)
                val start = SystemClock.elapsedRealtime()
                val poll = object : Runnable {
                    override fun run() {
                        val wv = web
                        if (wv == null) {
                            done.countDown()
                            return
                        }
                        wv.evaluateJavascript(extractScript) { raw ->
                            val data = parse(raw)
                            val elapsed = SystemClock.elapsedRealtime() - start
                            val ready = data != null &&
                                (data.urls.isNotEmpty() || (data.title.isNotBlank() && elapsed > 3500L))
                            if (ready || elapsed > timeoutMs) {
                                result[0] = data
                                done.countDown()
                            } else {
                                ui.postDelayed(this, 700L)
                            }
                        }
                    }
                }
                ui.postDelayed(poll, 1200L)
            } catch (_: Throwable) {
                done.countDown()
            }
        }
        done.await(timeoutMs + 6000L, TimeUnit.MILLISECONDS)
        return result[0]
    }

    private fun parse(raw: String?): WebData? {
        if (raw == null || raw == "null") return null
        return try {
            val s = JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(s)
            val urls = ArrayList<String>()
            val audio = o.optJSONArray("audio")
            if (audio != null) for (i in 0 until audio.length()) {
                val v = audio.optString(i)
                if (v.startsWith("http")) urls.add(v)
            }
            val found = o.optJSONArray("found")
            if (found != null) for (i in 0 until found.length()) {
                val v = found.optString(i).replace("\\/", "/")
                if (v.startsWith("http")) urls.add(v)
            }
            WebData(o.optString("title"), o.optString("image"), o.optString("href"), urls)
        } catch (_: Throwable) {
            null
        }
    }

    fun cookieHeader(url: String): String = try {
        CookieManager.getInstance().getCookie(url) ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** Downloads [url] with the page's own fetch(); works only if the CDN allows cross-origin reads. */
    fun fetchViaPage(url: String, dest: File, onProgress: (Float) -> Unit): String? {
        val w = web ?: return "WebView در دسترس نیست"
        val l = CountDownLatch(1)
        latch = l
        error = null
        total = 0
        written = 0L
        progress = onProgress
        val stream = try {
            FileOutputStream(dest)
        } catch (e: Exception) {
            return e.message ?: "خطای فایل"
        }
        out = stream
        val js = """
            (function(){
              fetch(${JSONObject.quote(url)},{credentials:'omit'}).then(function(r){
                if(!r.ok){throw new Error('HTTP '+r.status);}
                return r.arrayBuffer();
              }).then(function(buf){
                var u=new Uint8Array(buf);
                SunoBridge.begin(u.length);
                var CH=196608;
                for(var i=0;i<u.length;i+=CH){
                  var sub=u.subarray(i,Math.min(i+CH,u.length));var s='';
                  for(var j=0;j<sub.length;j+=8192){
                    s+=String.fromCharCode.apply(null,sub.subarray(j,Math.min(j+8192,sub.length)));
                  }
                  SunoBridge.chunk(btoa(s));
                }
                SunoBridge.done();
              }).catch(function(e){SunoBridge.fail(String(e));});
            })();
        """.trimIndent()
        ui.post { w.evaluateJavascript(js, null) }
        val finished = l.await(150, TimeUnit.SECONDS)
        try {
            stream.close()
        } catch (_: Exception) {
        }
        out = null
        if (!finished) return "زمان دریافت تمام شد"
        return error
    }

    fun close() {
        ui.post {
            try {
                val w = web
                web = null
                if (w != null) {
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.destroy()
                }
            } catch (_: Throwable) {
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun begin(n: Int) {
            total = n
        }

        @JavascriptInterface
        fun chunk(b64: String) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                out?.write(bytes)
                written += bytes.size
                if (total > 0) progress?.invoke(written.toFloat() / total)
            } catch (e: Exception) {
                error = e.message ?: "خطای نوشتن"
            }
        }

        @JavascriptInterface
        fun done() {
            latch?.countDown()
        }

        @JavascriptInterface
        fun fail(msg: String) {
            error = msg
            latch?.countDown()
        }
    }
}
