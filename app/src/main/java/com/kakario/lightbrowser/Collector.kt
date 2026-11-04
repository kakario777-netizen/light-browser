package com.kakario.lightbrowser

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONTokener

/** 작업 목록 화면에 보여줄 모으기 작업 정보 */
class CollectInfo(
    val title: String, val status: String,
    val goTo: () -> Unit, val finish: () -> Unit, val cancel: () -> Unit,
)

/**
 * 페이지 넘기며 모으기 (탭마다 따로, 여러 개 동시에).
 * 1페이지에서 시작 → '다음 페이지로 넘기는 곳'을 꾸욱 → 앱이 그 자리를 대신 눌러 가며
 * 화면에 크게 보이는 이미지를 모은다. 다른 탭·다른 앱·화면 꺼짐에서도 계속.
 * 끝나면 그 결과를 바로 받기 작업으로 넘긴다.
 */
class Collector(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())

    inner class Job(val wv: WebView) {
        var auto = false
        var running = false
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
        var text = ""

        fun title() = (wv.title?.takeIf { it.isNotBlank() } ?: android.net.Uri.parse(startPage ?: "").host ?: "images").take(40)

        val loop = object : Runnable {
            override fun run() = step(this@Job)
        }
    }

    private val jobs = mutableListOf<Job>()
    private var pending: Job? = null          // 넘기는 자리를 기다리는 중

    // 아래 막대 (지금 보는 탭의 작업 상태)
    private var bar: View? = null
    private var barText: TextView? = null
    private var barButtons: LinearLayout? = null
    private var marker: View? = null
    private var barFor: Job? = null

    val waitingForPoint get() = pending != null && pending?.wv === a.currentWebView()

    fun holds(w: WebView?) = w != null && jobs.any { it.wv === w && it.running }

    private fun jobOf(w: WebView?) = jobs.firstOrNull { it.wv === w }

    private fun onScreen(j: Job) = a.isShown && a.currentWebView() === j.wv

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
        j.running = true
        j.auto = autoTap
        j.lastNewAt = SystemClock.uptimeMillis()
        j.lastTapAt = 0L
        j.goodMethod = -1
        jobs.add(j)
        report()
        refreshBar()
        main.postDelayed(j.loop, if (autoTap) 900 else 600)
    }

    // ================= 반복 =================

    private fun step(j: Job) {
        if (!j.running) return
        if (!a.hasWebView(j.wv)) {
            finishJob(j)   // 모으던 탭이 닫힘
            return
        }
        snap(j) {
            if (!j.running) return@snap
            val now = SystemClock.uptimeMillis()
            if (j.auto) {
                val sinceTap = now - j.lastTapAt
                when {
                    // 새 이미지가 나오고 0.8초 지나면 다음 장
                    j.lastNewAt > j.lastTapAt && now - j.lastNewAt > 800 -> {
                        if (j.lastTapAt != 0L) j.goodMethod = j.lastMethod
                        j.failedTaps = 0
                        tap(j)
                    }
                    // 6초 넘게 새 이미지가 없으면 다시 눌러 보고, 여러 번 연속이면 끝
                    j.lastTapAt == 0L || sinceTap > 6000 -> {
                        if (j.lastTapAt != 0L) j.failedTaps++
                        if (j.failedTaps >= (if (onScreen(j)) 3 else 6)) {
                            j.text = "더 넘어가지 않아요. 마지막 페이지로 보고 마무리할게요"
                            refreshBar()
                            main.postDelayed({ finishJob(j) }, 900)
                            return@snap
                        }
                        tap(j)
                    }
                }
            }
            main.postDelayed(j.loop, 450)
        }
    }

    private fun tap(j: Job) {
        val w = j.wv
        j.lastTapAt = SystemClock.uptimeMillis()
        // 화면에 보이면 실제 터치. 안 보이면(다른 탭·다른 앱·화면 꺼짐) 통했던 방법 → 안 되면 바꿔 가며
        val m = when {
            onScreen(j) && j.goodMethod < 1 -> 0
            j.goodMethod >= 0 -> j.goodMethod
            else -> j.failedTaps % 3
        }
        j.lastMethod = m
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

    /** 화면에 크게 보이는 이미지 주소를 모은다 */
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
                            if (j.found.add(u)) j.lastNewAt = SystemClock.uptimeMillis()
                        } else if (u.startsWith("blob:") || u.startsWith("data:")) {
                            j.skipped++
                        }
                    }
                }
            } catch (e: Exception) {
            }
            if (j.running) {
                j.text = (if (j.auto) "넘기며 모으는 중" else "직접 넘기는 동안 모으는 중") + " · ${j.found.size}장" +
                    if (j.skipped > 0 && j.found.isEmpty()) "\n(이 사이트 이미지는 서버 주소가 없어 받을 수 없을 수 있어요)" else ""
                report()
                if (barFor === j) barText?.text = j.text
            }
            after?.invoke()
        }
    }

    /** 진행 상황을 알림·작업 아이콘에 알림 */
    private fun report() {
        BgWork.collectJobs = jobs.count { it.running }
        BgWork.collectImages = jobs.filter { it.running }.sumOf { it.found.size }
        BgWork.sync(a)
        a.onJobsChanged()
    }

    // ================= 끝 =================

    /** 여기까지 모은 이미지를 받기 작업으로 넘김 */
    private fun finishJob(j: Job) {
        if (!jobs.contains(j)) return
        j.running = false
        main.removeCallbacks(j.loop)
        jobs.remove(j)
        val list = j.found.toList()
        refreshBar()
        if (list.isEmpty()) {
            report()
            if (a.isShown) a.toastMsg("모은 이미지가 없어요")
            return
        }
        // 받기 작업을 먼저 만들고 나서 알림을 갱신해야, 화면이 꺼진 상태에서도 백그라운드 작업이 끊기지 않는다
        // 그 탭을 보고 있으면 받는 화면을 열고, 아니면 뒤에서 받기만
        a.openGrabberWith(list, j.startPage, j.wv.settings.userAgentString, j.title(), show = onScreen(j))
        report()
        if (!onScreen(j) && a.isShown) a.snackMsg("'${j.title()}' 모으기 끝 · ${list.size}장 받는 중")
    }

    private fun cancelJob(j: Job) {
        j.running = false
        main.removeCallbacks(j.loop)
        jobs.remove(j)
        if (pending === j) pending = null
        report()
        refreshBar()
    }

    /** 모으던 탭이 닫히려 할 때 */
    fun tabClosing(w: WebView) {
        jobOf(w)?.let { finishJob(it) }
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
        CollectInfo(j.title(), "${j.found.size}장 모음" + if (a.currentWebView() === j.wv) " · 지금 보는 탭" else "",
            goTo = { a.selectTabOf(j.wv) },
            finish = { finishJob(j) },
            cancel = { cancelJob(j) })
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
        if (p != null) {
            barText?.text = "다음 페이지로 넘기는 곳(다음 버튼이나 이미지 오른쪽 등)을 꾸욱 눌러 주세요"
            setButtons(listOf(
                "직접 넘길게요" to { startRunning(p, autoTap = false) },
                "취소" to { cancelJob(p) },
            ))
            removeMarker()
        } else {
            barText?.text = j.text.ifEmpty { "모으는 중" }
            setButtons(listOf(
                "멈추고 받기" to { finishJob(j) },
                "취소" to { cancelJob(j) },
            ))
            if (j.auto) showMarker(j) else removeMarker()
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
                setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(8))
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
