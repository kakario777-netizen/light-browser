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

/**
 * 페이지 넘기며 모으기.
 * 1페이지에서 시작 → 사용자가 '다음 페이지로 넘기는 곳'을 꾸욱 누름 → 앱이 그 자리를 대신 눌러 가며
 * 화면에 크게 보이는 이미지를 하나씩 모은다. 직접 넘기기를 고르면 사용자가 넘길 때마다 모은다.
 */
class Collector(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())

    private var bar: View? = null
    private var text: TextView? = null
    private var buttons: LinearLayout? = null
    private var marker: View? = null

    var waitingForPoint = false
        private set
    private var running = false
    private var auto = false
    private var wv: WebView? = null
    private var tapX = 0f
    private var tapY = 0f
    private var startPage: String? = null

    private val found = LinkedHashSet<String>()
    private var skipped = 0                  // 서버 주소가 없는 이미지(blob 등)
    private var lastNewAt = 0L
    private var lastTapAt = 0L
    private var failedTaps = 0

    val isActive get() = bar != null

    // ================= 시작 =================

    fun begin() {
        val w = a.currentWebView()
        if (w == null) {
            a.toastMsg("이미지를 보는 페이지(1페이지)에서 시작해 주세요")
            return
        }
        stop(cancel = true)
        wv = w
        startPage = w.url
        found.clear()
        skipped = 0
        failedTaps = 0
        waitingForPoint = true
        showBar()
        setText("다음 페이지로 넘기는 곳(다음 버튼이나 이미지 오른쪽 등)을 꾸욱 눌러 주세요")
        setButtons(listOf(
            "직접 넘길게요" to { startRunning(autoTap = false) },
            "취소" to { stop(cancel = true) },
        ))
        snap()   // 1페이지 이미지 먼저
    }

    /** 사용자가 넘기는 자리를 꾸욱 눌렀을 때 (웹뷰 좌표) */
    fun setPoint(x: Float, y: Float) {
        if (!waitingForPoint) return
        tapX = x
        tapY = y
        showMarker()
        startRunning(autoTap = true)
    }

    private fun startRunning(autoTap: Boolean) {
        waitingForPoint = false
        running = true
        auto = autoTap
        lastNewAt = SystemClock.uptimeMillis()
        lastTapAt = 0L
        setButtons(listOf(
            "멈추고 받기" to { finish() },
            "취소" to { stop(cancel = true) },
        ))
        update()
        main.removeCallbacks(loop)
        main.postDelayed(loop, if (autoTap) 900 else 600)
    }

    // ================= 반복 =================

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val w = wv
            if (w == null || a.currentWebView() !== w) {
                // 탭을 바꾸거나 닫으면 여기까지 모은 것으로 마무리
                finish()
                return
            }
            snap {
                if (!running) return@snap
                val now = SystemClock.uptimeMillis()
                if (auto) {
                    val sinceTap = now - lastTapAt
                    when {
                        // 새 이미지가 나오고 0.8초 지나면 다음 장
                        lastNewAt > lastTapAt && now - lastNewAt > 800 -> {
                            failedTaps = 0
                            tap()
                        }
                        // 6초 넘게 새 이미지가 없으면 한 번 더 눌러 보고, 3번 연속이면 끝
                        lastTapAt == 0L || sinceTap > 6000 -> {
                            if (lastTapAt != 0L) failedTaps++
                            if (failedTaps >= 3) {
                                setText("더 넘어가지 않아요. 마지막 페이지로 보고 마무리할게요")
                                main.postDelayed({ finish() }, 900)
                                return@snap
                            }
                            tap()
                        }
                    }
                }
                main.postDelayed(this, 450)
            }
        }
    }

    private fun tap() {
        val w = wv ?: return
        lastTapAt = SystemClock.uptimeMillis()
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
        w.dispatchTouchEvent(down)
        down.recycle()
        main.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, tapX, tapY, 0)
            w.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
        flashMarker()
    }

    /** 화면에 크게 보이는 이미지 주소를 모은다 */
    private fun snap(after: (() -> Unit)? = null) {
        val w = wv ?: return
        val js = "(function(){var o=[],W=innerWidth,H=innerHeight;" +
            "function big(r){return r.width*r.height>W*H*0.12&&r.bottom>0&&r.top<H&&r.right>0&&r.left<W;}" +
            "document.querySelectorAll('img').forEach(function(i){var r=i.getBoundingClientRect();" +
            "var s=i.currentSrc||i.src;if(s&&big(r))o.push(s);});" +
            "try{document.elementsFromPoint(W/2,H/2).forEach(function(e){var r=e.getBoundingClientRect();if(!big(r))return;" +
            "var b=getComputedStyle(e).backgroundImage;var m=b&&b.match(/url\\([\"']?([^\"')]+)/);" +
            "if(m)o.push(new URL(m[1],location.href).href);});}catch(e){}" +
            "return JSON.stringify(o);})()"
        w.evaluateJavascript(js) { res ->
            try {
                val str = JSONTokener(res).nextValue() as? String
                if (str != null) {
                    val arr = JSONArray(str)
                    for (i in 0 until arr.length()) {
                        val u = arr.getString(i)
                        if (u.startsWith("http")) {
                            if (found.add(u)) lastNewAt = SystemClock.uptimeMillis()
                        } else if (u.startsWith("blob:") || u.startsWith("data:")) {
                            skipped++
                        }
                    }
                }
            } catch (e: Exception) {
            }
            update()
            after?.invoke()
        }
    }

    private fun update() {
        if (!running) return
        setText((if (auto) "넘기며 모으는 중" else "직접 넘기는 동안 모으는 중") + " · ${found.size}장" +
            if (skipped > 0 && found.isEmpty()) "\n(이 사이트 이미지는 서버 주소가 없어 받을 수 없을 수 있어요)" else "")
    }

    // ================= 끝 =================

    private fun finish() {
        val list = found.toList()
        val page = startPage
        val w = wv
        stop(cancel = false)
        if (list.isEmpty()) {
            a.toastMsg("모은 이미지가 없어요")
            return
        }
        val name = (w?.title?.takeIf { it.isNotBlank() } ?: android.net.Uri.parse(page ?: "").host ?: "images").take(40)
        a.openGrabberWith(list, page, w?.settings?.userAgentString, name)
    }

    fun stop(cancel: Boolean) {
        running = false
        waitingForPoint = false
        main.removeCallbacks(loop)
        marker?.let { a.frame.removeView(it) }
        marker = null
        bar?.let { a.frame.removeView(it) }
        bar = null
        text = null
        buttons = null
        if (cancel) {
            wv = null
            found.clear()
        }
    }

    /** 뒤로가기: 모으는 중이면 취소 */
    fun back(): Boolean {
        if (!isActive) return false
        stop(cancel = true)
        return true
    }

    // ================= 화면 =================

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
        text = TextView(a).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        box.addView(text)
        buttons = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(a.dp(10), 0, a.dp(10), a.dp(60))
        a.frame.addView(box, lp)
        bar = box
    }

    private fun setText(t: String) {
        text?.text = t
    }

    private fun setButtons(list: List<Pair<String, () -> Unit>>) {
        val b = buttons ?: return
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
    private fun showMarker() {
        val w = wv ?: return
        marker?.let { a.frame.removeView(it) }
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
        lp.leftMargin = (loc[0] - floc[0] + tapX - size / 2).toInt()
        lp.topMargin = (loc[1] - floc[1] + tapY - size / 2).toInt()
        a.frame.addView(m, lp)
        marker = m
    }

    private fun flashMarker() {
        marker?.animate()?.scaleX(1.5f)?.scaleY(1.5f)?.alpha(0.4f)?.setDuration(120)?.withEndAction {
            marker?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(120)?.start()
        }?.start()
    }
}
