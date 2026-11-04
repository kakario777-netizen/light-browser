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
        var goodMethod = -1     // 이 사이트에서 통한 넘기기 방법
        var lastMethod = 0
        var pages = 1
        var text = ""
        var grab: GrabJob? = null

        fun title() = (wv.title?.takeIf { it.isNotBlank() } ?: Uri.parse(startPage ?: "").host ?: "images").take(40)

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
        if (g.captured.containsKey(url)) return null
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
            if (j.auto) {
                val waitLimit = if (onScreen(j)) 6000 else 10000   // 화면 밖에서는 늦게 뜨므로 더 기다림
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
                    j.lastTapAt == 0L || now - j.lastTapAt > waitLimit -> {
                        if (j.lastTapAt != 0L) j.failedTaps++
                        val limit = if (onScreen(j)) 3 else 6
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
        val js = "(function(){var o=[],W=innerWidth,H=innerHeight;" +
            "function big(r){return r.width*r.height>W*H*0.12&&r.bottom>0&&r.top<H&&r.right>0&&r.left<W;}" +
            "document.querySelectorAll('img').forEach(function(i){var r=i.getBoundingClientRect();" +
            "var s=i.currentSrc||i.src;if(s&&big(r))o.push(s);});" +
            "try{document.elementsFromPoint(W/2,H/2).forEach(function(e){var r=e.getBoundingClientRect();if(!big(r))return;" +
            "var b=getComputedStyle(e).backgroundImage;var m=b&&b.match(/url\\([\"']?([^\"')]+)/);" +
            "if(m)o.push(new URL(m[1],location.href).href);});}catch(e){}" +
            "return JSON.stringify(o);})()"
        j.wv.evaluateJavascript(js) { res ->
            try {
                val str = JSONTokener(res).nextValue() as? String
                if (str != null) {
                    val arr = JSONArray(str)
                    for (i in 0 until arr.length()) {
                        val u = arr.getString(i)
                        if (u.startsWith("http")) {
                            if (j.found.add(u)) {
                                j.lastNewAt = SystemClock.uptimeMillis()
                                log(j, "새 이미지 ${j.found.size}번째 찾음 (${where(j)}) $u")
                                j.grab?.let { g -> if (j.running) JobStore.add(g, u) }
                            }
                        } else if (u.startsWith("blob:") || u.startsWith("data:")) {
                            j.skipped++
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

    private fun updateText(j: Job) {
        val g = j.grab
        val dl = if (g != null) " · 받음 ${g.pics.size}" + (if (g.fails > 0) " (실패 ${g.fails})" else "") else ""
        j.text = when {
            j.paused -> "멈췄어요: ${j.stopReason}\n${j.found.size}장 찾음$dl · 받기는 계속돼요"
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
        updateText(j)
        report()
        refreshBar()
        if (!onScreen(j)) BgWork.collectPaused(a, j.title(), j.found.size)
    }

    fun resume(j: Job) {
        if (!jobs.contains(j) || j.running) return
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
                    "여기까지로 끝" to { endJob(j) },
                    "취소" to { cancelJob(j) },
                ))
                if (j.auto) showMarker(j) else removeMarker()
            }
            else -> {
                barText?.text = j.text.ifEmpty { "모으는 중" }
                setButtons(listOf(
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
