package com.kakario.lightbrowser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 홈·다른 앱으로 나가 있을 때 '넘기며 모으는' 페이지를 다른 앱 위의 작은 창에 띄워 둔다.
 * 웹 엔진은 화면에 보이는 페이지만 제대로 움직이므로, 작게라도 보이게 해 두면
 * 앱 안에서 볼 때와 똑같이 넘기고 이미지를 불러온다. (다른 앱 위에 표시 권한 필요)
 * 창을 누르면 앱으로 돌아가고, 끌어서 옮길 수 있다.
 */
class MiniWindow(private val a: MainActivity) {
    private val wm = a.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val hosted = mutableListOf<Triple<WebView, FrameLayout, TextView>>()

    val isShowing get() = root != null
    private var keepOn = false

    /** 넘기며 모으는 동안 다른 앱을 보고 있어도 화면이 꺼지지 않게 */
    fun setKeepScreenOn(on: Boolean) {
        keepOn = on
        val p = params ?: return
        val r = root ?: return
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        try { wm.updateViewLayout(r, p) } catch (e: Exception) {}
    }

    fun canUse() = Settings.canDrawOverlays(a)

    fun hosts(w: WebView?) = w != null && hosted.any { it.first === w }

    /** 권한 설정 화면 열기 */
    fun askPermission() {
        try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + a.packageName)))
        } catch (e: Exception) {
            a.toastMsg("설정 화면을 열 수 없어요")
        }
    }

    /**
     * pages: (웹뷰, 원래 폭, 원래 높이, 이름)
     * 웹뷰는 원래 크기 그대로 두고 작게 줄여 보여줌 → 페이지 배치·누르는 자리가 그대로 유지됨
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(pages: List<Pair<WebView, Pair<Int, Int>>>, labels: List<String>) {
        if (!canUse() || pages.isEmpty()) return
        hide()
        // 창 안의 모든 터치를 창이 받음 (작은 페이지가 눌리지 않게)
        val box = object : LinearLayout(a) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = true
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE202022.toInt())
                cornerRadius = a.dp(12).toFloat()
            }
            setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
        }
        val cellW = a.dp(if (pages.size > 2) 84 else 110)
        pages.forEachIndexed { idx, (w, size) ->
            val (fw, fh) = size
            val scale = cellW.toFloat() / fw.coerceAtLeast(1)
            val cellH = (fh * scale).toInt().coerceAtLeast(a.dp(60))
            (w.parent as? ViewGroup)?.removeView(w)
            val cell = FrameLayout(a).apply {
                clipChildren = true
                setBackgroundColor(Color.WHITE)
            }
            w.pivotX = 0f
            w.pivotY = 0f
            w.scaleX = scale
            w.scaleY = scale
            cell.addView(w, FrameLayout.LayoutParams(fw, fh))
            val label = TextView(a).apply {
                text = labels.getOrElse(idx) { "" }
                textSize = 10f
                setTextColor(Color.WHITE)
                maxLines = 2
                setPadding(a.dp(2), a.dp(3), a.dp(2), a.dp(4))
                typeface = Typeface.DEFAULT_BOLD
            }
            box.addView(cell, LinearLayout.LayoutParams(cellW, cellH))
            box.addView(label, LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT))
            hosted.add(Triple(w, cell, label))
            w.onResume()
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (keepOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = a.dp(8)
            y = a.dp(140)
        }
        // 끌어서 옮기기, 짧게 누르면 앱 열기
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > a.dp(6) || abs(dy) > a.dp(6)) moved = true
                    if (moved) {
                        p.x = (startX - dx).toInt()   // 오른쪽 기준이라 반대로
                        p.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(box, p) } catch (x: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    a.startActivity(Intent(a, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
            true
        }
        try {
            wm.addView(box, p)
            root = box
            params = p
        } catch (e: Exception) {
            // 권한이 없거나 창을 못 띄우면 원래대로
            release()
        }
    }

    fun updateLabels(labels: Map<WebView, String>) {
        hosted.forEach { (w, _, label) -> labels[w]?.let { label.text = it } }
    }

    /** 미니 창을 닫고 웹뷰를 풀어 줌 (원래 크기로) */
    fun hide() {
        root?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        root = null
        params = null
        release()
    }

    private fun release() {
        hosted.forEach { (w, cell, _) ->
            cell.removeView(w)
            w.scaleX = 1f
            w.scaleY = 1f
        }
        hosted.clear()
    }
}
