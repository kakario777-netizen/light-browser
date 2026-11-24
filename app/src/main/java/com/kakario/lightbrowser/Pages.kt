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
        if (v.tag == "advanced") {
            ModelStore.listeners.remove(modelListener)
            modelListener = {}
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

    /** 섹션마다 둥근 카드 하나: 그 아래 줄들은 카드 안에 (구분선으로 나눔) */
    private val cards = java.util.WeakHashMap<LinearLayout, LinearLayout>()

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(a).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(28), a.dp(22), a.dp(20), a.dp(8))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = a.dp(16).toFloat()
                setColor(a.getColor(R.color.field))
            }
            clipToOutline = true
        }
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(a.dp(14), 0, a.dp(14), a.dp(2))
        })
        cards[parent] = card
    }

    private fun row(parent: LinearLayout, title: String, sub: String?, right: View?, onClick: (() -> Unit)?): TextView? {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(18), a.dp(14), a.dp(14), a.dp(14))
            minimumHeight = a.dp(58)
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
            a.marquee(this)
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
        val card = cards[parent]
        if (card != null) {
            if (card.childCount > 0) card.addView(View(a).apply { setBackgroundColor(a.getColor(R.color.line)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(a.dp(18), 0, a.dp(18), 0) })
            card.addView(r)
        } else parent.addView(r)
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
        box.addView(FastScroll.wrap(ScrollView(a).apply { addView(body) }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val st = a.store

        section(body, "광고 · 차단")
        switchRow(body, "광고 차단", "광고 서버·광고 칸·광고 팝업을 막아요", a.adblockOn) { a.setAdblock(it) }
        switchRow(body, "차단 우회 (보안 DNS)", "막힌 사이트 접속을 시도해요", st.bypass) {
            st.bypass = it
            a.applyBypass(it, reload = true)
        }

        section(body, "다운로드")
        row(body, "다운로드 · 받기 설정", "아래쪽 다운로드 버튼에 모아 뒀어요 (폴더·압축·예약·FTP)", null) { back(); openDownloadCenter() }

        section(body, "화면 · 검색")
        var zoomSub: TextView? = null
        zoomSub = row(body, "웹페이지 글자 크기", "${st.textZoom}%", null) {
            val opts = intArrayOf(80, 90, 100, 110, 120, 130, 150)
            AlertDialog.Builder(a)
                .setTitle("웹페이지 글자 크기")
                .setSingleChoiceItems(opts.map { "$it%" + if (it == 100) " (기본)" else "" }.toTypedArray(),
                    opts.indexOf(st.textZoom)) { d, w ->
                    a.setTextZoom(opts[w])
                    zoomSub?.text = "${opts[w]}%"
                    d.dismiss()
                }.show()
        }
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

        section(body, "앱 정보")
        row(body, "업데이트 확인", "지금 빌드 ${a.updater.myBuild()}", null) { a.updater.check(silent = false) }
        switchRow(body, "자동으로 업데이트 확인", "앱을 켤 때 새 버전이 있으면 알려줘요", st.autoUpdate) { st.autoUpdate = it }

        section(body, "그 밖에")
        row(body, "자세한 설정", "실시간 만화 번역 모델 고르기·받기", null) { openAdvanced() }
    }

    // ================= 자세한 설정 =================

    private var modelListener: () -> Unit = {}

    fun openAdvanced() {
        val box = page("자세한 설정", "advanced")
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(24))
        }
        box.addView(FastScroll.wrap(ScrollView(a).apply { addView(body) }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val st = a.store
        val refreshers = ArrayList<() -> Unit>()

        section(body, "실시간 만화 번역")
        row(body, "인식 언어", "일본어 → 한국어\n(영어·중국어·스페인어는 다음에 추가)", null, null)

        // ---- 글자 인식 ----
        section(body, "글자 인식 (OCR)")
        choice(body, refreshers,
            "일반 인식", "구글 ML Kit · 처음 켤 때 자동으로 받음 · 빠름 · 손글씨풍·세로쓰기는 놓칠 수 있어요",
            { st.ocrEngine == 0 }) { st.ocrEngine = 0; a.live.modelChanged() }
        choice(body, refreshers,
            "만화 전용 인식", "manga-ocr · ${ModelStore.MANGA_OCR.sizeText} · 세로쓰기·손글씨풍에 강함 · 말풍선마다 조금 느림",
            { st.ocrEngine == 1 }) {
            if (!ModelStore.MANGA_OCR.ready(a)) {
                needDownload(ModelStore.MANGA_OCR) { st.ocrEngine = 1; a.live.modelChanged(); refreshers.forEach { it() } }
                return@choice
            }
            st.ocrEngine = 1
            a.live.modelChanged()
        }

        // ---- 번역 ----
        section(body, "일본 만화 번역에 쓸 모델")
        choice(body, refreshers,
            "일반 모델 (가벼움)", "구글 ML Kit · ${ModelStore.LIGHT_SIZE} · 빠름 · 뜻은 통하지만 말투가 어색할 수 있어요",
            { st.transModel == 0 }) { st.transModel = 0; a.live.modelChanged() }
        choice(body, refreshers,
            "고급 모델 (무거움)", "Qwen2.5 1.5B · ${ModelStore.QWEN.sizeText} · 더 자연스러움 · 한 페이지에 몇 초 걸려요",
            { st.transModel == 1 }) {
            if (!ModelStore.QWEN.ready(a)) {
                needDownload(ModelStore.QWEN) { st.transModel = 1; a.live.modelChanged(); refreshers.forEach { it() } }
                return@choice
            }
            st.transModel = 1
            a.live.modelChanged()
        }

        // ---- 받기·지우기 ----
        section(body, "모델 받기 · 지우기")
        var lightSub: TextView? = null
        lightSub = row(body, "일반 번역 모델", "확인 중…", null) {
            ModelStore.lightReady { ok ->
                if (ok) {
                    AlertDialog.Builder(a).setMessage("일반 번역 모델을 지울까요? 실시간 번역을 다시 켜면 다시 받아요.")
                        .setPositiveButton("지우기") { _, _ ->
                            ModelStore.deleteLight { lightSub?.text = "안 받음 · ${ModelStore.LIGHT_SIZE}" }
                        }.setNegativeButton("취소", null).show()
                } else {
                    lightSub?.text = "받는 중…"
                    ModelStore.downloadLight { done, err ->
                        lightSub?.text = if (done) "받음 · 눌러서 지우기" else "받기 실패: ${err ?: ""}"
                    }
                }
            }
        }
        ModelStore.lightReady { ok -> lightSub?.text = if (ok) "받음 · 눌러서 지우기" else "안 받음 · ${ModelStore.LIGHT_SIZE} · 눌러서 받기" }

        packRow(body, refreshers, ModelStore.MANGA_OCR) {
            if (st.ocrEngine == 1) { st.ocrEngine = 0; a.live.modelChanged() }
        }
        packRow(body, refreshers, ModelStore.QWEN) {
            if (st.transModel == 1) { st.transModel = 0; a.live.modelChanged() }
        }

        refreshers.forEach { it() }
        modelListener = { refreshers.forEach { it() } }
        ModelStore.listeners.add(modelListener)
    }

    /** 고르기 줄 (●/○) */
    private fun choice(body: LinearLayout, refreshers: MutableList<() -> Unit>, title: String, sub: String,
                       isOn: () -> Boolean, pick: () -> Unit) {
        val mark = TextView(a).apply {
            textSize = 20f
            setTextColor(a.getColor(R.color.accent))
            setPadding(a.dp(12), 0, a.dp(4), 0)
        }
        row(body, title, sub, mark) {
            pick()
            refreshers.forEach { it() }
        }
        refreshers.add { mark.text = if (isOn()) "●" else "○" }
    }

    /** 아직 안 받은 모델을 고르면: 받을지 묻고, 다 받으면 then */
    private fun needDownload(pack: ModelStore.Pack, then: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("${pack.title} 받기")
            .setMessage("${pack.title}을(를) 아직 받지 않았어요 (${pack.sizeText}).\n와이파이에서 받는 걸 권해요. 받는 동안 다른 걸 해도 되고, 끊기면 이어서 받아요.\n다 받으면 바로 이 모델로 바꿀게요.")
            .setPositiveButton("받기") { _, _ ->
                a.askBackgroundPermissions()
                pack.onDone = { ok ->
                    if (ok) {
                        a.toastMsg("${pack.title}을(를) 다 받았어요")
                        then()
                    }
                }
                ModelStore.download(a, pack)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 큰 모델 받기·멈추기·지우기 줄 + 진행 막대 */
    private fun packRow(body: LinearLayout, refreshers: MutableList<() -> Unit>, pack: ModelStore.Pack, onDeleted: () -> Unit) {
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(a.getColor(R.color.accent))
            visibility = View.GONE
        }
        var sub: TextView? = null
        sub = row(body, pack.title, "", null) {
            when {
                pack.downloading -> ModelStore.cancel(pack)
                pack.ready(a) -> AlertDialog.Builder(a)
                    .setMessage("${pack.title}(${Downloads.size(pack.readySize(a))})을(를) 지울까요?")
                    .setPositiveButton("지우기") { _, _ ->
                        ModelStore.delete(a, pack)
                        onDeleted()
                        refreshers.forEach { it() }
                    }.setNegativeButton("취소", null).show()
                else -> {
                    a.askBackgroundPermissions()
                    ModelStore.download(a, pack)
                }
            }
        }
        body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(6)).apply {
            leftMargin = a.dp(20); rightMargin = a.dp(20); bottomMargin = a.dp(8)
        })
        refreshers.add {
            val part = pack.partialSize(a)
            sub?.text = when {
                pack.downloading -> "받는 중 ${pack.percent()}% · ${Downloads.size(pack.done)} / ${pack.sizeText} · 눌러서 멈추기"
                pack.ready(a) -> "받음 · ${Downloads.size(pack.readySize(a))} · 눌러서 지우기"
                pack.error != null -> pack.error!!
                part > 0 -> "받다 멈춤 · ${Downloads.size(part)} · 눌러서 이어받기"
                else -> "안 받음 · ${pack.sizeText} · 눌러서 받기 (와이파이 권장)"
            }
            bar.visibility = if (pack.downloading) View.VISIBLE else View.GONE
            bar.progress = pack.percent()
        }
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

    // ================= 받다 멈춘 작업 =================

    /** 받다 멈춘 작업 목록: 저장해 둔 넘기며 모으기 + 지금 멈춰 있는 것 + 실패가 남은 받기 */
    fun openStopped() {
        val box = page("받다 멈춘 작업", "stopped")
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(24))
        }
        box.addView(FastScroll.wrap(ScrollView(a).apply { addView(body) }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val fmt = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA)
        var any = false

        val active = a.activeCollectIds()
        val saves = CollectSaves.list().filter { it.id !in active }
        if (saves.isNotEmpty()) {
            any = true
            section(body, "저장해 둔 넘기며 모으기 (${saves.size})")
            saves.forEach { s ->
                val extra = listOfNotNull(
                    if (s.failed > 0) "실패 ${s.failed}" else null,
                    if (s.dups > 0) "중복 건너뜀 ${s.dups}" else null,
                ).joinToString(" · ")
                val page = android.net.Uri.parse(s.page).let { (it.host ?: "") + (it.path ?: "") }.take(60)
                row(body, s.title,
                    "받은 ${s.count}장" + (if (extra.isNotEmpty()) " · $extra" else "") + " · ${fmt.format(java.util.Date(s.savedAt))} 저장\n멈춘 곳: $page",
                    null) {
                    android.app.AlertDialog.Builder(a)
                        .setCustomTitle(a.titleView(s.title))
                        .setItems(arrayOf("받은 이미지 보기 (${s.count}장)", "이어 받기 · 정리 · 버리기")) { _, w ->
                            if (w == 0) {
                                val g = JobStore.peek(s)
                                if (g == null) a.toastMsg("받아 둔 파일을 찾을 수 없어요") else a.viewGrabJob(g)
                            } else {
                                back()
                                a.chooseSaved(s)
                            }
                        }
                        .setNegativeButton("닫기", null).show()
                }
            }
        }

        val paused = a.collectJobsInfo().filter { it.paused }
        if (paused.isNotEmpty()) {
            any = true
            section(body, "지금 멈춰 있는 모으기 (${paused.size})")
            paused.forEach { c ->
                row(body, c.title, c.status, null) {
                    android.app.AlertDialog.Builder(a)
                        .setCustomTitle(a.titleView(c.title))
                        .setItems(arrayOf("그 탭으로 가기", "이어 넘기기", "여기까지로 끝")) { _, w ->
                            back()
                            when (w) { 0 -> c.goTo(); 1 -> { c.goTo(); c.resume() }; else -> c.end() }
                        }
                        .setNegativeButton("닫기", null).show()
                }
            }
        }

        val failedJobs = JobStore.jobs.filter { !it.active && it.fails > 0 }
        if (failedJobs.isNotEmpty()) {
            any = true
            section(body, "다 못 받은 작업 (${failedJobs.size})")
            failedJobs.forEach { g ->
                row(body, g.title, "받은 ${g.pics.size}장 · 실패 ${g.fails} (${g.failSummary()})", null) {
                    a.viewGrabJob(g)
                }
            }
        }

        if (!any) {
            body.addView(TextView(a).apply {
                text = "받다 멈춘 작업이 없어요.\n\n넘기며 모으기에서 '저장하고 그만'을 누르거나, 받다가 멈추면 여기에 나와요."
                textSize = 15f
                setTextColor(a.getColor(R.color.sub))
                setPadding(a.dp(24), a.dp(32), a.dp(24), a.dp(16))
            })
        }
    }

    // ================= 예약 저장 목록 =================

    fun openSchedules() {
        val box = page("전부 받기 · 예약", "sched")
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(24))
        }
        box.addView(FastScroll.wrap(ScrollView(a).apply { addView(body) }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val fmt = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA)
        val items = Sched.list(a)
        if (items.isEmpty()) {
            body.addView(TextView(a).apply {
                text = "예약한 페이지가 없어요.\n\n그림을 모으고 싶은 페이지를 열고 메뉴 › '이 페이지 예약 저장'을 누르면, 화면이 꺼져 있어도 정한 때마다 새 그림만 저장해요."
                textSize = 15f
                setTextColor(a.getColor(R.color.sub))
                setPadding(a.dp(24), a.dp(32), a.dp(24), a.dp(16))
            })
            return
        }
        val fulls = items.filter { it.full }
        if (fulls.isNotEmpty()) {
            section(body, "목록 전부 받기 ${fulls.size}개")
            fulls.forEach { it ->
                val running = it.bfPage != 0
                row(body, it.name, (if (running) "받는 중 · " else "") + it.bfText.ifEmpty { "대기" } + " · 누적 ${it.total}장" +
                    (if (it.zip) " · 압축" else "") + (if (it.ftp) " · FTP" else ""), null) {
                    val acts = listOf<Pair<String, () -> Unit>>(
                        (if (running) "멈추기" else "이어서 받기 (받은 글은 건너뜀)") to {
                            if (running) Sched.stopBackfill(a, it.id) else Sched.startBackfill(a, it.id)
                            back(); openSchedules()
                        },
                        "페이지 열기" to { back(); a.openUrl(it.url) },
                        "목록에서 지우기 (받은 그림은 남아요)" to { Sched.remove(a, it.id); back(); openSchedules() },
                    )
                    android.app.AlertDialog.Builder(a)
                        .setCustomTitle(a.titleView(it.name))
                        .setItems(acts.map { x -> x.first }.toTypedArray()) { _, w -> acts[w].second() }
                        .setNegativeButton("닫기", null).show()
                }
            }
        }
        val scheds = items.filter { !it.full }
        if (scheds.isNotEmpty()) section(body, "예약 ${scheds.size}개 · 다운로드/라이트 브라우저/ 에 저장")
        scheds.forEach { it ->
            val state = (if (it.mode == 1) "새 글 받기 · " else "이 페이지 그림 · ") + if (!it.enabled) "꺼짐" else it.whenText() + (if (it.nextAt > 0) " · 다음 ${fmt.format(java.util.Date(it.nextAt))}" else "")
            val bf = ""
            val last = (if (it.mode == 1 && it.zip) " · 압축" else "") + (if (it.ftp) " · FTP" else "") + bf + if (it.lastRun > 0) "\n마지막 ${fmt.format(java.util.Date(it.lastRun))}: ${it.lastResult} · 누적 ${it.total}장" else "\n아직 안 돌았어요"
            row(body, it.name, state + last, null) {
                val acts = listOf<Pair<String, () -> Unit>>(
                    "지금 한 번 받기" to { Sched.plan(a, it, 1000L); a.toastMsg("곧 받기 시작해요 (알림으로 알려드려요)") },
                    (if (it.enabled) "예약 끄기" else "예약 켜기") to {
                        Sched.update(a, it.id) { x -> x.enabled = !x.enabled }
                        Sched.list(a).firstOrNull { x -> x.id == it.id }?.let { x -> Sched.plan(a, x) }
                        back(); openSchedules()
                    },
                    *(if (it.mode != 1) emptyArray() else arrayOf<Pair<String, () -> Unit>>(
                        (if (it.zip) "글마다 압축 끄기 (그림 낱장으로 저장)" else "글마다 압축(zip)으로 저장·올리기") to {
                            Sched.update(a, it.id) { x -> x.zip = !x.zip }; back(); openSchedules()
                        })),
                    (if (it.ftp) "FTP로 올리기 끄기" else "FTP로도 올리기") to {
                        if (!it.ftp && !Ftp.conf(a).ok) { a.toastMsg("설정 › 예약 저장 FTP에서 서버를 먼저 넣어 주세요"); a.editFtp() }
                        else { Sched.update(a, it.id) { x -> x.ftp = !x.ftp }; back(); openSchedules() }
                    },
                    "언제 확인할지 바꾸기 (지금: ${it.whenText()})" to {
                        a.pickInterval(it.name, it.times) { every, times ->
                            Sched.update(a, it.id) { x -> x.everyMin = every; x.times = times }
                            Sched.list(a).firstOrNull { x -> x.id == it.id }?.let { x -> Sched.plan(a, x) }
                            back(); openSchedules()
                        }
                    },
                    "페이지 열기" to { back(); a.openUrl(it.url) },

                    "예약 지우기 (받은 그림은 남아요)" to {
                        android.app.AlertDialog.Builder(a)
                            .setMessage("'${it.name}' 예약을 지울까요? 이미 받은 그림은 그대로 남아요.")
                            .setPositiveButton("지우기") { _, _ -> Sched.remove(a, it.id); back(); openSchedules() }
                            .setNegativeButton("취소", null).show()
                    },
                )
                android.app.AlertDialog.Builder(a)
                    .setCustomTitle(a.titleView(it.name))
                    .setItems(acts.map { x -> x.first }.toTypedArray()) { _, w -> acts[w].second() }
                    .setNegativeButton("닫기", null)
                    .show()
            }
        }
    }

    // ================= 다운로드 (아래 다운로드 버튼) =================

    fun openDownloadCenter() {
        val box = page("다운로드", "dlcenter")
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(28))
        }
        box.addView(FastScroll.wrap(ScrollView(a).apply { addView(body) }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val st = a.store
        val jobs = JobStore.jobs
        val active = jobs.count { it.active }
        val saves = CollectSaves.list().size
        val scheds = Sched.list(a)

        section(body, "지금 보는 페이지에서")
        row(body, "페이지 넘기며 이미지 모으기", "한 장씩 넘겨 보는 만화·뷰어 페이지", null) { back(); a.menuAction(12) }
        row(body, "이미지 폴더 받기", "페이지 속 그림 폴더를 찾아 한 번에", null) { back(); a.menuAction(11) }
        row(body, "이 목록 전부 받기 (옛날 글부터)", "목록 마지막 쪽 맨 아래부터 1쪽 맨 위까지 · 화면 꺼져도 계속", null) { back(); a.menuAction(17) }
        row(body, "이 페이지 예약 저장", "정한 때마다 새 글·새 그림만 저장", null) { back(); a.menuAction(15) }

        section(body, "받는 중 · 받은 것")
        row(body, "작업 현황", if (active > 0) "받는 중 ${active}개 · 모두 ${jobs.size}개" else "이미지 받기 작업 ${jobs.size}개", null) { a.menuAction(18) }
        row(body, "받다 멈춘 작업", if (saves > 0) "저장해 둔 작업 ${saves}개" else "멈춘 작업 이어 받기·정리", null) { openStopped() }
        row(body, "전부 받기 · 예약 목록", "전부 받기 ${scheds.count { it.full }}개 · 예약 ${scheds.count { !it.full }}개", null) { openSchedules() }
        row(body, "파일 다운로드", "받는 중인 파일과 받은 파일", null) { openDownloads() }

        section(body, "저장 · 압축")
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
        val zipNames = arrayOf("물어보기", "자동으로 압축하고 이미지 지우기", "그냥 두기")
        var zipSub: TextView? = null
        zipSub = row(body, "이미지 다 받으면 압축 (실패가 없을 때)", zipNames[st.autoZip.coerceIn(0, 2)], null) {
            AlertDialog.Builder(a)
                .setTitle("다 받으면 압축")
                .setSingleChoiceItems(zipNames, st.autoZip.coerceIn(0, 2)) { d, w ->
                    st.autoZip = w
                    zipSub?.text = zipNames[w]
                    d.dismiss()
                }.show()
        }
        switchRow(body, "다운로드 차단 해제", "광고 차단에 걸리는 주소라도 파일은 받고, 사이트 안에서 만든 파일(blob)도 저장해요",
            st.unblockDownloads) { st.unblockDownloads = it }

        section(body, "FTP 올리기")
        row(body, "FTP 서버", Ftp.conf(a).let { c -> if (c.ok) "${c.host}:${c.port} · ${c.dir} · ${c.enc}" else "설정 안 됨 · 넣으면 예약·전부 받기 결과를 FTP로도 올려요" }, null) { a.editFtp() }

        section(body, "화면 꺼져도 계속 받기")
        row(body, "미니 창 (홈에서도 계속 모으기)",
            if (a.mini.canUse()) "허용됨 · 앱을 나가면 모으는 페이지가 작은 창으로 떠요" else "꺼짐 · 눌러서 '다른 앱 위에 표시' 허용", null) {
            a.mini.askPermission()
        }
        row(body, "배터리 최적화에서 빼기", "화면이 꺼져도 작업이 끊기지 않게", null) {
            try {
                @android.annotation.SuppressLint("BatteryLife")
                val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + a.packageName))
                a.startActivity(i)
            } catch (e: Exception) {
                a.toastMsg("설정 화면을 열 수 없어요")
            }
        }

        section(body, "문제 확인")
        row(body, "받기 테스트 (지금 보던 페이지)", "목록이면 맨 아래 글 하나, 글이면 그 글을 화면 없이 받아 봐요", null) { back(); a.schedTest() }
        row(body, "지난 테스트 기록", "복사해서 채팅에 붙여 주세요", null) { a.showTestLog() }
    }

    fun openDownloads() {
        val box = page("다운로드", "downloads")
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(24))
        }
        box.addView(FastScroll.wrap(ScrollView(a).apply { addView(body) }),
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
