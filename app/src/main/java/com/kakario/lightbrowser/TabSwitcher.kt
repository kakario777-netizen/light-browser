package com.kakario.lightbrowser

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

/**
 * 오페라식 탭 목록.
 *  - 정사각형에 가까운 카드가 가로로 늘어서고 밀어서 고름
 *  - 같은 사이트 탭은 그룹 카드 하나로 (안에 2×2·3×2 작은 미리보기). 누르면 확대되며 그 그룹 탭들이 펼쳐짐
 *  - 열면 지금 탭 자리에서 시작 (지금 탭이 그룹 안이면 그 그룹을 펼친 채로)
 *  - 꾸욱 눌러 끌면 순서 변경, 위로 밀거나 우상단 X로 닫기 (탭은 5초간 되살리기)
 * 미리보기는 파일로도 저장돼서 앱을 다시 켜도 남는다.
 */
class TabSwitcher(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()

    private var root: View? = null
    private var rv: RecyclerView? = null
    private var titleText: TextView? = null
    private var backBtn: View? = null
    private val adapter = Adapter()

    /** 한 칸: 탭 하나, 또는 같은 사이트 탭 묶음 */
    private class Entry(val key: String, val tabs: List<Tab>)

    private var entries = mutableListOf<Entry>()
    private var group: String? = null        // 펼친 그룹 (null = 전체)
    private var orderChanged = false
    private var opening = false
    private var cardW = 0
    private val loading = HashSet<Long>()

    val isOpen get() = root != null

    /** 지금 탭 화면을 먼저 찍고(탭 목록이 덮기 전에) 연다 */
    fun open() {
        if (isOpen || opening) return
        opening = true
        a.captureCurrentTab {
            opening = false
            show()
        }
    }

    /** 뒤로가기: 그룹을 펼친 상태면 전체로, 아니면 닫기 */
    fun back() {
        if (group != null) {
            enter(null, focus = null)
        } else close()
    }

    fun close() {
        root?.let { a.frame.removeView(it) }
        root = null
        rv = null
        group = null
    }

    fun refresh() {
        if (!isOpen) return
        rebuild()
        // 펼친 그룹이 하나만 남으면 전체로
        if (group != null && entries.size < 2) {
            enter(null, focus = a.tabList.getOrNull(a.curIndex))
            return
        }
        adapter.notifyDataSetChanged()
        updateTitle()
    }

    // ================= 묶기 =================

    private fun groups(): List<Entry> {
        val map = LinkedHashMap<String, MutableList<Tab>>()
        a.tabList.forEach { t -> map.getOrPut(a.tabKey(t)) { ArrayList() }.add(t) }
        return map.map { (k, v) -> Entry(k, v) }
    }

    private fun rebuild() {
        val all = groups()
        val g = group
        entries = if (g == null) all.toMutableList()
        else (all.firstOrNull { it.key == g }?.tabs ?: emptyList()).map { Entry(g, listOf(it)) }.toMutableList()
    }

    private fun updateTitle() {
        val g = group
        titleText?.text = if (g == null) "탭 ${a.tabList.size}개" else "$g · ${entries.size}개"
        backBtn?.visibility = if (g == null) View.INVISIBLE else View.VISIBLE
    }

    // ================= 화면 =================

    private fun show() {
        if (isOpen) return
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(a.getColor(R.color.switcher))
            isClickable = true
        }
        // 위: (그룹일 때) 전체로 돌아가기 + 제목
        val head = FrameLayout(a).apply { setPadding(a.dp(6), a.dp(8), a.dp(6), a.dp(4)) }
        val back = TextView(a).apply {
            text = "‹ 전체 탭"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
            a.ripple(this)
            setOnClickListener { enter(null, focus = null) }
        }
        backBtn = back
        head.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        titleText = TextView(a).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.sub))
            gravity = Gravity.CENTER
        }
        head.addView(titleText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        box.addView(head)

        // 가운데: 카드 (세로 가운데 정렬)
        val middle = FrameLayout(a)
        val list = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@TabSwitcher.adapter
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
        }
        rv = list
        LinearSnapHelper().attachToRecyclerView(list)
        ItemTouchHelper(touch).attachToRecyclerView(list)
        middle.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        box.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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

        // 지금 탭이 그룹 안이면 그 그룹을 펼친 채로 시작
        val cur = a.tabList.getOrNull(a.curIndex)
        val startGroup = cur?.let { t -> groups().firstOrNull { it.tabs.size >= 2 && t in it.tabs }?.key }
        list.doOnLayout { enter(startGroup, focus = cur, animate = false) }
    }

    /** 전체(null) 또는 그룹 하나로 들어가기. focus 탭이 있는 칸을 가운데로 */
    private fun enter(g: String?, focus: Tab?, animate: Boolean = true) {
        val list = rv ?: return
        val from = group
        group = g
        rebuild()
        updateTitle()
        // 카드 크기: 화면 폭의 66%(접은 폰) / 40%(펼친 폴드), 정사각형 + 제목줄
        val w = list.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels
        cardW = (w * if (a.resources.configuration.screenWidthDp >= 600) 0.4f else 0.66f).toInt()
        val pad = (w - cardW) / 2 - a.dp(8)
        list.setPadding(pad, a.dp(10), pad, a.dp(10))
        adapter.notifyDataSetChanged()
        val target = when {
            focus != null -> entries.indexOfFirst { focus in it.tabs }
            from != null && g == null -> entries.indexOfFirst { it.key == from }   // 그룹에서 나오면 그 그룹 칸으로
            else -> -1
        }.coerceAtLeast(0)
        // 카드 크기가 정해진 뒤에 지금 칸으로 옮김 (먼저 옮기면 엉뚱한 칸에 걸림)
        list.post {
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 0)
        }
        if (animate) {
            // 그룹으로 들어갈 때는 확대, 나올 때는 축소되는 느낌
            list.scaleX = if (g != null) 0.82f else 1.12f
            list.scaleY = list.scaleX
            list.alpha = 0f
            list.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
        }
    }

    // ================= 끌기·밀기 =================

    private val touch = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, ItemTouchHelper.UP) {
        override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0 || from >= entries.size || to >= entries.size) return false
            val e = entries.removeAt(from)
            entries.add(to, e)
            adapter.notifyItemMoved(from, to)
            orderChanged = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val p = vh.bindingAdapterPosition
            val e = entries.getOrNull(p) ?: return
            closeEntry(e) { adapter.notifyItemChanged(p) }
        }

        override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(r, vh)
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
            if (orderChanged) {
                orderChanged = false
                applyOrder()
            }
            r.post { adapter.notifyDataSetChanged() }
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                vh?.itemView?.scaleX = 1.05f
                vh?.itemView?.scaleY = 1.05f
            }
        }
    }

    /** 끌어서 바꾼 순서를 실제 탭 순서에 반영 */
    private fun applyOrder() {
        val all = a.tabList.toMutableList()
        val g = group
        if (g == null) {
            a.reorderTabs(entries.flatMap { it.tabs })
        } else {
            // 그룹 탭들이 있던 자리는 그대로 두고, 그 안에서 순서만 바꿈
            val slots = all.indices.filter { a.tabKey(all[it]) == g }
            val newOrder = entries.map { it.tabs[0] }
            if (slots.size == newOrder.size) {
                slots.forEachIndexed { k, idx -> all[idx] = newOrder[k] }
                a.reorderTabs(all)
            }
        }
    }

    /** 닫기: 탭 하나는 되살리기 가능, 그룹은 한 번 묻고 모두 닫음 */
    private fun closeEntry(e: Entry, cancelled: () -> Unit) {
        if (e.tabs.size == 1) {
            val idx = a.tabList.indexOf(e.tabs[0])
            if (idx >= 0) a.closeTabUndoable(idx)
            refresh()
        } else {
            AlertDialog.Builder(a)
                .setMessage("'${e.key}' 탭 ${e.tabs.size}개를 모두 닫을까요?")
                .setPositiveButton("모두 닫기") { _, _ ->
                    a.closeTabsNow(e.tabs)
                    refresh()
                }
                .setNegativeButton("취소") { _, _ -> cancelled() }
                .setOnCancelListener { cancelled() }
                .show()
        }
    }

    // ================= 카드 =================

    /** 미리보기: 메모리에 없으면 저장해 둔 파일에서 읽어 옴 */
    private fun thumbOf(t: Tab, onLoaded: () -> Unit): android.graphics.Bitmap? {
        t.thumb?.let { return it }
        if (t.home || !loading.add(t.id)) return null
        loader.execute {
            val b = Thumbs.loadTab(a, t.id)
            main.post {
                loading.remove(t.id)
                if (b != null && t.thumb == null) {
                    t.thumb = b
                    if (isOpen) onLoaded()
                }
            }
        }
        return null
    }

    private fun preview(t: Tab, textSize: Float, onLoaded: () -> Unit): View {
        val b = thumbOf(t, onLoaded)
        return if (b != null) TopCrop(a).apply {
            setImageBitmap(b)
            setBackgroundColor(a.getColor(R.color.field))
        } else TextView(a).apply {
            text = if (t.home) "새 탭" else a.tabTitle(t).take(1)
            this.textSize = if (t.home) textSize * 0.4f else textSize
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(a.getColor(R.color.sub))
            setBackgroundColor(a.getColor(R.color.field))
        }
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = FrameLayout(a).apply {
                elevation = a.dp(4).toFloat()
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val e = entries[position]
            val card = holder.itemView as FrameLayout
            card.removeAllViews()
            val headH = a.dp(40)
            val w = if (cardW > 0) cardW else a.dp(240)
            card.layoutParams = RecyclerView.LayoutParams(w, w + headH).apply {
                setMargins(a.dp(8), a.dp(6), a.dp(8), a.dp(6))
            }
            val cur = a.tabList.getOrNull(a.curIndex)
            val isGroupCard = group == null && e.tabs.size >= 2
            val hasCur = cur != null && cur in e.tabs
            card.background = GradientDrawable().apply {
                setColor(a.getColor(R.color.bar))
                cornerRadius = a.dp(16).toFloat()
                if (hasCur) setStroke(a.dp(3), a.getColor(R.color.accent))
            }

            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            // 머리: 제목(그룹이면 사이트 · 개수) + 작은 X
            val head = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(14), 0, a.dp(2), 0)
            }
            head.addView(TextView(a).apply {
                text = if (isGroupCard) "${e.key} · ${e.tabs.size}" else a.tabTitle(e.tabs[0])
                textSize = 13f
                if (isGroupCard) typeface = Typeface.DEFAULT_BOLD
                setTextColor(a.getColor(R.color.text))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(a.iconBtn(R.drawable.ic_close, if (isGroupCard) "그룹 닫기" else "탭 닫기") {
                val p = holder.bindingAdapterPosition
                entries.getOrNull(p)?.let { closeEntry(it) {} }
            }.apply { setPadding(a.dp(9), a.dp(9), a.dp(9), a.dp(9)) }, LinearLayout.LayoutParams(a.dp(36), a.dp(36)))
            col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headH))

            val reload = { notifyItemChanged(holder.bindingAdapterPosition.coerceAtLeast(0)) }
            if (isGroupCard) {
                // 그룹: 작은 미리보기를 2×2(4개까지) 또는 3×2(5개 이상)로
                val cols = if (e.tabs.size <= 4) 2 else 3
                val rows = 2
                val gap = a.dp(4)
                val grid = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(gap, 0, gap, gap)
                }
                val show = e.tabs.take(cols * rows)
                for (r in 0 until rows) {
                    val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        val cell = FrameLayout(a).apply {
                            outlineProvider = ViewOutlineProvider.BACKGROUND
                            clipToOutline = true
                            background = GradientDrawable().apply {
                                setColor(a.getColor(R.color.field))
                                cornerRadius = a.dp(8).toFloat()
                                if (cur != null && show.getOrNull(i) === cur) setStroke(a.dp(2), a.getColor(R.color.accent))
                            }
                        }
                        show.getOrNull(i)?.let { t ->
                            cell.addView(preview(t, 18f, reload), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            // 다 못 보여주면 마지막 칸에 +N
                            if (i == show.size - 1 && e.tabs.size > show.size) {
                                cell.addView(TextView(a).apply {
                                    text = "+${e.tabs.size - show.size + 1}"
                                    textSize = 18f
                                    typeface = Typeface.DEFAULT_BOLD
                                    gravity = Gravity.CENTER
                                    setTextColor(android.graphics.Color.WHITE)
                                    setBackgroundColor(0x99000000.toInt())
                                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            }
                        }
                        line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                        })
                    }
                    grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                }
                col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            } else {
                col.addView(preview(e.tabs[0], 44f, reload), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            card.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.setOnClickListener {
                val p = holder.bindingAdapterPosition
                val en = entries.getOrNull(p) ?: return@setOnClickListener
                if (group == null && en.tabs.size >= 2) {
                    // 그룹 펼치기 (지금 탭이 그 안에 있으면 그 탭부터)
                    enter(en.key, focus = cur?.takeIf { it in en.tabs } ?: en.tabs.first())
                } else {
                    val idx = a.tabList.indexOf(en.tabs[0])
                    close()
                    if (idx >= 0) a.selectTab(idx)
                }
            }
        }
    }
}

/** 미리보기 그림을 칸에 꽉 채우고 위쪽부터 보여줌 (크기가 정해질 때마다 다시 맞춤) */
private class TopCrop(ctx: android.content.Context) : ImageView(ctx) {
    init {
        scaleType = ScaleType.MATRIX
    }

    private fun fit() {
        val d = drawable ?: return
        if (width <= 0 || d.intrinsicWidth <= 0) return
        // 폭에 맞추되, 높이가 모자라면 높이에 맞춤 (빈 곳 없이 채우기)
        val s = maxOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight.coerceAtLeast(1))
        val dx = (width - d.intrinsicWidth * s) / 2f
        imageMatrix = android.graphics.Matrix().apply {
            setScale(s, s)
            postTranslate(dx, 0f)
        }
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
