package com.kakario.lightbrowser

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap

/** 작업 목록 화면에 보여줄 모으기 작업 정보 */
class CollectInfo(
    val title: String, val status: String, val paused: Boolean,
    val goTo: () -> Unit, val resume: () -> Unit, val end: () -> Unit, val cancel: () -> Unit,
)

/**
 * 페이지 넘기며 모으기 (탭마다 따로, 여러 개 동시에).
 * 1페이지에서 시작 → '다음 페이지로 넘기는 곳'을 꾸욱 → 앱이 그 자리를 대신 눌러 가며
 * 화면에 크게 보이는 이미지를 찾고, 찾는 즉시 받는다(이미지 주소가 시간이 지나 만료되기 전에).
 * 더 넘어가지 않으면 끝내지 않고 '멈춤' 상태로 두어 이어서 넘길 수 있게 한다.
 */
class Collector(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())

    init {
        CollectSaves.init(a)
        // 받고 보니 이미 받은 그림과 같으면: 화면만 바뀌고 실제로는 안 넘어간 것일 수 있음
        JobStore.onDuplicate = { g, same -> jobs.firstOrNull { it.grab === g }?.let { onDup(it, same) } }
    }

    inner class Job(val wv: WebView) {
        var auto = false
        var running = false
        var paused = false
        var stopReason = ""
        var tapX = 0f
        var tapY = 0f
        val startPage: String? = wv.url
        val found = LinkedHashSet<String>()
        var skipped = 0
        var lastNewAt = 0L
        var lastTapAt = 0L
        var failedTaps = 0
        var lastSig = ""        // 지금 화면(주소+보이는 이미지) — 바뀌면 넘어간 걸로 봄
        var lastNetAt = 0L      // 페이지가 마지막으로 이미지를 받기 시작한 때
        var autoRetries = 0     // 멈춘 뒤 스스로 다시 시도한 횟수
        var dupStreak = 0       // 연달아 받은 '이미 받은 그림'
        var lastPics = 0
        var catchingUp = false  // 저장한 데서 이어 받는 중: 이미 받은 장은 건너뛰며 따라잡기
        var sigOnly = 0         // 새 주소 없이 화면만 바뀐 횟수 (연속)
        val foundNorm = HashSet<String>()   // 주소 꼬리를 뗀 그림 파일 주소
        var lastGrabAt = 0L     // 주소 없는 그림을 마지막으로 꺼낸 때
        var grabbing = false
        val shot = HashSet<String>()     // 화면을 찍어 둔 그림 주소
        val shotHashes = ArrayList<Long>()   // 찍은 화면 그림 지문들
        var sameCheckTap = 0L
        var rewound = false     // 저장한 곳보다 앞에서 이어 받는 중
        var cursor = -1         // 지금 화면이 이미 받은 몇 번째 장인지 (빈 칸 채우기용)
        val saver = Runnable { saveNow(this) }
        var goodMethod = -1     // 이 사이트에서 통한 넘기기 방법
        var lastMethod = 0
        var pages = 1
        var text = ""
        var grab: GrabJob? = null

        fun title() = (wv.title?.takeIf { it.isNotBlank() } ?: Uri.parse(startPage ?: "").host ?: "images").trim()

        val loop = Runnable { step(this) }
    }

    private val jobs = mutableListOf<Job>()
    private var pending: Job? = null          // 넘기는 자리를 기다리는 중

    /** 웹뷰별 받기 작업 (이미지 요청 머리말을 모으는 데 씀, 다른 스레드에서도 읽음) */
    private val grabByView = ConcurrentHashMap<Int, GrabJob>()

    // 아래 막대 (지금 보는 탭의 작업 상태)
    private var bar: View? = null
    private var barText: TextView? = null
    private var barButtons: LinearLayout? = null
    private var marker: View? = null
    private var barFor: Job? = null

    val waitingForPoint get() = pending != null && pending?.wv === a.currentWebView()

    fun holds(w: WebView?) = w != null && jobs.any { it.wv === w && it.running }

    private fun jobOf(w: WebView?) = jobs.firstOrNull { it.wv === w }

    /** 웹 엔진이 그 페이지를 '보이는 페이지'로 다루는지: 앱에서 보고 있거나 미니 창에 떠 있으면 */
    private fun onScreen(j: Job) = (a.isShown && a.currentWebView() === j.wv) || a.mini.hosts(j.wv)

    private fun screenOn() = (a.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

    /** 기록에 남길 지금 상태 */
    private fun where(j: Job) = when {
        !screenOn() -> "화면 꺼짐"
        a.isShown && a.currentWebView() === j.wv -> "화면에 보임"
        a.mini.hosts(j.wv) -> "미니 창"
        a.isShown -> "다른 탭"
        else -> "앱 밖(홈·다른 앱)"
    }

    private fun log(j: Job, msg: String) = j.grab?.addLog(msg)

    private val methodName = arrayOf("실제 터치", "페이지 안 클릭", "페이지 안 터치 신호")

    /** 미니 창으로 옮길 작업들 (돌아가는 것 + 멈춘 것) */
    fun miniPages(): List<WebView> = jobs.map { it.wv }

    fun miniLabels(): Map<WebView, String> = jobs.associate { j ->
        j.wv to (if (j.paused) "멈춤 · " else "화면 켜둠 · ") + "${j.found.size}장 · 받음 ${j.grab?.pics?.size ?: 0}"
    }

    fun hasJobs() = jobs.isNotEmpty()

    // ================= 이미지 요청 머리말 기록 =================

    private val imgPath = Regex("\\.(jpe?g|png|gif|webp|bmp|avif|jfif)$", RegexOption.IGNORE_CASE)

    /**
     * 모으는 중인 탭이 이미지를 불러올 때: 앱이 대신 받아서 페이지에 넘겨 주고, 같은 내용을 저장해 둔다.
     * 그래서 나중에 서버에 다시 요청하지 않아도 되고(만료·차단 걱정 없음), 실패하면 웹뷰가 원래대로 받는다.
     * (shouldInterceptRequest에서 부름: 다른 스레드)
     */
    fun intercept(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        val g = grabByView[System.identityHashCode(view)] ?: return null
        if (req.isForMainFrame || !req.method.equals("GET", true)) return null
        val url = req.url.toString()
        if (!url.startsWith("http")) return null
        val accept = req.requestHeaders["Accept"] ?: req.requestHeaders["accept"] ?: ""
        if (!accept.contains("image") && !imgPath.containsMatchIn(req.url.path ?: "")) return null
        if (g.headers.size < 8000) g.headers.putIfAbsent(url, HashMap(req.requestHeaders))
        jobs.firstOrNull { it.grab === g }?.lastNetAt = SystemClock.uptimeMillis()
        // 같은 주소도 매번 대신 받음: 같은 주소에 다른 그림이 오는 사이트에서 원본을 잡기 위해
        return JobStore.interceptImage(g, url, req.requestHeaders)
    }

    // ================= 시작 =================

    fun begin() {
        val w = a.currentWebView()
        if (w == null) {
            a.toastMsg("이미지를 보는 페이지(1페이지)에서 시작해 주세요")
            return
        }
        if (jobOf(w) != null) {
            a.toastMsg("이 탭은 이미 모으는 중이에요")
            refreshBar()
            return
        }
        pending?.let { cancelJob(it) }
        val j = Job(w)
        pending = j
        snap(j)   // 1페이지 이미지 먼저
        refreshBar()
        a.askBackgroundPermissions()
    }

    /** 사용자가 넘기는 자리를 꾸욱 눌렀을 때 (웹뷰 좌표) */
    fun setPoint(x: Float, y: Float) {
        val j = pending ?: return
        j.tapX = x
        j.tapY = y
        startRunning(j, autoTap = true)
    }

    private fun startRunning(j: Job, autoTap: Boolean) {
        if (pending === j) pending = null
        j.auto = autoTap
        // 받기 작업을 먼저 만들고, 지금까지 찾은 이미지부터 바로 받기
        val g = JobStore.startCollecting(j.title(), j.startPage, j.wv.settings.userAgentString)
        j.grab = g
        g.pageTitle = j.wv.title
        grabByView[System.identityHashCode(j.wv)] = g
        g.addLog("모으기 시작 · " + (if (autoTap) "자동으로 넘김 (누르는 자리 ${j.tapX.toInt()},${j.tapY.toInt()})" else "직접 넘김") +
            " · 페이지 ${j.wv.url}")
        j.found.forEach { JobStore.add(g, it) }
        jobs.add(j)
        run(j)
    }

    private fun run(j: Job) {
        j.running = true
        j.paused = false
        j.stopReason = ""
        j.failedTaps = 0
        j.lastNewAt = SystemClock.uptimeMillis()
        j.lastTapAt = 0L
        j.wv.onResume()
        updateText(j)
        report()
        refreshBar()
        main.removeCallbacks(j.loop)
        main.postDelayed(j.loop, if (j.auto) 900 else 600)
    }

    // ================= 반복 =================

    private fun step(j: Job) {
        if (!j.running) return
        if (!a.hasWebView(j.wv)) {
            endJob(j)   // 모으던 탭이 닫힘
            return
        }
        snap(j) {
            if (!j.running) return@snap
            val now = SystemClock.uptimeMillis()
            val pics = j.grab?.pics?.size ?: 0
            if (pics > j.lastPics) {
                // 새 그림을 실제로 받음 → 따라잡기 끝, 중복 연속 초기화
                j.lastPics = pics
                j.dupStreak = 0
                if (j.catchingUp) {
                    j.catchingUp = false
                    log(j, "이미 받은 데까지 따라잡음 · 여기서부터 새로 받아요")
                }
            }
            // 새 주소 없이 화면만 계속 바뀌는데 새 그림이 안 늘면 '안 넘어가는 것'
            if (!j.catchingUp && j.sigOnly >= 8) {
                pause(j, "화면은 바뀌는데 새 그림이 안 나와요 (같은 장을 반복하는 것 같아요)")
                return@snap
            }
            if (j.auto) {
                // 화면 밖에서는 늦게 뜨므로 더 기다림. 실패할수록 더 오래 기다림 (서버가 느려지는 경우)
                val base = if (onScreen(j)) 6000 else 10000
                val waitLimit = (base + j.failedTaps * 5000L).coerceAtMost(30000L)
                // 넘긴 뒤 이미지를 받는 중이면(4초 안에 요청) 실패로 세지 않고 더 기다림
                val loading = j.lastNetAt > j.lastTapAt && now - j.lastNetAt < 4000
                when {
                    // 새 이미지가 나오고 0.8초 지나면 다음 장
                    j.lastNewAt > j.lastTapAt && now - j.lastNewAt > 800 -> {
                        if (j.lastTapAt != 0L) {
                            j.goodMethod = j.lastMethod
                            j.pages++
                        }
                        j.failedTaps = 0
                        tap(j)
                    }
                    j.lastTapAt == 0L || (now - j.lastTapAt > waitLimit && (!loading || now - j.lastTapAt > 45000)) -> {
                        if (j.lastTapAt != 0L) j.failedTaps++
                        val limit = if (onScreen(j)) 5 else 7
                        if (j.failedTaps >= limit) {
                            pause(j, "${j.failedTaps}번 넘겨도 새 이미지가 안 나와요")
                            return@snap
                        }
                        // 두 번째부터는 화면을 살짝 움직여 늦게 뜨는 이미지를 깨움
                        if (j.failedTaps >= 1) nudge(j)
                        tap(j)
                    }
                }
            }
            main.postDelayed(j.loop, 450)
        }
    }

    private fun nudge(j: Job) {
        j.wv.evaluateJavascript(
            "(function(){try{window.scrollBy(0,2);setTimeout(function(){window.scrollBy(0,-2);},120);" +
                "window.dispatchEvent(new Event('scroll'));window.dispatchEvent(new Event('resize'));}catch(e){}})()", null
        )
    }

    private fun tap(j: Job) {
        val w = j.wv
        j.lastTapAt = SystemClock.uptimeMillis()
        // 화면에 보이면 실제 터치. 안 보이면(다른 탭·다른 앱·화면 꺼짐) 통했던 방법 → 안 되면 바꿔 가며
        val m = when {
            onScreen(j) && j.goodMethod < 1 -> 0
            j.goodMethod >= 0 && j.failedTaps < 2 -> j.goodMethod
            else -> j.failedTaps % 3
        }
        j.lastMethod = m
        log(j, "넘기기 ${j.pages}쪽 → 다음 · 방식: ${methodName[m]} · ${where(j)}" +
            if (j.failedTaps > 0) " · 새 이미지 없이 ${j.failedTaps}번째 다시 시도" else "")
        if (m == 1 || m == 2) {
            jsTap(j, m)
            return
        }
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, j.tapX, j.tapY, 0)
        w.dispatchTouchEvent(down)
        down.recycle()
        main.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, j.tapX, j.tapY, 0)
            w.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
        if (barFor === j) flashMarker()
    }

    /** 페이지 안에서 넘기는 자리의 요소를 직접 누른다 (화면에 안 보여도 동작) */
    private fun jsTap(j: Job, m: Int) {
        val w = j.wv
        val vw = w.width.coerceAtLeast(1)
        val vh = w.height.coerceAtLeast(1)
        val js = "(function(X,Y,VW,VH,M){var cx=X*innerWidth/VW,cy=Y*innerHeight/VH;" +
            "var e=document.elementFromPoint(cx,cy);if(!e)return 'none';" +
            "if(M==1){var a=e.closest&&e.closest('a[href]');" +
            "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){try{var ev=(t.indexOf('pointer')==0&&window.PointerEvent)?" +
            "new PointerEvent(t,{bubbles:true,cancelable:true,clientX:cx,clientY:cy,pointerType:'touch',isPrimary:true}):" +
            "new MouseEvent(t,{bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window});e.dispatchEvent(ev);}catch(x){}});" +
            "if(a&&!e.onclick)a.click();else e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window}));}" +
            "else{try{var t=new Touch({identifier:Date.now(),target:e,clientX:cx,clientY:cy});" +
            "e.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[t],targetTouches:[t],changedTouches:[t]}));" +
            "e.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[t]}));}catch(x){}}" +
            "return e.tagName;})(" + j.tapX + "," + j.tapY + "," + vw + "," + vh + "," + m + ")"
        w.evaluateJavascript(js, null)
    }

    /** 화면에 크게 보이는 이미지 주소를 모으고, 새로 찾은 건 바로 받기 시작 */
    private fun snap(j: Job, after: (() -> Unit)? = null) {
        // 화면에 가장 크게 보이는 그림(들)만 고름: 옆의 배경·광고·안내 그림을 잘못 집지 않게
        // 주소가 없는 그림(blob·data·canvas)은 'grab:번호'로 표시해 두고 따로 꺼냄
        val js = "(function(){var o=[],c=[],W=innerWidth,H=innerHeight;" +
            "function area(r){var w=Math.min(r.right,W)-Math.max(r.left,0),h=Math.min(r.bottom,H)-Math.max(r.top,0);return w>0&&h>0?w*h:0;}" +
            "document.querySelectorAll('img,canvas').forEach(function(e){var A=area(e.getBoundingClientRect());if(A<W*H*0.12)return;" +
            "if(e.tagName=='CANVAS')c.push({a:A,e:e,s:'canvas'});else{var s=e.currentSrc||e.src;if(s)c.push({a:A,e:e,s:s});}});" +
            "try{document.elementsFromPoint(W/2,H/2).forEach(function(e){var A=area(e.getBoundingClientRect());if(A<W*H*0.12)return;" +
            "var b=getComputedStyle(e).backgroundImage;var m=b&&b.match(/url\\([\"']?([^\"')]+)/);" +
            "if(m)c.push({a:A,e:null,s:new URL(m[1],location.href).href});});}catch(e){}" +
            "var mx=0;c.forEach(function(x){if(x.a>mx)mx=x.a;});window.__lbG=[];" +
            "c.forEach(function(x){if(x.a<mx*0.5)return;if(/^https?:/.test(x.s))o.push(x.s);" +
            "else if(x.e){window.__lbG.push(x.e);o.push('grab:'+(window.__lbG.length-1)+':'+(x.s.indexOf('data:')==0?'data':x.s));}});" +
            "o.push('sig:'+location.href+'|'+o.join('|'));" +
            // 가장 큰 그림의 위치 (화면과 비교용)
            "var b=null;c.forEach(function(x){if(x.e&&x.e.tagName=='IMG'&&(!b||x.a>b.a))b=x;});" +
            "if(b){var r=b.e.getBoundingClientRect();o.push('main:'+JSON.stringify({s:b.s,x:r.left,y:r.top,w:r.width,h:r.height,iw:W,ih:H," +
            "ok:b.e.complete&&b.e.naturalWidth>0}));}" +
            "return JSON.stringify(o);})()"
        j.wv.evaluateJavascript(js) { res ->
            try {
                val str = JSONTokener(res).nextValue() as? String
                if (str != null) {
                    val arr = JSONArray(str)
                    for (i in 0 until arr.length()) {
                        val u = arr.getString(i)
                        if (u.startsWith("sig:")) {
                            // 새 이미지는 아니어도 화면이 바뀌었으면 넘어간 것 (이미 본 이미지를 다시 보여주는 뷰어 등)
                            if (j.lastSig.isNotEmpty() && u != j.lastSig && u.length > 8 && j.lastTapAt > 0 && j.lastNewAt <= j.lastTapAt) {
                                // 새 주소는 없지만 화면이 바뀜 → 일단 넘어간 걸로 보고, 받은 그림이 중복이면 다시 판단
                                j.lastNewAt = SystemClock.uptimeMillis()
                                j.sigOnly++
                                log(j, "새 주소 없이 화면만 바뀜 (${j.sigOnly}번째 연속) · 이미 받은 장이면 건너뜀")
                            }
                            j.lastSig = u
                        } else if (u.startsWith("main:")) {
                            shoot(j, u.substring(5))
                        } else if (u.startsWith("grab:")) {
                            grabPixels(j, u)
                        } else if (u.startsWith("http")) {
                            // 같은 그림 파일인데 주소 뒤 꼬리(?...)만 바뀐 건 같은 걸로 봄
                            val norm = Uri.parse(u).let { p -> if (imgPath.containsMatchIn(p.path ?: "")) (p.host + p.path) else u }
                            if (norm != u && !j.foundNorm.add(norm)) continue
                            if (j.found.add(u)) {
                                j.lastNewAt = SystemClock.uptimeMillis()
                                j.autoRetries = 0
                                j.sigOnly = 0
                                scheduleSave(j)
                                log(j, "새 이미지 ${j.found.size}번째 찾음 (${where(j)}) $u")
                                j.grab?.let { g -> if (j.running) JobStore.add(g, u, fillSlot(j)) }
                            } else if (j.rewound) {
                                // 이미 받은 장 → 지금 위치만 기억
                                j.grab?.urls?.indexOf(u)?.takeIf { it >= 0 }?.let { j.cursor = it }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
            if (j.running) {
                updateText(j)
                report()
                if (barFor === j) barText?.text = j.text
            }
            after?.invoke()
        }
    }

    /**
     * 화면에 실제로 보이는 가장 큰 그림을 찍어서, 받은 파일이 그 그림과 같은지 나중에 비교하게 함.
     * 앱에서 그 탭을 보고 있을 때만 (화면 밖에서는 찍을 수 없음)
     */
    private fun shoot(j: Job, json: String) {
        val g = j.grab ?: return
        if (!j.running || !a.isShown || a.currentWebView() !== j.wv) return
        val o = try { org.json.JSONObject(json) } catch (e: Exception) { return }
        val src = o.optString("s")
        if (!src.startsWith("http") || !o.optBoolean("ok") || !j.found.contains(src)) return
        val already = src in j.shot
        // 이미 찍은 주소: 넘긴 뒤 한 번만, 주소는 그대로인데 그림만 바뀌었는지 확인
        if (already && (j.catchingUp || j.lastTapAt == 0L || j.sameCheckTap == j.lastTapAt || j.lastNewAt > j.lastTapAt)) return
        val iw = o.optDouble("iw", 0.0)
        val ih = o.optDouble("ih", 0.0)
        val x = o.optDouble("x"); val y = o.optDouble("y"); val w = o.optDouble("w"); val h = o.optDouble("h")
        // 그림 전체가 화면 안에 있을 때만 (길게 이어진 웹툰은 비교 안 함)
        if (iw <= 0 || w < 50 || h < 50 || x < -2 || y < -2 || x + w > iw + 2 || y + h > ih + 2) return
        if (already) j.sameCheckTap = j.lastTapAt else j.shot.add(src)
        val tapAt = j.lastTapAt
        val wv = j.wv
        val k = wv.width / iw
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        val rect = android.graphics.Rect((loc[0] + x * k).toInt(), (loc[1] + y * k).toInt(),
            (loc[0] + (x + w) * k).toInt(), (loc[1] + (y + h) * k).toInt())
        if (rect.width() <= 0 || rect.height() <= 0) return
        // 이미지가 막 바뀐 순간을 피하려고 잠깐 뒤에 찍음
        main.postDelayed({
            if (!j.running || a.currentWebView() !== wv || !a.isShown) { if (!already) j.shot.remove(src); return@postDelayed }
            try {
                val bmp = android.graphics.Bitmap.createBitmap(rect.width(), rect.height(), android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(a.window, rect, bmp, { res ->
                    if (res != android.view.PixelCopy.SUCCESS) { bmp.recycle(); if (!already) j.shot.remove(src); return@request }
                    Thread {
                        val hash = JobStore.dHash(bmp)
                        val out = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        bmp.recycle()
                        val jpg = out.toByteArray()
                        val orig = if (already) JobStore.bestOriginal(g, hash) else null
                        main.post { shotTaken(j, g, src, already, tapAt, hash, jpg, orig) }
                    }.start()
                }, main)
            } catch (e: Throwable) {
                if (!already) j.shot.remove(src)
            }
        }, 350)
    }

    private fun shotTaken(j: Job, g: GrabJob, src: String, already: Boolean, tapAt: Long, hash: Long, jpg: ByteArray,
                          orig: GrabJob.Recent?) {
        val near = j.shotHashes.any { java.lang.Long.bitCount(it xor hash) <= 10 }
        j.shotHashes.add(hash)
        if (!already) {
            // 새 주소의 그림: 받은 파일이 이 화면과 같은지 비교
            JobStore.expectShot(g, src, hash, jpg)
            return
        }
        // 주소는 그대로인데 화면 그림이 처음 보는 그림 → 새 장으로 화면을 저장
        if (near || !j.running || j.lastNewAt > tapAt) return
        j.found.add("screen:$hash")
        j.lastNewAt = SystemClock.uptimeMillis()
        j.autoRetries = 0
        j.sigOnly = 0
        scheduleSave(j)
        log(j, "주소는 그대로인데 화면 그림이 바뀜 → " + (if (orig != null) "페이지가 받은 원본 화질로" else "화면 캡처로") +
            " 저장 (${j.found.size}번째)")
        if (orig != null) JobStore.addBytes(g, orig.bytes, orig.ext, fillSlot(j)) else JobStore.addBytes(g, jpg, "jpg", fillSlot(j))
    }

    /**
     * 주소가 없는 그림(blob·data 이미지, canvas): 페이지 안에서 그림 내용을 직접 꺼내 저장.
     * blob 주소는 그림마다 달라서 이미 꺼낸 건 건너뛰고, canvas는 내용 지문으로 새 그림인지 판단.
     */
    private fun grabPixels(j: Job, tag: String) {
        val g = j.grab ?: return
        if (!j.running || j.grabbing) return
        val parts = tag.split(":", limit = 3)
        val k = parts.getOrNull(1)?.toIntOrNull() ?: return
        val src = parts.getOrNull(2) ?: ""
        if (src.startsWith("blob:") && j.found.contains(src)) return
        val now = SystemClock.uptimeMillis()
        // canvas·data는 매번 꺼내면 무거우니 넘긴 뒤에만, 너무 자주는 말고
        if (!src.startsWith("blob:") && (now - j.lastGrabAt < 1200 || (j.lastTapAt > 0 && j.lastGrabAt > j.lastTapAt + 2500))) return
        j.grabbing = true
        j.lastGrabAt = now
        val js = "(function(k){var e=window.__lbG&&window.__lbG[k];if(!e)return '';try{" +
            "if(e.tagName=='CANVAS')return e.toDataURL('image/png');var s=e.currentSrc||e.src;if(s.indexOf('data:')==0)return s;" +
            "if(!e.naturalWidth)return '';var cv=document.createElement('canvas');cv.width=e.naturalWidth;cv.height=e.naturalHeight;" +
            "cv.getContext('2d').drawImage(e,0,0);return cv.toDataURL('image/jpeg',0.95);}catch(x){return 'ERR:'+x;}})(" + k + ")"
        j.wv.evaluateJavascript(js) { res ->
            val data = try { JSONTokener(res).nextValue() as? String } catch (e: Exception) { null } ?: ""
            if (data.startsWith("ERR:")) {
                j.grabbing = false
                if (j.skipped++ < 3) log(j, "주소 없는 그림을 꺼내지 못함 (사이트가 막음) · ${data.take(120)}")
                return@evaluateJavascript
            }
            if (!data.startsWith("data:image")) { j.grabbing = false; return@evaluateJavascript }
            val ext = when {
                data.startsWith("data:image/png") -> "png"
                data.startsWith("data:image/webp") -> "webp"
                data.startsWith("data:image/gif") -> "gif"
                else -> "jpg"
            }
            Thread {
                val bytes = try { android.util.Base64.decode(data.substringAfter(','), android.util.Base64.DEFAULT) } catch (e: Exception) { null }
                val key = bytes?.let { b ->
                    "pix:" + java.security.MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
                }
                main.post {
                    j.grabbing = false
                    if (bytes == null || key == null || bytes.size < 2000 || !j.running) return@post
                    if (src.startsWith("blob:")) j.found.add(src)
                    if (!j.found.add(key)) return@post
                    j.lastNewAt = SystemClock.uptimeMillis()
                    j.autoRetries = 0
                    j.sigOnly = 0
                    scheduleSave(j)
                    log(j, "주소 없는 그림 꺼냄 ${j.found.size}번째 (${if (src.startsWith("blob:")) "blob" else src}) · ${bytes.size / 1024}KB")
                    JobStore.addBytes(g, bytes, ext, fillSlot(j))
                }
            }.start()
        }
    }

    private fun updateText(j: Job) {
        val g = j.grab
        val dl = if (g != null) " · 받음 ${g.pics.size}" + (if (g.fails > 0) " (실패 ${g.fails})" else "") else ""
        j.text = when {
            j.paused -> "멈췄어요: ${j.stopReason}\n${j.found.size}장 찾음$dl · 받기는 계속돼요"
            j.catchingUp -> "저장한 데까지 따라잡는 중 · 이미 받은 장은 건너뛰어요 · ${j.found.size}장$dl"
            j.auto -> "넘기며 모으는 중 · ${j.found.size}장 찾음$dl\n모으는 동안 화면이 꺼지지 않아요"
            else -> "직접 넘기는 동안 모으는 중 · ${j.found.size}장 찾음$dl\n모으는 동안 화면이 꺼지지 않아요"
        } + if (j.skipped > 0 && j.found.isEmpty()) "\n(이 사이트 이미지는 서버 주소가 없어 받을 수 없을 수 있어요)" else ""
    }

    /** 진행 상황을 알림·작업 아이콘에 알림 */
    private fun report() {
        // 돌아가는 페이지는 웹 엔진에 계속 '보이는 중'으로, 멈추거나 끝난 건 원래대로
        jobs.forEach { (it.wv as? LbWebView)?.keepVisible = it.running }
        val anyRunning = jobs.any { it.running }
        a.setKeepScreenOn(anyRunning)
        a.parkCollecting()
        BgWork.collectJobs = jobs.count { it.running }
        BgWork.collectImages = jobs.filter { it.running }.sumOf { it.found.size }
        BgWork.sync(a)
        a.onJobsChanged()
        if (a.mini.isShowing) a.mini.updateLabels(miniLabels())
    }

    // ================= 멈춤·이어하기·끝 =================

    /** 더 넘어가지 않을 때: 끝내지 않고 멈춤 (이미 찾은 건 계속 받음) */
    private fun pause(j: Job, reason: String) {
        j.running = false
        j.paused = true
        j.stopReason = reason
        log(j, "멈춤 · $reason · ${where(j)} · 지금 페이지 ${j.wv.url}")
        main.removeCallbacks(j.loop)
        saveNow(j)
        updateText(j)
        report()
        refreshBar()
        // 서버가 잠깐 느려졌을 수 있으니 30초 뒤 스스로 다시 시도 (연속 3번까지)
        if (j.autoRetries < 3) {
            j.autoRetries++
            log(j, "30초 뒤 다시 넘겨 볼게요 (${j.autoRetries}/3)")
            j.text += "\n30초 뒤 다시 시도해요 (${j.autoRetries}/3)"
            if (barFor === j) barText?.text = j.text
            main.postDelayed({ if (jobs.contains(j) && j.paused) resume(j) }, 30000)
            return
        }
        if (!onScreen(j)) BgWork.collectPaused(a, j.title(), j.found.size)
    }

    fun resume(j: Job) {
        if (!jobs.contains(j) || j.running) return
        j.sigOnly = 0
        j.dupStreak = 0
        log(j, "이어 넘기기 · ${where(j)}")
        run(j)
    }

    /** 여기까지로 끝: 모으기를 마치고 남은 받기만 마저 함 */
    private fun endJob(j: Job) {
        if (!jobs.contains(j)) return
        val wasOnScreen = onScreen(j)
        j.running = false
        j.paused = false
        main.removeCallbacks(j.loop)
        jobs.remove(j)
        (j.wv as? LbWebView)?.keepVisible = false
        grabByView.remove(System.identityHashCode(j.wv))
        val g = j.grab
        main.removeCallbacks(j.saver)
        g?.let { CollectSaves.delete(it.id) }
        log(j, "모으기 끝 · 찾은 이미지 ${j.found.size}장")
        refreshBar()
        if (g != null) {
            JobStore.endCollecting(g)   // 받기 작업이 남아 있으면 계속 (알림 유지를 위해 먼저)
            if (wasOnScreen && JobStore.get(g.id) != null) a.openGrabJob(g)
        }
        report()
        if (g == null || g.total == 0) {
            if (a.isShown) a.toastMsg("모은 이미지가 없어요")
        }
    }

    private fun cancelJob(j: Job) {
        main.removeCallbacks(j.saver)
        j.grab?.let { CollectSaves.delete(it.id) }
        j.running = false
        main.removeCallbacks(j.loop)
        jobs.remove(j)
        (j.wv as? LbWebView)?.keepVisible = false
        if (pending === j) pending = null
        grabByView.remove(System.identityHashCode(j.wv))
        j.grab?.let { JobStore.remove(it) }
        report()
        refreshBar()
    }

    /** 모으던 탭이 닫히려 할 때 */
    fun tabClosing(w: WebView) {
        jobOf(w)?.let { endJob(it) }
        if (pending?.wv === w) pending = null
    }

    /** 뒤로가기: 넘기는 자리 기다리는 중이면 취소 */
    fun back(): Boolean {
        val p = pending ?: return false
        if (p.wv !== a.currentWebView()) return false
        cancelJob(p)
        return true
    }

    fun info(): List<CollectInfo> = jobs.map { j ->
        CollectInfo(
            j.title(),
            j.text.replace("\n", " ") + if (a.currentWebView() === j.wv) " · 지금 보는 탭" else "",
            j.paused,
            goTo = { a.selectTabOf(j.wv) },
            resume = { resume(j) },
            end = { endJob(j) },
            cancel = { cancelJob(j) },
        )
    }

    // ================= 저장·이어 받기 =================

    /** 앞에서 이어 받을 때: 지금 위치 바로 뒤가 실패했던 칸이면 그 칸을 채움 (순서 유지) */
    private fun fillSlot(j: Job): Int? {
        val g = j.grab ?: return null
        if (!j.rewound || j.cursor < 0) return null
        var k = j.cursor + 1
        while (k < g.slots.size && k in g.dupSlots) k++
        if (k < g.slots.size && k in g.failed) {
            j.cursor = k
            return k
        }
        return null
    }

    private fun onDup(j: Job, same: Int) {
        if (j.rewound) j.cursor = same
        if (j.catchingUp) return   // 이어 받기로 되돌아간 거라 중복이 당연함
        j.dupStreak++
        log(j, "받고 보니 이미 받은 그림 (${j.dupStreak}번 연속) · 넘기기가 안 됐을 수 있어 다시 확인")
        // 방금 '넘어갔다'고 본 게 사실은 같은 그림이었음 → 그 판단을 취소하고 다시 넘김
        if (j.running && j.lastNewAt > j.lastTapAt) j.lastNewAt = j.lastTapAt - 1
        if (j.running && j.dupStreak >= 6) pause(j, "같은 그림만 계속 받아져요 (주소만 바뀌고 그림은 그대로)")
    }

    private fun scheduleSave(j: Job) {
        main.removeCallbacks(j.saver)
        main.postDelayed(j.saver, 4000)
    }

    private fun saveNow(j: Job) {
        main.removeCallbacks(j.saver)
        val g = j.grab ?: return
        if (!jobs.contains(j)) return
        CollectSaves.write(g, j.wv.url, j.tapX, j.tapY, j.auto, j.goodMethod, j.pages, j.found)
    }

    /** 앱을 나갈 때 등: 지금 상태 저장 */
    fun saveAll() = jobs.forEach { saveNow(it) }

    /** 저장하고 그만: 지금까지 상태를 남기고 멈춤 (나중에 '이어 받기') */
    private fun saveAndStop(j: Job) {
        saveNow(j)
        j.running = false
        j.paused = false
        main.removeCallbacks(j.loop)
        jobs.remove(j)
        (j.wv as? LbWebView)?.keepVisible = false
        grabByView.remove(System.identityHashCode(j.wv))
        j.grab?.let { JobStore.detach(it) }
        report()
        refreshBar()
        android.app.AlertDialog.Builder(a)
            .setTitle("저장했어요")
            .setMessage("앱을 껐다 켜도 괜찮아요.\n메뉴 › 넘기며 모으기를 누르거나 앱을 다시 켜면 '이어 받을까요?'가 떠요.\n\n이어 받을 때 몇 장 앞에서 시작해도, 이미 받은 장은 알아서 건너뛰어요.")
            .setPositiveButton("확인", null)
            .show()
    }

    private fun savedList() = CollectSaves.list().filter { s -> jobs.none { it.grab?.id == s.id } }

    /** 앱을 켤 때: 저장해 둔 게 있으면 물어봄 */
    fun askSavedOnStart() {
        val list = savedList()
        if (list.isEmpty()) return
        pickSaved(list, "저번에 받던 게 있어요. 이어 받으시겠어요?", newOption = false)
    }

    /** 메뉴 › 넘기며 모으기: 저장해 둔 게 있으면 고르게, 없으면 바로 새로 */
    fun beginOrResume() {
        val list = savedList()
        if (list.isEmpty()) {
            begin()
            return
        }
        // 지금 보는 사이트에 저장해 둔 게 있으면 바로 물어봄
        val host = Uri.parse(a.currentWebView()?.url ?: "").host
        val same = if (hereOk() && host != null) list.firstOrNull { Uri.parse(it.page).host == host } else null
        if (same != null) {
            val acts = listOf<Pair<String, () -> Unit>>(
                "여기서부터 이어 받기\n이미 받은 장은 건너뛰고 못 받은 장만 채워요" to { resumeSaved(same, here = true) },
                "이 페이지부터 새로 받기\n받은 이미지 중 같은 장을 찾아 보여주고, 그 장부터 다시 받아요" to { findHere(same) },
                "다른 저장 작업 고르기" to { pickSaved(list, "넘기며 모으기", newOption = true) },
                "새 작업으로 모으기" to { begin() },
            )
            android.app.AlertDialog.Builder(a)
                .setCustomTitle(a.titleView("'${same.title}' (${same.count}장 받아 둠)"))
                .setItems(acts.map { it.first }.toTypedArray()) { _, w -> acts[w].second() }
                .setNegativeButton("닫기", null)
                .show()
            return
        }
        pickSaved(list, "넘기며 모으기", newOption = true)
    }

    private fun pickSaved(list: List<CollectSaves.Save>, title: String, newOption: Boolean) {
        val fmt = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA)
        val items = ArrayList<String>()
        if (newOption) items.add("＋ 지금 페이지에서 새로 모으기")
        list.forEach { s -> items.add("이어 받기: ${s.title}\n${s.count}장 받음 · ${fmt.format(java.util.Date(s.savedAt))} 저장") }
        android.app.AlertDialog.Builder(a)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                val k = if (newOption) which - 1 else which
                if (k < 0) begin() else choose(list[k])
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    /** 지금 보는 탭에서 이어 받을 수 있는지 */
    private fun hereOk(): Boolean {
        val w = a.currentWebView() ?: return false
        return jobOf(w) == null && pending?.wv !== w && (w.url ?: "").startsWith("http")
    }

    fun chooseSaved(s: CollectSaves.Save) = choose(s)
    fun activeGrabIds(): Set<Long> = jobs.mapNotNull { it.grab?.id }.toSet()

    private fun choose(s: CollectSaves.Save) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (hereOk()) acts.add("지금 보는 페이지부터 이어 받기 (이미 받은 장은 건너뜀)" to { resumeSaved(s, here = true) })
        if (hereOk()) acts.add("이 페이지부터 새로 받기 (받은 것 중 같은 장을 찾아 확인)" to { findHere(s) })
        acts.add("저장한 페이지(멈췄던 곳)에서 이어 받기" to { resumeSaved(s, here = false) })
        acts.add("받은 것만 정리 (더 안 넘기고 끝)" to { finishSaved(s) })
        acts.add("버리기" to {
            android.app.AlertDialog.Builder(a)
                .setMessage("저장한 작업과 받아 둔 이미지 ${s.count}장을 지울까요?")
                .setPositiveButton("지우기") { _, _ ->
                    CollectSaves.delete(s.id)
                    val d = java.io.File(s.o.optString("dir"))
                    JobStore.get(s.id)?.let { JobStore.remove(it) } ?: Thread { d.deleteRecursively() }.start()
                }
                .setNegativeButton("취소", null).show()
        })
        android.app.AlertDialog.Builder(a)
            .setCustomTitle(a.titleView("${s.title} · ${s.count}장 받아 둠"))
            .setItems(acts.map { it.first }.toTypedArray()) { _, w -> acts[w].second() }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun finishSaved(s: CollectSaves.Save) {
        val g = JobStore.restore(s)
        if (g == null) {
            a.toastMsg("받아 둔 파일을 찾을 수 없어요")
            CollectSaves.delete(s.id)
            return
        }
        CollectSaves.delete(s.id)
        JobStore.endCollecting(g)
        if (JobStore.get(g.id) != null) a.openGrabJob(g)
    }

    // ================= 이 페이지부터 새로 받기 =================

    /** 지금 화면 그림과 가장 비슷한 '받은 이미지'를 찾아 보여주고 확인 */
    private fun findHere(s: CollectSaves.Save) {
        val w = a.currentWebView() ?: return
        val js = "(function(){var W=innerWidth,H=innerHeight,b=null,ba=0;" +
            "document.querySelectorAll('img,canvas').forEach(function(e){var r=e.getBoundingClientRect();" +
            "var x=Math.max(r.left,0),y=Math.max(r.top,0),w=Math.min(r.right,W)-x,h=Math.min(r.bottom,H)-y;" +
            "if(w>0&&h>0&&w*h>ba){ba=w*h;b={s:e.currentSrc||e.src||'',x:x,y:y,w:w,h:h,iw:W};}});" +
            "return b?JSON.stringify(b):'';})()"
        val wait = android.app.ProgressDialog(a).apply {
            setMessage("받은 이미지 중에서 이 페이지와 같은 장을 찾는 중…")
            setCancelable(false)
            show()
        }
        w.evaluateJavascript(js) { res ->
            val str = try { JSONTokener(res).nextValue() as? String } catch (e: Exception) { null }
            val o = try { if (str.isNullOrEmpty()) null else org.json.JSONObject(str) } catch (e: Exception) { null }
            if (o == null) { wait.dismiss(); a.toastMsg("이 페이지에서 그림을 찾지 못했어요"); return@evaluateJavascript }
            val src = o.optString("s")
            val k = w.width / o.optDouble("iw", 1.0)
            val loc = IntArray(2)
            w.getLocationInWindow(loc)
            val rect = android.graphics.Rect((loc[0] + o.optDouble("x") * k).toInt(), (loc[1] + o.optDouble("y") * k).toInt(),
                (loc[0] + (o.optDouble("x") + o.optDouble("w")) * k).toInt(), (loc[1] + (o.optDouble("y") + o.optDouble("h")) * k).toInt())
            if (rect.width() <= 0 || rect.height() <= 0) { wait.dismiss(); a.toastMsg("이 페이지에서 그림을 찾지 못했어요"); return@evaluateJavascript }
            val bmp = android.graphics.Bitmap.createBitmap(rect.width(), rect.height(), android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(a.window, rect, bmp, { r ->
                val hash = if (r == android.view.PixelCopy.SUCCESS) JobStore.dHash(bmp) else null
                bmp.recycle()
                Thread {
                    val best = matchSaved(s, src, hash)
                    main.post {
                        wait.dismiss()
                        if (best == null) a.toastMsg("받은 이미지 중에 비슷한 장이 없어요")
                        else confirmFrom(s, best.first, best.second)
                    }
                }.start()
            }, main)
        }
    }

    /** (받은 칸 번호(0부터), 비슷한 정도 %) — 주소가 같으면 바로, 아니면 그림 지문으로 (뒤에서부터) */
    private fun matchSaved(s: CollectSaves.Save, src: String, hash: Long?): Pair<Int, Int>? {
        val o = s.o
        val urls = o.optJSONArray("urls") ?: return null
        for (i in 0 until urls.length()) if (urls.getString(i) == src) return i to 100
        if (hash == null) return null
        val dir = java.io.File(o.optString("dir"))
        val ok = o.optJSONArray("ok") ?: return null
        var best = -1
        var bestD = 65
        for (k in ok.length() - 1 downTo 0) {
            val e = ok.getJSONObject(k)
            val f = java.io.File(dir, e.getString("f"))
            val h = smallHash(f) ?: continue
            val d = java.lang.Long.bitCount(h xor hash)
            if (d < bestD) { bestD = d; best = e.getInt("i") }
            if (d <= 3) break
        }
        if (best < 0 || bestD > 20) return null
        return best to ((64 - bestD) * 100 / 64)
    }

    private fun smallHash(f: java.io.File): Long? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 48 && o.outHeight / (sample * 2) >= 48) sample *= 2
        android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?.let { val h = JobStore.dHash(it); it.recycle(); h }
    } catch (e: Throwable) { null }

    /** 찾은 받은 이미지를 크게 보여주며 확인 (◀ ▶로 앞뒤 장 바꿔 고를 수 있음) */
    private fun confirmFrom(s: CollectSaves.Save, start: Int, sim: Int) {
        val o = s.o
        val dir = java.io.File(o.optString("dir"))
        val files = HashMap<Int, java.io.File>()
        o.optJSONArray("ok")?.let { arr -> for (k in 0 until arr.length()) arr.getJSONObject(k).let { files[it.getInt("i")] = java.io.File(dir, it.getString("f")) } }
        val idx = files.keys.sorted()
        if (idx.isEmpty()) return
        var pos = idx.indexOf(start).takeIf { it >= 0 } ?: idx.indexOfFirst { it >= start }.coerceAtLeast(0)
        val img = android.widget.ImageView(a).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        val label = TextView(a).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(a.getColor(R.color.text))
            setPadding(0, a.dp(8), 0, a.dp(4))
        }
        fun arrow(t: String, d: Int) = TextView(a).apply {
            text = t
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(18), a.dp(4), a.dp(18), a.dp(4))
            a.ripple(this)
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(8), a.dp(16), 0)
        }
        box.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.5f).toInt()))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = arrow("◀", -1)
        val next = arrow("▶", 1)
        row.addView(prev)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(next)
        box.addView(row)
        var dlg: android.app.AlertDialog? = null
        fun show() {
            val n = idx[pos]
            label.text = "받은 이미지 ${n + 1}번" + if (n == start) " · 지금 화면과 ${sim}% 비슷" else ""
            dlg?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.text = "${n + 1}번부터 새로 받기"
            img.setImageDrawable(null)
            val f = files[n] ?: return
            Thread {
                val b = try {
                    val op = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.path, op)
                    var sm = 1
                    while (op.outHeight / (sm * 2) >= 900) sm *= 2
                    android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sm })
                } catch (e: Throwable) { null }
                main.post { if (idx[pos] == n) img.setImageBitmap(b) }
            }.start()
        }
        prev.setOnClickListener { if (pos > 0) { pos--; show() } }
        next.setOnClickListener { if (pos < idx.size - 1) { pos++; show() } }
        dlg = android.app.AlertDialog.Builder(a)
            .setTitle("이 페이지가 이 장 맞나요?")
            .setView(box)
            .setPositiveButton("새로 받기") { _, _ -> resumeSaved(s, here = true, fromIndex = idx[pos]) }
            .setNegativeButton("취소", null)
            .create()
        dlg.setOnShowListener { show() }
        dlg.show()
    }

    private fun resumeSaved(s: CollectSaves.Save, here: Boolean, fromIndex: Int? = null) {
        val page = if (here) a.currentWebView()?.url ?: "" else s.page
        if (here && !hereOk()) {
            a.toastMsg("이어 받을 페이지를 먼저 열어 주세요")
            return
        }
        if (page.isBlank()) {
            a.toastMsg("멈췄던 페이지 주소가 없어요")
            return
        }
        CollectSaves.revive(s.id)
        val g = JobStore.restore(s)
        if (g == null) {
            a.toastMsg("받아 둔 파일을 찾을 수 없어요")
            CollectSaves.delete(s.id)
            return
        }
        a.closeOverlays()
        val wv = if (here) a.currentWebView()!! else a.newTab(page).webView
        val o = s.o
        val j = Job(wv)
        if (fromIndex != null) {
            // 이 장부터 지우고 새로: 남은 장의 주소만 '이미 받음'으로
            JobStore.truncateFrom(g, fromIndex)
        } else {
            o.optJSONArray("found")?.let { arr -> for (i in 0 until arr.length()) j.found.add(arr.getString(i)) }
        }
        g.urls.forEach { j.found.add(it) }
        j.tapX = o.optDouble("tapX", 0.0).toFloat()
        j.tapY = o.optDouble("tapY", 0.0).toFloat()
        j.auto = o.optBoolean("auto", true) && (j.tapX > 0 || j.tapY > 0)
        j.goodMethod = o.optInt("method", -1)
        j.pages = o.optInt("pages", 1)
        j.grab = g
        j.lastPics = g.pics.size
        j.catchingUp = fromIndex == null
        j.rewound = fromIndex == null
        grabByView[System.identityHashCode(wv)] = g
        jobs.add(j)
        g.addLog("이어 받기 · 페이지 $page")
        j.text = if (fromIndex != null) "${fromIndex + 1}번부터 새로 받아요" else if (here) "이 페이지부터 이어 받아요 · 이미 받은 장은 건너뛰어요" else "멈췄던 페이지를 여는 중… 잠시 뒤 이어서 넘겨요"
        refreshBar()
        report()
        // 페이지가 뜰 시간을 주고 시작
        main.postDelayed({ if (jobs.contains(j) && !j.running) run(j) }, if (here) 800 else 4000)
    }

    // ================= 아래 막대 =================

    /** 탭을 바꾸거나 상태가 바뀔 때: 지금 보는 탭의 작업을 막대에 보여줌 */
    fun refreshBar() {
        val w = a.currentWebView()
        val p = pending?.takeIf { it.wv === w }
        val j = p ?: jobOf(w)
        if (j == null) {
            removeBar()
            return
        }
        if (bar == null) showBar()
        barFor = j
        when {
            p != null -> {
                barText?.text = "다음 페이지로 넘기는 곳(다음 버튼이나 이미지 오른쪽 등)을 꾸욱 눌러 주세요"
                setButtons(listOf(
                    "직접 넘길게요" to { startRunning(p, autoTap = false) },
                    "취소" to { cancelJob(p) },
                ))
                removeMarker()
            }
            j.paused -> {
                barText?.text = j.text
                setButtons(listOf(
                    "이어 넘기기" to { resume(j) },
                    "저장하고 그만" to { saveAndStop(j) },
                    "여기까지로 끝" to { endJob(j) },
                    "취소" to { cancelJob(j) },
                ))
                if (j.auto) showMarker(j) else removeMarker()
            }
            else -> {
                barText?.text = j.text.ifEmpty { "모으는 중" }
                setButtons(listOf(
                    "저장하고 그만" to { saveAndStop(j) },
                    "여기까지로 끝" to { endJob(j) },
                    "취소" to { cancelJob(j) },
                ))
                if (j.auto) showMarker(j) else removeMarker()
            }
        }
    }

    private fun removeBar() {
        removeMarker()
        bar?.let { a.frame.removeView(it) }
        bar = null
        barText = null
        barButtons = null
        barFor = null
    }

    private fun showBar() {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(a.getColor(R.color.snack))
                cornerRadius = a.dp(14).toFloat()
            }
            elevation = a.dp(10).toFloat()
            setPadding(a.dp(16), a.dp(12), a.dp(10), a.dp(8))
            isClickable = true
        }
        barText = TextView(a).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        box.addView(barText)
        barButtons = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        box.addView(barButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(a.dp(10), 0, a.dp(10), a.dp(60))
        a.frame.addView(box, lp)
        bar = box
    }

    private fun setButtons(list: List<Pair<String, () -> Unit>>) {
        val b = barButtons ?: return
        b.removeAllViews()
        list.forEach { (label, act) ->
            b.addView(TextView(a).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFF6B70.toInt())
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(8))
                a.ripple(this)
                setOnClickListener { act() }
            })
        }
    }

    /** 넘기는 자리를 빨간 동그라미로 표시 */
    private fun showMarker(j: Job) {
        removeMarker()
        val w = j.wv
        if (!w.isAttachedToWindow) return
        val size = a.dp(36)
        val loc = IntArray(2)
        val floc = IntArray(2)
        w.getLocationInWindow(loc)
        a.frame.getLocationInWindow(floc)
        val m = View(a).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33E5262D)
                setStroke(a.dp(3), 0xFFE5262D.toInt())
            }
            isClickable = false
            isFocusable = false
        }
        val lp = FrameLayout.LayoutParams(size, size)
        lp.leftMargin = (loc[0] - floc[0] + j.tapX - size / 2).toInt()
        lp.topMargin = (loc[1] - floc[1] + j.tapY - size / 2).toInt()
        a.frame.addView(m, lp)
        marker = m
    }

    private fun removeMarker() {
        marker?.let { a.frame.removeView(it) }
        marker = null
    }

    private fun flashMarker() {
        marker?.animate()?.scaleX(1.5f)?.scaleY(1.5f)?.alpha(0.4f)?.setDuration(120)?.withEndAction {
            marker?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(120)?.start()
        }?.start()
    }
}
