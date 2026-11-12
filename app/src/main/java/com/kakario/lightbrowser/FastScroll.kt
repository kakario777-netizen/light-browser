package com.kakario.lightbrowser

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 오른쪽 스크롤 막대 (끌어서 빠르게 이동).
 * 스크롤할 때 나타나고 잠시 뒤 흐려짐. 막대를 잡고 끌면 그 위치로 바로 이동.
 * 목록·설정 같은 세로로 넘기는 화면에 씀: wrap(view)로 감싼 걸 화면에 넣는다.
 */
object FastScroll {

    fun wrap(v: View): FrameLayout {
        val ctx = v.context
        val d = ctx.resources.displayMetrics.density
        val box = FrameLayout(ctx)
        box.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val thumbH = (56 * d).toInt()
        val idle = 0x88888888.toInt()
        val active = ctx.getColor(R.color.accent)
        val shape = GradientDrawable().apply {
            cornerRadius = 4 * d
            setColor(idle)
        }
        val thumb = View(ctx).apply {
            background = shape
            alpha = 0f
        }
        val track = FrameLayout(ctx)
        track.addView(thumb, FrameLayout.LayoutParams((7 * d).toInt(), thumbH, Gravity.END).apply {
            rightMargin = (3 * d).toInt()
        })
        box.addView(track, FrameLayout.LayoutParams((30 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))

        var dragging = false
        fun range(): Int = when (v) {
            is RecyclerView -> v.computeVerticalScrollRange()
            is ScrollView -> v.getChildAt(0)?.height ?: 0
            else -> 0
        }
        fun extent(): Int = when (v) {
            is RecyclerView -> v.computeVerticalScrollExtent()
            else -> v.height
        }
        fun offset(): Int = when (v) {
            is RecyclerView -> v.computeVerticalScrollOffset()
            else -> v.scrollY
        }
        val hide = Runnable { if (!dragging) thumb.animate().alpha(0f).setDuration(300).start() }
        fun update(show: Boolean) {
            val r = range()
            val e = extent()
            // 한 화면 반 이하로 짧으면 막대 안 띄움
            if (r <= e * 1.5f || track.height <= thumbH) {
                thumb.alpha = 0f
                return
            }
            val avail = track.height - thumbH
            val f = (offset().toFloat() / (r - e).coerceAtLeast(1)).coerceIn(0f, 1f)
            if (!dragging) thumb.translationY = avail * f
            if (show) {
                thumb.animate().cancel()
                thumb.alpha = 1f
                track.removeCallbacks(hide)
                track.postDelayed(hide, 1500)
            }
        }
        fun jump(y: Float) {
            val avail = (track.height - thumbH).coerceAtLeast(1)
            val f = ((y - thumbH / 2f) / avail).coerceIn(0f, 1f)
            thumb.translationY = avail * f
            when (v) {
                is RecyclerView -> {
                    val n = v.adapter?.itemCount ?: 0
                    if (n > 0) (v.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset((f * (n - 1)).toInt(), 0)
                }
                is ScrollView -> v.scrollTo(0, ((range() - extent()).coerceAtLeast(0) * f).toInt())
            }
        }

        if (v is RecyclerView) {
            v.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) update(true) else update(false)
                }
            })
        } else {
            v.setOnScrollChangeListener { _, _, _, _, _ -> update(true) }
        }

        track.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 막대가 보일 때, 막대 근처를 잡았을 때만 (아니면 아래 목록으로 넘김)
                    val near = e.y >= thumb.translationY - 24 * d && e.y <= thumb.translationY + thumbH + 24 * d
                    if (thumb.alpha < 0.5f || !near) return@setOnTouchListener false
                    dragging = true
                    shape.setColor(active)
                    track.parent?.requestDisallowInterceptTouchEvent(true)
                    track.removeCallbacks(hide)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) jump(e.y)
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        shape.setColor(idle)
                        track.postDelayed(hide, 1500)
                    }
                    true
                }
                else -> dragging
            }
        }
        return box
    }
}
