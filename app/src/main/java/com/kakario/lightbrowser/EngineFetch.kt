package com.kakario.lightbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream

/**
 * 브라우저 엔진으로 이미지 받기 (앱이 직접 요청하면 막히는 경우의 마지막 방법).
 * 보이지 않는 웹뷰로 이미지 주소를 열고(브라우저와 똑같은 요청·쿠키·보호 장치 통과),
 * 열린 이미지를 같은 출처 안에서 읽어 파일로 저장한다. 한 번에 하나씩 차례로.
 */
@SuppressLint("StaticFieldLeak")
object EngineFetch {
    private class Task(val j: GrabJob, val url: String, val referer: String?, val done: (File?, String) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Task>()
    private var web: WebView? = null
    private var cur: Task? = null
    private var token = 0
    private var lastType = ""
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    /** 받을 이미지를 줄 세움. done은 메인 스레드에서 (파일 또는 null, 실패 이유) */
    fun enqueue(j: GrabJob, url: String, referer: String?, done: (File?, String) -> Unit) {
        main.post {
            queue.addLast(Task(j, url, referer, done))
            next()
        }
    }

    private fun next() {
        if (cur != null) return
        var t: Task? = queue.removeFirstOrNull()
        while (t != null && t.j.canceled) t = queue.removeFirstOrNull()
        if (t == null) {
            scheduleIdle()
            return
        }
        main.removeCallbacks(idle)
        cur = t
        token++
        lastType = ""
        val tk = token
        val w = web ?: create()
        w.settings.userAgentString = t.j.ua   // 같은 브라우저 정보여야 보호 장치 통과 쿠키가 맞음
        main.postDelayed({
            if (token == tk && cur === t) finish(null, "25초 안에 받지 못함" + if (lastType.isNotEmpty()) " (열린 페이지 형식: $lastType)" else "")
        }, 25_000)
        val headers = HashMap<String, String>()
        t.referer?.let { headers["Referer"] = it }
        w.loadUrl(t.url, headers)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.loadsImagesAutomatically = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        // 화면에 붙이지 않으므로 크기를 직접 정해 둠
        w.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
        )
        w.layout(0, 0, 1080, 1920)
        w.addJavascriptInterface(Bridge(), "__lbfetch")
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {}
            override fun onPageFinished(view: WebView, url: String?) {
                if (cur != null && url != "about:blank") inject(view)
            }
        }
        web = w
        return w
    }

    /**
     * 열린 문서가 이미지면 같은 주소를 다시 읽어(브라우저 캐시) 데이터로 넘김.
     * 보호 장치 확인 화면(웹페이지)이면 최대 15초까지 이미지로 바뀌길 기다림.
     */
    private fun inject(w: WebView) {
        val js = "(function(T){function go(n){var ct=document.contentType||'';__lbfetch.type(T,ct+' / '+(document.title||''));" +
            "if(ct.indexOf('image')!=0){if(n<30){setTimeout(function(){go(n+1)},500);return;}" +
            "__lbfetch.fail(T,'이미지 대신 웹페이지가 열림: '+ct+(document.title?' \"'+document.title+'\"':''));return;}" +
            "fetch(location.href,{cache:'force-cache',credentials:'include'}).then(function(r){if(!r.ok)throw 'HTTP '+r.status;return r.blob();})" +
            ".then(function(b){var f=new FileReader();f.onloadend=function(){__lbfetch.done(T,f.result||'')};" +
            "f.onerror=function(){__lbfetch.fail(T,'읽기 실패')};f.readAsDataURL(b);})" +
            ".catch(function(e){__lbfetch.fail(T,'다시 읽기 실패: '+e);});}go(0);})('" + token + "')"
        w.evaluateJavascript(js, null)
    }

    /** 페이지 스크립트가 결과를 넘겨주는 통로 (자바스크립트 스레드에서 불림) */
    private class Bridge {
        @JavascriptInterface
        fun type(tk: String, t: String) {
            if (tk == token.toString()) lastType = t
        }

        @JavascriptInterface
        fun done(tk: String, data: String) {
            val t = cur ?: return
            if (tk != token.toString()) return
            // 데이터 변환은 여기(백그라운드 스레드)에서
            var file: File? = null
            var msg = "ok"
            try {
                val comma = data.indexOf(',')
                if (comma < 0) throw IllegalStateException("데이터 형식 이상")
                val bytes = Base64.decode(data.substring(comma + 1), Base64.DEFAULT)
                if (bytes.isEmpty()) throw IllegalStateException("빈 이미지")
                val f = File(File(t.j.dir, "eng").apply { mkdirs() }, "e" + System.nanoTime())
                FileOutputStream(f).use { it.write(bytes) }
                file = f
            } catch (e: Exception) {
                msg = "저장 실패: ${e.message}"
            }
            main.post { if (cur === t && tk == token.toString()) finish(file, msg) }
        }

        @JavascriptInterface
        fun fail(tk: String, m: String) {
            main.post { if (tk == token.toString()) finish(null, m) }
        }
    }

    private fun finish(file: File?, msg: String) {
        val t = cur ?: return
        cur = null
        token++   // 늦게 오는 응답 무시
        try {
            t.done(file, msg)
        } catch (e: Exception) {
        }
        web?.loadUrl("about:blank")
        main.post { next() }
    }

    // 한동안 쓰지 않으면 웹뷰 정리
    private val idle = Runnable {
        if (cur == null && queue.isEmpty()) {
            web?.destroy()
            web = null
        }
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, 60_000)
    }
}
