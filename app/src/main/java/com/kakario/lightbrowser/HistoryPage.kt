package com.kakario.lightbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 방문 기록 화면.
 * 날짜별로 묶고, 제목·주소를 나눠 보여주며 오른쪽에 방문 시간.
 * 꾸욱 누르면 선택 모드(체크란), 꾸욱 누른 채 끌면 여러 개를 한 번에 선택. 선택 삭제·전체 삭제.
 */
class HistoryPage(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var list: RecyclerView? = null
    private var titleText: TextView? = null
    private var leftBtn: FrameLayout? = null
    private var rightBox: LinearLayout? = null
    private var emptyText: TextView? = null

    /** 화면에 그릴 줄: 날짜 머리줄 또는 기록 하나 */
    private class Row(val header: String?, val item: Store.Item?, val index: Int)

    private var items: List<Store.Item> = emptyList()
    private var rows: List<Row> = emptyList()
    private val selected = HashSet<Int>()     // items의 순번
    private var selecting = false

    // 꾸욱 + 끌기 다중 선택
    private var dragging = false
    private var dragAnchor = -1               // items 순번
    private var dragBase: Set<Int> = emptySet()
    private var dragAdd = true
    private var lastY = 0f

    private val adapter = Adapter()

    val isOpen get() = root != null

    fun open() {
        if (isOpen) close()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(a.getColor(R.color.bar))
            isClickable = true
        }
        // 위 막대
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(4), a.dp(4), a.dp(8), a.dp(4))
        }
        leftBtn = FrameLayout(a)
        head.addView(leftBtn, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
        titleText = TextView(a).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.text))
        }
        head.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rightBox = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(rightBox)
        box.addView(head)
        box.addView(View(a).apply { setBackgroundColor(a.getColor(R.color.line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val content = FrameLayout(a)
        val rv = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a)
            adapter = this@HistoryPage.adapter
            itemAnimator = null
        }
        rv.addOnItemTouchListener(dragListener)
        list = rv
        content.addView(rv)
        emptyText = TextView(a).apply {
            text = "방문 기록이 없어요"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(a.getColor(R.color.sub))
            visibility = View.GONE
        }
        content.addView(emptyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(160)))
        box.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        a.frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = box
        reload()
    }

    fun close() {
        root?.let { a.frame.removeView(it) }
        root = null
        list = null
        selected.clear()
        selecting = false
        dragging = false
    }

    /** 뒤로가기: 선택 중이면 선택 해제, 아니면 닫기 */
    fun back(): Boolean {
        if (!isOpen) return false
        if (selecting) {
            endSelect()
            return true
        }
        close()
        return true
    }

    // ================= 데이터 =================

    private fun dayLabel(t: Long): String {
        if (t <= 0) return "날짜 없음 (예전 기록)"
        val c = Calendar.getInstance()
        val today = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        c.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = t
        val d = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        val date = SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(Date(t))
        return when (d) {
            today -> "오늘 · $date"
            yesterday -> "어제 · $date"
            else -> date
        }
    }

    private fun reload() {
        items = a.store.history()
        val out = ArrayList<Row>()
        var last: String? = null
        items.forEachIndexed { i, it ->
            val label = dayLabel(it.time)
            if (label != last) {
                out.add(Row(label, null, -1))
                last = label
            }
            out.add(Row(null, it, i))
        }
        rows = out
        selected.retainAll(items.indices.toSet())
        emptyText?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        updateBar()
    }

    // ================= 위 막대 =================

    private fun textBtn(t: String, onClick: () -> Unit) = TextView(a).apply {
        text = t
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(a.getColor(R.color.accent))
        setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        a.ripple(this)
        setOnClickListener { onClick() }
    }

    private fun updateBar() {
        val left = leftBtn ?: return
        val right = rightBox ?: return
        left.removeAllViews()
        right.removeAllViews()
        if (selecting) {
            left.addView(a.iconBtn(R.drawable.ic_close, "선택 해제") { endSelect() },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            titleText?.text = "${selected.size}개 선택"
            right.addView(textBtn(if (selected.size == items.size) "선택 해제" else "전체 선택") {
                if (selected.size == items.size) selected.clear() else selected.addAll(items.indices)
                adapter.notifyDataSetChanged()
                updateBar()
            })
            right.addView(textBtn("삭제") { deleteSelected() })
        } else {
            left.addView(a.iconBtn(R.drawable.ic_back, "닫기") { close() },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            titleText?.text = "방문 기록"
            if (items.isNotEmpty()) {
                right.addView(textBtn("선택") { startSelect() })
                right.addView(textBtn("전체 삭제") { deleteAll() })
            }
        }
    }

    private fun startSelect() {
        selecting = true
        adapter.notifyDataSetChanged()
        updateBar()
    }

    private fun endSelect() {
        selecting = false
        selected.clear()
        adapter.notifyDataSetChanged()
        updateBar()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) {
            a.toastMsg("지울 기록을 골라 주세요")
            return
        }
        val pick = selected.mapNotNull { items.getOrNull(it) }
        AlertDialog.Builder(a)
            .setMessage("고른 기록 ${pick.size}개를 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                a.store.removeHistory(pick)
                selected.clear()
                selecting = false
                reload()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteAll() {
        AlertDialog.Builder(a)
            .setMessage("방문 기록을 모두 지울까요?")
            .setPositiveButton("모두 지우기") { _, _ ->
                a.clearAllHistory()
                reload()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun toggle(index: Int) {
        if (!selected.add(index)) selected.remove(index)
        if (selected.isEmpty() && !dragging) {
            endSelect()
            return
        }
        updateBar()
    }

    // ================= 꾸욱 + 끌기 =================

    /** 화면 위치의 기록 순번 (머리줄이면 -1) */
    private fun indexAt(y: Float): Int {
        val rv = list ?: return -1
        val child = rv.findChildViewUnder(rv.width / 2f, y) ?: return -1
        val pos = rv.getChildAdapterPosition(child)
        return rows.getOrNull(pos)?.index ?: -1
    }

    private fun applyDrag(y: Float) {
        val cur = indexAt(y)
        if (cur < 0 || dragAnchor < 0) return
        val range = if (cur >= dragAnchor) dragAnchor..cur else cur..dragAnchor
        val next = HashSet(dragBase)
        if (dragAdd) next.addAll(range) else next.removeAll(range.toSet())
        if (next != selected) {
            selected.clear()
            selected.addAll(next)
            adapter.notifyDataSetChanged()
            updateBar()
        }
    }

    /** 손가락이 위·아래 끝에 있으면 계속 굴림 */
    private val autoScroll = object : Runnable {
        override fun run() {
            val rv = list ?: return
            if (!dragging) return
            val edge = a.dp(64)
            val dy = when {
                lastY < edge -> -a.dp(14)
                lastY > rv.height - edge -> a.dp(14)
                else -> 0
            }
            if (dy != 0) {
                rv.scrollBy(0, dy)
                applyDrag(lastY)
            }
            main.postDelayed(this, 16)
        }
    }

    private val dragListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (!dragging) return false
            return e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    lastY = e.y
                    applyDrag(e.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDrag()
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    private fun stopDrag() {
        dragging = false
        main.removeCallbacks(autoScroll)
        if (selected.isEmpty()) endSelect()
    }

    // ================= 목록 =================

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position].header != null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val t = TextView(a).apply {
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(a.getColor(R.color.accent))
                    setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(6))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                return object : RecyclerView.ViewHolder(t) {}
            }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(12), a.dp(10), a.dp(16), a.dp(10))
                minimumHeight = a.dp(60)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val check = CheckBox(a).apply {
                id = 1
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList.valueOf(a.getColor(R.color.accent))
            }
            row.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val texts = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(6), 0, a.dp(10), 0)
            }
            texts.addView(TextView(a).apply {
                id = 2
                textSize = 15f
                setTextColor(a.getColor(R.color.text))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(TextView(a).apply {
                id = 3
                textSize = 12f
                setTextColor(a.getColor(R.color.sub))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, a.dp(3), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(a).apply {
                id = 4
                textSize = 12f
                setTextColor(a.getColor(R.color.sub))
            })
            return object : RecyclerView.ViewHolder(row) {}
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            if (r.header != null) {
                (h.itemView as TextView).text = r.header
                return
            }
            val it = r.item ?: return
            val row = h.itemView as LinearLayout
            val check = row.findViewById<CheckBox>(1)
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.isChecked = r.index in selected
            row.findViewById<TextView>(2).text = it.title.ifBlank { it.url }
            row.findViewById<TextView>(3).text = it.url.removePrefix("https://").removePrefix("http://")
            row.findViewById<TextView>(4).text =
                if (it.time > 0) SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it.time)) else ""
            row.setBackgroundColor(if (r.index in selected) a.getColor(R.color.field) else 0)
            row.setOnClickListener {
                if (selecting) {
                    toggle(r.index)
                    notifyItemChanged(h.bindingAdapterPosition)
                } else {
                    close()
                    a.openUrl(it.url)
                }
            }
            row.setOnLongClickListener {
                // 꾸욱: 선택 모드로, 그대로 끌면 여러 개 선택
                if (!selecting) selecting = true
                dragAnchor = r.index
                dragAdd = r.index !in selected
                dragBase = HashSet(selected)
                if (dragAdd) selected.add(r.index) else selected.remove(r.index)
                dragging = true
                list?.let { rv -> lastY = row.y + row.height / 2f; main.removeCallbacks(autoScroll); main.post(autoScroll); rv.parent?.requestDisallowInterceptTouchEvent(true) }
                notifyDataSetChanged()
                updateBar()
                true
            }
        }
    }
}
