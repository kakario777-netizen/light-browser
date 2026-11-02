package com.kakario.lightbrowser

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Date

/** 설정 화면, 다운로드 화면 (앱 안에서 열리는 전체 화면 페이지) */
class Pages(private val a: MainActivity) {
    private val stack = mutableListOf<View>()
    private var downloadsBody: LinearLayout? = null

    val isOpen get() = stack.isNotEmpty()

    /** 뒤로가기 처리: 열린 페이지가 있으면 하나 닫고 true */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        val v = stack.removeAt(stack.size - 1)
        a.frame.removeView(v)
        if (v.tag == "downloads") {
            downloadsBody = null
            Downloads.onChange = null
        }
        return true
    }

    fun closeAll() {
        while (back()) { }
    }

    private fun page(title: String, tag: String): LinearLayout {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(a.getColor(R.color.bar))
            isClickable = true
            this.tag = tag
        }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(4), a.dp(4), a.dp(12), a.dp(4))
        }
        head.addView(a.iconBtn(R.drawable.ic_back, "닫기") { back() }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
        head.addView(TextView(a).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.text))
        })
        box.addView(head)
        box.addView(View(a).apply { setBackgroundColor(a.getColor(R.color.line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        a.frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stack.add(box)
        return box
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(a).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(6))
        })
    }

    private fun row(parent: LinearLayout, title: String, sub: String?, right: View?, onClick: (() -> Unit)?): TextView? {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(20), a.dp(12), a.dp(16), a.dp(12))
            minimumHeight = a.dp(56)
            if (onClick != null) {
                a.ripple(this)
                setOnClickListener { onClick() }
            }
        }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(a).apply {
            text = title
            textSize = 16f
            setTextColor(a.getColor(R.color.text))
        })
        var subView: TextView? = null
        if (sub != null) {
            subView = TextView(a).apply {
                text = sub
                textSize = 13f
                setTextColor(a.getColor(R.color.sub))
                setPadding(0, a.dp(2), 0, 0)
            }
            texts.addView(subView)
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) r.addView(right)
        parent.addView(r)
        return subView
    }

    private fun switchRow(parent: LinearLayout, title: String, sub: String?, on: Boolean, change: (Boolean) -> Unit) {
        val sw = Switch(a).apply {
            isChecked = on
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(a.getColor(R.color.accent), a.getColor(R.color.field))
            )
            setOnCheckedChangeListener { _, v -> change(v) }
        }
        row(parent, title, sub, sw) { sw.isChecked = !sw.isChecked }
    }

    // ================= 설정 =================

    fun openSettings() {
        val box = page("설정", "settings")
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(24))
        }
        box.addView(ScrollView(a).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val st = a.store

        section(body, "광고 · 차단")
        switchRow(body, "광고 차단", "광고 서버·광고 칸·광고 팝업을 막아요", a.adblockOn) { a.setAdblock(it) }
        switchRow(body, "차단 우회 (보안 DNS)", "막힌 사이트 접속을 시도해요", st.bypass) {
            st.bypass = it
            a.applyBypass(it, reload = true)
        }

        section(body, "다운로드")
        row(body, "다운로드 보기", "받는 중인 파일과 받은 파일", null) { openDownloads() }
        var folderSub: TextView? = null
        folderSub = row(body, "다운로드 폴더", folderName(st.downloadTree), null) {
            AlertDialog.Builder(a)
                .setTitle("다운로드 폴더")
                .setItems(arrayOf("폴더 고르기", "기본 폴더(Download)로")) { _, w ->
                    if (w == 0) a.pickDownloadFolder { folderSub?.text = folderName(st.downloadTree) }
                    else {
                        st.downloadTree = null
                        folderSub?.text = folderName(null)
                    }
                }.show()
        }
        switchRow(body, "다운로드 차단 해제", "광고 차단에 걸리는 주소라도 파일은 받고, 사이트 안에서 만든 파일(blob)도 저장해요",
            st.unblockDownloads) { st.unblockDownloads = it }

        section(body, "화면 · 검색")
        switchRow(body, "다크 모드일 때 웹페이지도 어둡게", "폰이 다크 모드일 때 적용", st.darkPages) {
            st.darkPages = it
            a.applyDarkAll()
        }
        var engSub: TextView? = null
        engSub = row(body, "검색 엔진", Store.ENGINES[st.engine], null) {
            AlertDialog.Builder(a)
                .setTitle("검색 엔진")
                .setSingleChoiceItems(Store.ENGINES, st.engine) { d, w ->
                    st.engine = w
                    engSub?.text = Store.ENGINES[w]
                    d.dismiss()
                }.show()
        }

        section(body, "기록")
        row(body, "방문 기록", null, null) { a.showHistory() }
    }

    private fun folderName(tree: String?): String {
        if (tree == null) return "기본 (내장 메모리 › Download)"
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
            val path = id.substringAfter(':', id)
            val vol = id.substringBefore(':')
            (if (vol == "primary") "내장 메모리" else vol) + if (path.isNotEmpty()) " › " + path.replace("/", " › ") else ""
        } catch (e: Exception) {
            tree
        }
    }

    // ================= 다운로드 =================

    fun openDownloads() {
        val box = page("다운로드", "downloads")
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(24))
        }
        box.addView(ScrollView(a).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        downloadsBody = body
        Downloads.onChange = { renderDownloads() }
        renderDownloads()
    }

    private fun renderDownloads() {
        val body = downloadsBody ?: return
        body.removeAllViews()
        val running = Downloads.items.filter { it.state == Downloads.RUNNING }
        val finished = Downloads.items.filter { it.state != Downloads.RUNNING }
        if (running.isEmpty() && finished.isEmpty()) {
            body.addView(TextView(a).apply {
                text = "받은 파일이 없어요"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(a.getColor(R.color.sub))
                setPadding(0, a.dp(60), 0, 0)
            })
            return
        }
        if (running.isNotEmpty()) {
            section(body, "받는 중")
            running.forEach { body.addView(runningRow(it)) }
        }
        if (finished.isNotEmpty()) {
            section(body, "완료")
            finished.forEach { body.addView(doneRow(it)) }
        }
    }

    private fun runningRow(it: Downloads.Item): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(10), a.dp(8), a.dp(12))
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(a).apply {
            text = it.name
            textSize = 15f
            setTextColor(a.getColor(R.color.text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(a.iconBtn(R.drawable.ic_close, "취소") {
            AlertDialog.Builder(a).setMessage("다운로드를 취소할까요?")
                .setPositiveButton("취소하기") { _, _ -> it.cancel = true }
                .setNegativeButton("계속 받기", null).show()
        }, LinearLayout.LayoutParams(a.dp(40), a.dp(40)))
        box.addView(top)
        val pct = if (it.total > 0) (it.done * 100 / it.total).toInt() else -1
        box.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = pct < 0
            if (pct >= 0) progress = pct
            progressTintList = ColorStateList.valueOf(a.getColor(R.color.accent))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(8)).apply {
            topMargin = a.dp(6); rightMargin = a.dp(12)
        })
        val parts = mutableListOf<String>()
        if (pct >= 0) parts.add("$pct%")
        parts.add(if (it.total > 0) "${Downloads.size(it.done)} / ${Downloads.size(it.total)}" else Downloads.size(it.done))
        if (it.speed > 0) {
            parts.add("${Downloads.size(it.speed)}/s")
            if (it.total > 0) {
                val left = (it.total - it.done) / it.speed
                parts.add("남은 시간 " + when {
                    left < 60 -> "${left}초"
                    left < 3600 -> "${left / 60}분 ${left % 60}초"
                    else -> "${left / 3600}시간 ${left % 3600 / 60}분"
                })
            }
        }
        box.addView(TextView(a).apply {
            text = parts.joinToString(" · ")
            textSize = 12f
            setTextColor(a.getColor(R.color.sub))
            setPadding(0, a.dp(6), 0, 0)
        })
        return box
    }

    private fun doneRow(it: Downloads.Item): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(12))
            a.ripple(this)
        }
        r.addView(TextView(a).apply {
            text = it.name
            textSize = 15f
            setTextColor(a.getColor(if (it.state == Downloads.DONE) R.color.text else R.color.sub))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val state = when (it.state) {
            Downloads.DONE -> Downloads.size(it.total)
            Downloads.CANCELED -> "취소됨"
            else -> "실패" + (it.error?.let { e -> " ($e)" } ?: "")
        }
        r.addView(TextView(a).apply {
            text = state + " · " + DateFormat.format("M월 d일 HH:mm", Date(it.time))
            textSize = 12f
            setTextColor(a.getColor(R.color.sub))
            setPadding(0, a.dp(3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        r.setOnClickListener { _ ->
            if (it.state == Downloads.DONE) a.openFile(it)
            else AlertDialog.Builder(a).setMessage("다시 받을까요?")
                .setPositiveButton("다시 받기") { _, _ ->
                    Downloads.items.remove(it)
                    a.startDownload(it.url, null, null, it.mime, null)
                }.setNegativeButton("닫기", null).show()
        }
        r.setOnLongClickListener { _ ->
            AlertDialog.Builder(a).setTitle(it.name)
                .setItems(arrayOf("목록에서 지우기", "파일까지 삭제")) { _, w ->
                    if (w == 1) Downloads.deleteFile(a, it)
                    Downloads.items.remove(it)
                    Downloads.save(a)
                    renderDownloads()
                }.show()
            true
        }
        return r
    }
}
