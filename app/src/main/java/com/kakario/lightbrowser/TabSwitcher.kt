package com.kakario.lightbrowser

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 오페라식 탭 목록.
 * 미리보기 카드가 가로로 늘어서고 밀어서 고른다. 카드를 꾸욱 눌러 끌면 순서 변경,
 * 위로 밀거나 우상단 X로 닫기. 닫으면 5초 동안 '되살리기'.
 */
class TabSwitcher(private val a: MainActivity) {
    private var root: View? = null
    private var rv: RecyclerView? = null
    private var countText: TextView? = null
    private val adapter = Adapter()

    val isOpen get() = root != null

    private var opening = false

    /** 지금 탭 화면을 먼저 찍고(탭 목록이 덮기 전에) 연다 */
    fun open() {
        if (isOpen || opening) return
        opening = true
        a.captureCurrentTab {
            opening = false
            show()
        }
    }

    private fun show() {
        if (isOpen) return
        val dm = a.resources.displayMetrics
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(a.getColor(R.color.switcher))
            isClickable = true
        }
        countText = TextView(a).apply {
            textSize = 14f
            setTextColor(a.getColor(R.color.sub))
            gravity = Gravity.CENTER
            setPadding(0, a.dp(14), 0, a.dp(6))
        }
        box.addView(countText)

        val list = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@TabSwitcher.adapter
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rv = list
        LinearSnapHelper().attachToRecyclerView(list)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, ItemTouchHelper.UP
        ) {
            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                a.moveTab(from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                closeAt(vh.bindingAdapterPosition)
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                vh.itemView.scaleX = 1f
                vh.itemView.scaleY = 1f
                r.post { adapter.notifyDataSetChanged() }   // 현재 탭 테두리 다시 그리기
            }

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(vh, state)
                if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh?.itemView?.scaleX = 1.05f
                    vh?.itemView?.scaleY = 1.05f
                }
            }
        }).attachToRecyclerView(list)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 아래: 새 탭 버튼
        val bottom = FrameLayout(a).apply { setPadding(0, a.dp(8), 0, a.dp(14)) }
        val plus = a.iconBtn(R.drawable.ic_add, "새 탭") {
            close()
            a.newTab(null)
        }.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(a.getColor(R.color.bar))
            }
            elevation = a.dp(3).toFloat()
        }
        bottom.addView(plus, FrameLayout.LayoutParams(a.dp(52), a.dp(52), Gravity.CENTER))
        box.addView(bottom)

        a.frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = box
        refreshCount()

        // 카드 크기: 화면 폭의 62%, 가운데 정렬
        list.doOnLayout {
            val w = list.width.takeIf { it > 0 } ?: dm.widthPixels
            val cw = (w * if (a.resources.configuration.screenWidthDp >= 600) 0.4f else 0.62f).toInt()
            val pad = (w - cw) / 2 - a.dp(8)
            list.setPadding(pad, 0, pad, 0)
            adapter.cardW = cw
            adapter.cardH = (list.height * 0.86f).toInt().coerceAtLeast(a.dp(200))
            adapter.notifyDataSetChanged()
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(a.curIndex, 0)
        }
    }

    fun close() {
        root?.let { a.frame.removeView(it) }
        root = null
        rv = null
    }

    fun refresh() {
        if (!isOpen) return
        adapter.notifyDataSetChanged()
        refreshCount()
    }

    private fun refreshCount() {
        countText?.text = "탭 ${a.tabList.size}개"
    }

    private fun closeAt(pos: Int) {
        if (pos < 0 || pos >= a.tabList.size) return
        a.closeTabUndoable(pos)
        refresh()
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var cardW = 0
        var cardH = 0

        override fun getItemCount() = a.tabList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = FrameLayout(a).apply {
                elevation = a.dp(4).toFloat()
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val t = a.tabList[position]
            val card = holder.itemView as FrameLayout
            card.removeAllViews()
            val lp = RecyclerView.LayoutParams(
                if (cardW > 0) cardW else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (cardH > 0) cardH else a.dp(400)
            )
            lp.setMargins(a.dp(8), a.dp(10), a.dp(8), a.dp(10))
            card.layoutParams = lp
            val isCur = position == a.curIndex
            card.background = GradientDrawable().apply {
                setColor(a.getColor(R.color.bar))
                cornerRadius = a.dp(14).toFloat()
                if (isCur) setStroke(a.dp(3), a.getColor(R.color.accent))
            }

            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            // 머리: 제목 + 작은 X
            val head = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(12), 0, a.dp(2), 0)
            }
            head.addView(TextView(a).apply {
                text = a.tabTitle(t)
                textSize = 13f
                setTextColor(a.getColor(R.color.text))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(a.iconBtn(R.drawable.ic_close, "탭 닫기") {
                closeAt(holder.bindingAdapterPosition)
            }.apply { setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8)) }, LinearLayout.LayoutParams(a.dp(34), a.dp(34)))
            col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(38)))

            val thumb = t.thumb
            if (thumb != null && !t.home) {
                col.addView(TopCrop(a).apply {
                    setImageBitmap(thumb)
                    setBackgroundColor(a.getColor(R.color.field))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            } else {
                col.addView(TextView(a).apply {
                    text = if (t.home) "새 탭" else a.tabTitle(t).take(1)
                    textSize = if (t.home) 16f else 44f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(a.getColor(R.color.sub))
                    setBackgroundColor(a.getColor(R.color.field))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            card.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p >= 0) {
                    close()
                    a.selectTab(p)
                }
            }
        }
    }
}

/** 미리보기 그림을 카드 폭에 맞추고 위쪽부터 보여줌 (크기가 정해질 때마다 다시 맞춤) */
private class TopCrop(ctx: android.content.Context) : ImageView(ctx) {
    init {
        scaleType = ScaleType.MATRIX
    }

    private fun fit() {
        val d = drawable ?: return
        if (width <= 0 || d.intrinsicWidth <= 0) return
        val s = width.toFloat() / d.intrinsicWidth
        imageMatrix = android.graphics.Matrix().apply { setScale(s, s) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }
}
