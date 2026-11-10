package com.kakario.lightbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 이미지 받기 화면.
 *  - 작업 하나 보기: 찾는 중·받는 중 진행 상황, 받은 이미지 격자에서 고르고 ZIP·낱개 저장
 *  - 작업 목록: 넘기며 모으기·이미지 받기·파일 다운로드를 한눈에
 * 작업 자체는 JobStore에서 돌아가므로 화면을 닫아도 계속 받는다.
 */
class Grabber(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val thumbPool = Executors.newFixedThreadPool(2)

    // 작업 보기 화면
    private var root: View? = null
    private var job: GrabJob? = null
    private var titleText: TextView? = null
    private var statusText: TextView? = null
    private var bar: ProgressBar? = null
    private var actions: LinearLayout? = null
    private var failBox: LinearLayout? = null
    private var grid: RecyclerView? = null
    private var preview: View? = null
    private var shownPics = 0
    private var confirmShownFor = -1L

    // 작업 목록 화면
    private var listRoot: View? = null
    private var listBody: LinearLayout? = null

    private val adapter = Adapter()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val listener: (GrabJob?) -> Unit = { j -> onJobChanged(j) }

    val isOpen get() = root != null || listRoot != null

    init {
        JobStore.init(a)
        JobStore.listeners.add(listener)
        // 찾기가 끝났을 때: 지금 그 작업을 보고 있으면 확인 창, 아니면 바로 받기
        JobStore.onFound = { j ->
            if (job === j && root != null && a.isShown) confirmDownload(j) else JobStore.download(j)
        }
    }

    fun release() {
        JobStore.listeners.remove(listener)
        JobStore.onFound = null
    }

    // ================= 시작 =================

    fun start(imageUrl: String, pageUrl: String?, userAgent: String?, pageTitle: String? = null) {
        if (!imageUrl.startsWith("http")) {
            a.toastMsg("이 이미지는 서버 주소가 없어서 폴더를 찾을 수 없어요. '넘기며 모으기'를 써 보세요")
            return
        }
        val j = JobStore.create(JobStore.folderName(JobStore.folderOf(imageUrl)), pageUrl, userAgent)
        j.pageTitle = pageTitle
        openJob(j)
        JobStore.findFolder(j, imageUrl)
    }

    /** 넘기며 모으기 결과: 묻지 않고 바로 받기 시작 */
    fun startWithUrls(urls: List<String>, pageUrl: String?, userAgent: String?, name: String, show: Boolean): GrabJob? {
        if (urls.isEmpty()) return null
        val j = JobStore.create(name, pageUrl, userAgent)
        j.how = "넘기며 모으기"
        JobStore.download(j, urls)
        if (show) openJob(j)
        return j
    }

    // ================= 주소 분석 =================

    fun analyze(imageUrl: String, pageUrl: String?, userAgent: String?) {
        if (!imageUrl.startsWith("http")) {
            a.toastMsg("이 이미지는 서버 주소가 없어요. '넘기며 모으기'를 써 보세요")
            return
        }
        val u = Uri.parse(imageUrl)
        val q = imageUrl.indexOf('?')
        val pathPart = if (q >= 0) imageUrl.substring(0, q) else imageUrl
        val fileName = pathPart.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val allRuns = JobStore.runs(imageUrl)

        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(8), a.dp(22), a.dp(4))
        }
        fun label(t: String, bold: Boolean = false, size: Float = 13f, color: Int = R.color.text) = TextView(a).apply {
            text = t
            textSize = size
            setTextColor(a.getColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextIsSelectable(true)
            setPadding(0, a.dp(3), 0, a.dp(3))
        }
        body.addView(label("사이트   ${u.host ?: "?"}"))
        body.addView(label("폴더   " + (u.path ?: "/").substringBeforeLast('/') + "/"))
        body.addView(label("파일 이름   $fileName"))
        body.addView(label("확장자   " + ext.ifEmpty { "(없음)" }))
        if (q >= 0) body.addView(label("뒤에 붙은 값   " + imageUrl.substring(q)))

        val group = android.widget.RadioGroup(a)
        val def = JobStore.lastRunInFile(imageUrl) ?: allRuns.lastOrNull()
        if (allRuns.isEmpty()) {
            body.addView(label("\n주소에 번호가 없어서 이어 찾기를 할 수 없어요.\n'페이지 넘기며 모으기'를 써 보세요.", color = R.color.sub))
        } else {
            body.addView(label("\n바꿔 가며 찾을 번호", bold = true, size = 14f, color = R.color.accent))
            allRuns.forEachIndexed { i, run ->
                val before = imageUrl.substring(maxOf(0, run.start - 14), run.start)
                val after = imageUrl.substring(run.end, minOf(imageUrl.length, run.end + 8))
                group.addView(android.widget.RadioButton(a).apply {
                    id = 100 + i
                    text = android.text.Html.fromHtml(
                        "…" + TextUtils.htmlEncode(before) + "<b><font color='#E5262D'>[" +
                            imageUrl.substring(run.start, run.end) + "]</font></b>" + TextUtils.htmlEncode(after) + "…",
                        android.text.Html.FROM_HTML_MODE_LEGACY
                    )
                    textSize = 13f
                    setTextColor(a.getColor(R.color.text))
                    isChecked = run.start == def?.start
                })
            }
            body.addView(group)
        }
        val extBox = android.widget.CheckBox(a).apply {
            text = "확장자도 바꿔 가며 찾기 (jpg·png·webp·gif)"
            isChecked = true
            setTextColor(a.getColor(R.color.text))
        }
        val stripBox = android.widget.CheckBox(a).apply {
            text = "뒤에 붙은 크기 조절 값 떼고 원본 찾기"
            setTextColor(a.getColor(R.color.text))
        }
        val missGroup = android.widget.RadioGroup(a).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val misses = intArrayOf(5, 20, 100)
        misses.forEachIndexed { i, m ->
            missGroup.addView(android.widget.RadioButton(a).apply {
                id = 200 + i
                text = "${m}개"
                setTextColor(a.getColor(R.color.text))
                isChecked = m == 20
            })
        }
        if (allRuns.isNotEmpty()) {
            body.addView(extBox)
            if (q >= 0) body.addView(stripBox)
            body.addView(label("\n빈 번호가 몇 개 이어지면 멈출까요", bold = true, size = 14f, color = R.color.accent))
            body.addView(missGroup)
        }

        val b = AlertDialog.Builder(a)
            .setTitle("이미지 주소 분석")
            .setView(ScrollView(a).apply { addView(body) })
            .setNegativeButton("닫기", null)
            .setNeutralButton("넘기며 모으기") { _, _ ->
                closeView()
                a.startCollector()
            }
        if (allRuns.isNotEmpty()) b.setPositiveButton("찾기") { _, _ ->
            val run = allRuns.getOrNull(group.checkedRadioButtonId - 100) ?: def ?: return@setPositiveButton
            val miss = misses.getOrElse(missGroup.checkedRadioButtonId - 200) { 20 }
            val j = JobStore.create(JobStore.folderName(JobStore.folderOf(imageUrl)), pageUrl, userAgent)
            openJob(j)
            JobStore.findSequence(j, imageUrl, run, miss, extBox.isChecked, stripBox.isChecked && q >= 0)
        }
        b.show()
    }

    private fun confirmDownload(j: GrabJob) {
        if (confirmShownFor == j.id) return
        confirmShownFor = j.id
        AlertDialog.Builder(a)
            .setTitle("이미지 ${j.urls.size}개")
            .setMessage("${j.title}\n\n모두 받은 뒤 확인하고 고를 수 있어요.")
            .setPositiveButton("받기") { _, _ -> JobStore.download(j) }
            .setNegativeButton("취소") { _, _ ->
                JobStore.remove(j)
                closeView()
            }
            .setCancelable(false)
            .show()
    }

    // ================= 작업 하나 보기 =================

    fun openJobById(id: Long) {
        val j = JobStore.get(id)
        if (j == null) { openList(); return }
        openJob(j)
    }

    fun openJob(j: GrabJob) {
        closeView()
        job = j
        j.seen = true
        BgWork.clearJob(a, j.id)
        cache.evictAll()
        shownPics = -1
        buildUi(j)
        render()
        if (j.state == GrabJob.FOUND && a.isShown) confirmDownload(j)
    }

    private fun onJobChanged(j: GrabJob?) {
        if (root != null && (j == null || j === job)) render()
        if (listRoot != null) renderList()
    }

    private fun render() {
        val j = job ?: return
        titleText?.text = j.title
        statusText?.text = j.status
        val b = bar
        failBox?.visibility = View.GONE
        actions?.visibility = View.GONE
        when (j.state) {
            GrabJob.FINDING -> { b?.visibility = View.VISIBLE; b?.isIndeterminate = true }
            GrabJob.FOUND -> b?.visibility = View.GONE
            GrabJob.DOWNLOADING, GrabJob.COLLECTING -> {
                b?.visibility = View.VISIBLE
                b?.isIndeterminate = false
                b?.max = j.total.coerceAtLeast(1)
                b?.progress = j.done
            }
            GrabJob.DONE -> {
                b?.visibility = View.GONE
                actions?.visibility = View.VISIBLE
                updateCount()
                if (j.fails > 0) showFailed(j)
            }
            GrabJob.NOTHING -> {
                b?.visibility = View.GONE
                showFail(j)
            }
            else -> b?.visibility = View.GONE
        }
        if (shownPics != j.pics.size) {
            shownPics = j.pics.size
            adapter.notifyDataSetChanged()
        }
    }

    private fun showFail(j: GrabJob) {
        val box = failBox ?: return
        box.removeAllViews()
        box.visibility = View.VISIBLE
        val img = j.baseImage
        if (img != null) {
            box.addView(button("주소 분석해서 찾기", filled = true) {
                val r = j.referer; val u = j.ua
                JobStore.remove(j)
                closeView()
                analyze(img, r, u)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(46)).apply { bottomMargin = a.dp(8) })
        }
        box.addView(button("페이지 넘기며 모으기", filled = img == null) {
            JobStore.remove(j)
            closeView()
            a.startCollector()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(46)))
    }

    /** 받기가 끝났는데 실패한 게 있을 때: 이유 보기 / 다시 받기 */
    private fun showFailed(j: GrabJob) {
        val box = failBox ?: return
        box.removeAllViews()
        box.visibility = View.VISIBLE
        box.setPadding(a.dp(10), a.dp(4), a.dp(10), 0)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("실패 이유 보기", filled = false) { showFailReasons(j) },
            LinearLayout.LayoutParams(0, a.dp(42), 1f).apply { rightMargin = a.dp(6) })
        row.addView(button("실패한 ${j.fails}개 다시 받기", filled = false) {
            JobStore.retryFailed(j)
        }, LinearLayout.LayoutParams(0, a.dp(42), 1.2f))
        box.addView(row)
    }

    private fun showFailReasons(j: GrabJob) {
        val list = JobStore.failList(j)
        if (list.isEmpty()) return
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(6))
        }
        // 이유별 설명 먼저
        list.groupBy { it.third }.entries.sortedByDescending { it.value.size }.forEach { (code, items) ->
            body.addView(TextView(a).apply {
                text = "${FailReason.short(code)} · ${items.size}개"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(a.getColor(R.color.accent))
                setPadding(0, a.dp(10), 0, a.dp(2))
            })
            body.addView(TextView(a).apply {
                text = FailReason.long(code)
                textSize = 13f
                setTextColor(a.getColor(R.color.text))
                setPadding(0, 0, 0, a.dp(4))
            })
            items.take(30).forEach { (i, url, _) ->
                body.addView(TextView(a).apply {
                    text = "${i + 1}번  $url"
                    textSize = 11f
                    setTextColor(a.getColor(R.color.sub))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setTextIsSelectable(true)
                })
            }
            if (items.size > 30) body.addView(TextView(a).apply {
                text = "… 외 ${items.size - 30}개"
                textSize = 11f
                setTextColor(a.getColor(R.color.sub))
            })
        }
        AlertDialog.Builder(a)
            .setTitle("실패한 이미지 ${list.size}개")
            .setView(ScrollView(a).apply { addView(body) })
            .setPositiveButton("다시 받기") { _, _ -> JobStore.retryFailed(j) }
            .setNeutralButton("오류 기록 저장") { _, _ -> saveLog(j) }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 작업 기록(넘기기·실패 상세)을 보기 */
    private fun showLog(j: GrabJob) {
        val text = JobStore.logText(j)
        val tv = TextView(a).apply {
            this.text = text
            textSize = 11f
            setTextColor(a.getColor(R.color.text))
            setTextIsSelectable(true)
            setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(8))
            typeface = Typeface.MONOSPACE
        }
        AlertDialog.Builder(a)
            .setTitle("작업 기록")
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("파일로 저장") { _, _ -> saveLog(j) }
            .setNeutralButton("공유") { _, _ ->
                try {
                    a.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text.take(90_000)), "작업 기록 공유"))
                } catch (e: Exception) {
                    a.toastMsg("공유할 앱이 없어요")
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 작업 기록을 다운로드 폴더에 txt로 저장 */
    private fun saveLog(j: GrabJob) {
        val bytes = JobStore.logText(j).toByteArray(Charsets.UTF_8)
        val stamp = java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.KOREA).format(java.util.Date())
        val name = j.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40) + "_기록_" + stamp + ".txt"
        Downloads.saveWith(a, name, "text/plain", bytes.size.toLong(), { out, progress ->
            out.write(bytes)
            progress(bytes.size.toLong())
        })
        a.toastMsg("다운로드 폴더에 저장할게요: $name")
    }

    private fun header(title: String, onBack: () -> Unit, right: View? = null): Pair<LinearLayout, TextView> {
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(4), a.dp(4), a.dp(8), a.dp(4))
        }
        head.addView(a.iconBtn(R.drawable.ic_back, "닫기") { onBack() }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
        val t = TextView(a).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) head.addView(right)
        return Pair(head, t)
    }

    private fun buildUi(j: GrabJob) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(a.getColor(R.color.bar))
            isClickable = true
        }
        val listBtn = TextView(a).apply {
            text = "작업 목록"
            textSize = 13f
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
            a.ripple(this)
            setOnClickListener { closeView(); openList() }
        }
        val right = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(TextView(a).apply {
            text = "기록"
            textSize = 13f
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(10), a.dp(10), a.dp(6), a.dp(10))
            a.ripple(this)
            setOnClickListener { job?.let { showLog(it) } }
        })
        right.addView(listBtn)
        val (head, t) = header(j.title, { closeView() }, right)
        titleText = t
        box.addView(head)

        statusText = TextView(a).apply {
            textSize = 13f
            setTextColor(a.getColor(R.color.sub))
            setPadding(a.dp(18), a.dp(2), a.dp(18), a.dp(8))
        }
        box.addView(statusText)
        bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(a.getColor(R.color.accent))
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(6)).apply {
            leftMargin = a.dp(18); rightMargin = a.dp(18); bottomMargin = a.dp(8)
        })

        val cols = if (a.resources.configuration.screenWidthDp >= 600) 5 else 3
        grid = RecyclerView(a).apply {
            layoutManager = GridLayoutManager(a, cols)
            adapter = this@Grabber.adapter
            setPadding(a.dp(4), 0, a.dp(4), a.dp(4))
            clipToPadding = false
        }
        box.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        failBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(14))
            visibility = View.GONE
        }
        box.addView(failBox)

        val act = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(10))
            visibility = View.GONE
        }
        act.addView(button("전체", filled = false) {
            val pics = job?.pics ?: return@button
            val all = pics.all { it.selected }
            pics.forEach { it.selected = !all }
            adapter.notifyDataSetChanged()
            updateCount()
        }, LinearLayout.LayoutParams(0, a.dp(46), 0.8f).apply { rightMargin = a.dp(6) })
        act.addView(button("낱개 저장", filled = false) { saveEach() },
            LinearLayout.LayoutParams(0, a.dp(46), 1f).apply { rightMargin = a.dp(6) })
        act.addView(button("ZIP 압축", filled = true) { saveZip() }, LinearLayout.LayoutParams(0, a.dp(46), 1.3f))
        actions = act
        box.addView(act)

        a.frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = box
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = TextView(a).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) Color.WHITE else a.getColor(R.color.text))
        background = GradientDrawable().apply {
            cornerRadius = a.dp(23).toFloat()
            setColor(if (filled) a.getColor(R.color.accent) else a.getColor(R.color.field))
        }
        setOnClickListener { onClick() }
    }

    private fun updateCount() {
        val j = job ?: return
        if (j.state != GrabJob.DONE) return
        val sel = j.pics.count { it.selected }
        val size = j.pics.filter { it.selected }.sumOf { it.file.length() }
        statusText?.text = "${j.pics.size}개 중 ${sel}개 선택 · ${Downloads.size(size)}" +
            (if (j.fails > 0) " · 실패 ${j.fails}" else "") + "\n눌러서 빼거나 넣고, 꾸욱 눌러 크게 보기"
    }

    /** 보기 화면만 닫음 (작업은 계속) */
    fun closeView() {
        closePreview()
        root?.let { a.frame.removeView(it) }
        root = null
        grid = null
        failBox = null
        actions = null
        job = null
    }

    // ================= 작업 목록 =================

    fun openList() {
        closeView()
        listRoot?.let { a.frame.removeView(it) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(a.getColor(R.color.bar))
            isClickable = true
        }
        val (head, _) = header("작업 현황", { closeList() })
        box.addView(head)
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(24))
        }
        box.addView(ScrollView(a).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        a.frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        listRoot = box
        listBody = body
        renderList()
    }

    fun closeList() {
        listRoot?.let { a.frame.removeView(it) }
        listRoot = null
        listBody = null
    }

    fun refreshList() {
        if (listRoot != null) renderList()
    }

    private fun section(body: LinearLayout, t: String) {
        body.addView(TextView(a).apply {
            text = t
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(6))
        })
    }

    private fun jobRow(title: String, sub: String, prog: Int, max: Int, indeterminate: Boolean, onClick: () -> Unit,
                       onLong: (() -> Unit)?, extra: List<Pair<String, () -> Unit>> = emptyList()): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(12), a.dp(16), a.dp(12))
            a.ripple(this)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        r.addView(TextView(a).apply {
            text = title
            textSize = 15f
            setTextColor(a.getColor(R.color.text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(TextView(a).apply {
            text = sub
            textSize = 12f
            setTextColor(a.getColor(R.color.sub))
            maxLines = 2
            setPadding(0, a.dp(2), 0, 0)
        })
        if (max > 0 || indeterminate) {
            r.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = indeterminate
                this.max = max.coerceAtLeast(1)
                progress = prog
                progressTintList = android.content.res.ColorStateList.valueOf(a.getColor(R.color.accent))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(6)).apply { topMargin = a.dp(6) })
        }
        if (extra.isNotEmpty()) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            extra.forEach { (t, act) ->
                row.addView(TextView(a).apply {
                    text = t
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(a.getColor(R.color.accent))
                    setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(4))
                    a.ripple(this)
                    setOnClickListener { act() }
                })
            }
            r.addView(row)
        }
        return r
    }

    private fun renderList() {
        val body = listBody ?: return
        body.removeAllViews()
        var any = false

        val collects = a.collectJobsInfo()
        if (collects.isNotEmpty()) {
            any = true
            section(body, "넘기며 모으는 중")
            collects.forEach { c ->
                val btns = mutableListOf<Pair<String, () -> Unit>>("탭으로 가기" to { closeList(); c.goTo() })
                if (c.paused) btns.add("이어 넘기기" to { c.resume() })
                btns.add("여기까지로 끝" to { c.end() })
                btns.add("취소" to { c.cancel() })
                body.addView(jobRow(c.title, c.status, 0, 0, !c.paused, { closeList(); c.goTo() }, null, btns))
            }
        }

        val jobs = JobStore.jobs.toList()
        val activeJobs = jobs.filter { (it.active || it.state == GrabJob.FOUND) && it.state != GrabJob.COLLECTING }
        val doneJobs = jobs.filter { !it.active && it.state != GrabJob.FOUND && it.state != GrabJob.COLLECTING }
        if (activeJobs.isNotEmpty()) {
            any = true
            section(body, "이미지 받는 중")
            activeJobs.forEach { j ->
                body.addView(jobRow(j.title, j.status.replace("\n", " "), j.done, j.total, j.state != GrabJob.DOWNLOADING,
                    { openJob(j) }, { askRemove(j) }, listOf("취소" to { JobStore.cancel(j); JobStore.remove(j) })))
            }
        }
        val files = Downloads.items.filter { it.state == Downloads.RUNNING }
        if (files.isNotEmpty()) {
            any = true
            section(body, "파일 다운로드 중")
            files.forEach { f ->
                val pct = if (f.total > 0) (f.done * 100 / f.total).toInt() else -1
                body.addView(jobRow(f.name,
                    (if (pct >= 0) "$pct% · " else "") + Downloads.size(f.done) + (if (f.total > 0) " / " + Downloads.size(f.total) else "") +
                        if (f.speed > 0) " · ${Downloads.size(f.speed)}/s" else "",
                    pct.coerceAtLeast(0), if (pct >= 0) 100 else 0, pct < 0,
                    { closeList(); a.openDownloadsPage() }, null))
            }
        }
        if (doneJobs.isNotEmpty()) {
            any = true
            section(body, "완료된 이미지 받기 (눌러서 확인·압축, 꾸욱 눌러 지우기)")
            doneJobs.forEach { j ->
                val label = (if (!j.seen && j.state == GrabJob.DONE) "● " else "") + j.title
                body.addView(jobRow(label, j.status.replace("\n", " "), 0, 0, false, { openJob(j) }, { askRemove(j) }))
            }
        }
        if (!any) {
            body.addView(TextView(a).apply {
                text = "진행 중이거나 끝난 작업이 없어요"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(a.getColor(R.color.sub))
                setPadding(0, a.dp(60), 0, 0)
            })
        }
        body.addView(TextView(a).apply {
            text = "파일 다운로드 기록 전체 보기"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(a.getColor(R.color.accent))
            setPadding(0, a.dp(24), 0, a.dp(12))
            a.ripple(this)
            setOnClickListener { closeList(); a.openDownloadsPage() }
        })
    }

    private fun askRemove(j: GrabJob) {
        AlertDialog.Builder(a)
            .setTitle(j.title)
            .setMessage(if (j.active) "받는 중이에요. 취소하고 지울까요?" else "이 작업과 임시로 받은 이미지를 지울까요?\n(저장한 ZIP·이미지는 그대로예요)")
            .setPositiveButton("지우기") { _, _ -> JobStore.remove(j) }
            .setNegativeButton("취소", null)
            .show()
    }

    // ================= 뒤로가기 =================

    fun back(): Boolean {
        if (preview != null) { closePreview(); return true }
        if (root != null) { closeView(); return true }
        if (listRoot != null) { closeList(); return true }
        return false
    }

    // ================= 저장 =================

    private fun chosen() = job?.pics?.filter { it.selected && it.file.exists() } ?: emptyList()

    private fun saveZip() {
        val j = job ?: return
        val list = chosen()
        if (list.isEmpty()) { a.toastMsg("고른 이미지가 없어요"); return }
        zip(j, list, deleteAfter = false)
        a.toastMsg("압축 중… 끝나면 알림이 떠요")
    }

    /**
     * 받은 이미지 전체를 압축 (완료 후 자동 압축). deleteAfter면 압축이 끝난 뒤 작업과 임시 이미지를 지움.
     * 앱을 안 보고 있어도 동작.
     */
    fun zipAll(j: GrabJob, deleteAfter: Boolean) {
        val list = j.pics.filter { it.file.exists() }
        if (list.isEmpty()) return
        j.addLog("자동 압축 시작 · ${list.size}장" + if (deleteAfter) " · 끝나면 임시 이미지 삭제" else "")
        zip(j, list, deleteAfter)
    }

    private val hangul = Regex("[가-힣]")
    private val LONG_NAME = 50

    /**
     * 압축 파일 이름 고르기: 페이지 제목 → 작업 이름 순으로, 한국어가 있는 쪽을 우선.
     * "원피스 1화 - One Piece | 사이트"처럼 나뉘어 있으면 한국어가 많은 부분을 고름. 자르지 않고 전체 이름.
     */
    private fun niceName(j: GrabJob): String {
        fun best(s: String): String {
            val parts = s.split(Regex("\\s+[-–—|:·•]\\s+|\\s*[|｜]\\s*|\\s+::\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            val kor = parts.filter { hangul.containsMatchIn(it) }
            return (kor.maxByOrNull { p -> hangul.findAll(p).count() } ?: parts.firstOrNull() ?: s).trim()
        }
        val named = listOfNotNull(j.pageTitle, j.title).filter { it.isNotBlank() }.map { best(it) }
        val pick = named.firstOrNull { hangul.containsMatchIn(it) } ?: named.firstOrNull() ?: "images"
        return clean(pick)
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
        .replace(Regex("\\s+"), " ").trim().trimEnd('.').ifBlank { "images" }

    /** 이름이 길면(50자 넘음) 고칠 수 있게 물어봄. 앱을 안 보고 있으면 다음에 열 때 물어봄 */
    private fun ensureName(j: GrabJob, then: (String) -> Unit) {
        val n = j.zipName ?: niceName(j)
        if (n.length <= LONG_NAME) {
            then(n)
            return
        }
        if (!a.isShown) {
            j.askZip = true
            j.addLog("압축 파일 이름이 길어서(${n.length}자) 앱을 열 때 확인할게요")
            return
        }
        val et = android.widget.EditText(a).apply {
            setText(n)
            setSelection(n.length)
            textSize = 15f
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(4), a.dp(20), 0)
            addView(et)
        }
        AlertDialog.Builder(a)
            .setTitle("파일 이름이 길어요 (${n.length}자)")
            .setMessage("이대로 저장하거나, 고쳐서 저장할 수 있어요.")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val v = clean(et.text.toString())
                j.zipName = v
                then(v)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun zip(j: GrabJob, list: List<GrabJob.Pic>, deleteAfter: Boolean) {
        ensureName(j) { base -> doZip(j, list, deleteAfter, base) }
    }

    private fun doZip(j: GrabJob, list: List<GrabJob.Pic>, deleteAfter: Boolean, base: String) {
        val name = "$base.zip"
        val total = list.sumOf { it.file.length() }
        Downloads.saveWith(a, name, "application/zip", total, { out, progress ->
            var n = 0L
            ZipOutputStream(out).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)   // 이미지는 이미 압축돼 있어서 빠르게
                list.forEach { p ->
                    zip.putNextEntry(ZipEntry(p.file.name))
                    FileInputStream(p.file).use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            zip.write(buf, 0, r)
                            n += r
                            progress(n)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }) { item ->
            if (item.state != Downloads.DONE) {
                j.addLog("압축 실패 · ${item.error ?: ""}")
                return@saveWith
            }
            if (deleteAfter) {
                if (job === j) closeView()
                JobStore.remove(j)
                if (a.isShown) a.snackMsg("$name 저장 완료 · 받은 이미지는 지웠어요")
            } else askCleanup(j, "$name 저장했어요 (${list.size}개)")
        }
    }

    private fun saveEach() {
        val j = job ?: return
        val list = chosen()
        if (list.isEmpty()) { a.toastMsg("고른 이미지가 없어요"); return }
        val left = AtomicInteger(list.size)
        list.forEach { p ->
            val mime = when (p.file.extension.lowercase()) {
                "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }
            Downloads.saveWith(a, p.file.name.substringAfter('_'), mime, p.file.length(), { out, progress ->
                FileInputStream(p.file).use { it.copyTo(out, 64 * 1024) }
                progress(p.file.length())
            }) { if (left.decrementAndGet() == 0) askCleanup(j, "${list.size}개 저장했어요") }
        }
    }

    private fun askCleanup(j: GrabJob, msg: String) {
        if (!a.isShown) return
        AlertDialog.Builder(a)
            .setMessage("$msg\n\n이 작업을 지울까요? (임시로 받은 이미지가 지워져요)")
            .setPositiveButton("지우기") { _, _ ->
                if (job === j) closeView()
                JobStore.remove(j)
            }
            .setNegativeButton("남겨 두기", null)
            .show()
    }

    // ================= 크게 보기 =================

    @SuppressLint("ClickableViewAccessibility")
    private fun openPreview(index: Int) {
        val pics = job?.pics ?: return
        closePreview()
        var i = index
        val img = ImageView(a).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(a).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            setBackgroundColor(0x88000000.toInt())
        }
        fun show() {
            val p = pics.getOrNull(i) ?: return
            label.text = "${i + 1} / ${pics.size}   " + (if (p.selected) "선택됨" else "빠짐") + "   (왼쪽·오른쪽 눌러 넘기기)"
            thumbPool.execute {
                val b = decode(p.file, a.resources.displayMetrics.widthPixels)
                main.post { if (preview != null) img.setImageBitmap(b) }
            }
        }
        val box = FrameLayout(a).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        box.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.x / v.width
                when {
                    x < 0.33f && i > 0 -> { i--; show() }
                    x > 0.67f && i < pics.size - 1 -> { i++; show() }
                    x in 0.33f..0.67f -> closePreview()
                }
            }
            true
        }
        a.frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview = box
        show()
    }

    private fun closePreview() {
        preview?.let { a.frame.removeView(it) }
        preview = null
    }

    // ================= 격자 =================

    private fun decode(f: File, target: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) {
        null
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val pics get() = job?.pics ?: emptyList<GrabJob.Pic>()

        override fun getItemCount() = pics.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = FrameLayout(a)
            val img = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(a.getColor(R.color.field))
                id = 1
            }
            val mark = TextView(a).apply {
                id = 2
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val num = TextView(a).apply {
                id = 3
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(a.dp(5), a.dp(1), a.dp(5), a.dp(1))
                setBackgroundColor(0x99000000.toInt())
            }
            cell.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(num, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            cell.addView(mark, FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.TOP or Gravity.END).apply {
                topMargin = a.dp(6); rightMargin = a.dp(6)
            })
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val p = pics.getOrNull(position) ?: return
            val cell = h.itemView as FrameLayout
            val w = (grid?.width?.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels) /
                ((grid?.layoutManager as? GridLayoutManager)?.spanCount ?: 3)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (w * 1.3f).toInt()).apply {
                setMargins(a.dp(3), a.dp(3), a.dp(3), a.dp(3))
            }
            val img = cell.findViewById<ImageView>(1)
            val mark = cell.findViewById<TextView>(2)
            val num = cell.findViewById<TextView>(3)
            num.text = (position + 1).toString()
            img.alpha = if (p.selected) 1f else 0.3f
            mark.text = if (p.selected) "✓" else ""
            mark.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (p.selected) setColor(a.getColor(R.color.accent)) else {
                    setColor(0x66000000)
                    setStroke(a.dp(2), Color.WHITE)
                }
            }
            val key = p.file.absolutePath
            img.tag = key
            val cached = cache.get(key)
            if (cached != null) img.setImageBitmap(cached) else {
                img.setImageDrawable(null)
                thumbPool.execute {
                    val b = decode(p.file, 300) ?: return@execute
                    cache.put(key, b)
                    main.post { if (img.tag == key) img.setImageBitmap(b) }
                }
            }
            cell.setOnClickListener {
                if (job?.state != GrabJob.DONE) return@setOnClickListener
                p.selected = !p.selected
                notifyItemChanged(h.bindingAdapterPosition)
                updateCount()
            }
            cell.setOnLongClickListener {
                openPreview(h.bindingAdapterPosition)
                true
            }
        }
    }
}
