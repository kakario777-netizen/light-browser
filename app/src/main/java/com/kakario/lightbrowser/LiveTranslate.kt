package com.kakario.lightbrowser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.Executors

/**
 * 실시간 만화 번역 (일본어 → 한국어, 인터넷 없이).
 * 화면에 보이는 페이지를 찍어 → 글자 인식(ML Kit 일본어) → 번역(일반: ML Kit / 고급: Qwen) →
 * 말풍선 자리에 한글을 덮어 보여준다. 넘기거나 굴리면 다시 한다.
 */
class LiveTranslate(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val llmThread = Executors.newSingleThreadExecutor()

    var isOn = false
        private set
    private var overlay: Overlay? = null
    private var recognizer: TextRecognizer? = null
    private var mlkit: Translator? = null
    @Volatile private var llm: LlmInference? = null
    private val cache = HashMap<String, String>()        // 원문 → 번역
    private var busy = false
    private var again = false
    private var gen = 0
    private var lastSig = ""
    private var heavyFailed = false
    private var mangaFailed = false
    private val ocrThread = Executors.newSingleThreadExecutor()
    @Volatile private var manga: MangaOcr? = null

    // ================= 켜고 끄기 =================

    fun toggle() = if (isOn) stop() else start()

    fun start() {
        ModelStore.lightReady { ok ->
            if (ok) begin() else askFirstDownload()
        }
    }

    /** 처음 켤 때: 번역에 필요한 모델을 받는다는 안내 */
    private fun askFirstDownload() {
        AlertDialog.Builder(a)
            .setTitle("실시간 번역 처음 켜기")
            .setMessage(
                "처음 한 번, 일본어 글자 인식과 번역에 필요한 모델을 받아요 (${ModelStore.LIGHT_SIZE}).\n" +
                    "와이파이에서 받는 걸 권해요. 받은 뒤에는 인터넷 없이 번역돼요.\n\n" +
                    "더 정확한 만화 전용 글자 인식(${ModelStore.MANGA_OCR.sizeText})과 더 자연스러운 고급 번역 모델(${ModelStore.QWEN.sizeText})은 설정 › 자세한 설정에서 따로 받을 수 있어요."
            )
            .setPositiveButton("받기") { _, _ -> downloadFirst() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun downloadFirst() {
        val dlg = AlertDialog.Builder(a)
            .setTitle("번역 모델 받는 중")
            .setMessage("번역 모델을 받고 있어요…")
            .setCancelable(false)
            .show()
        ModelStore.downloadLight { ok, err ->
            if (!ok) {
                dlg.dismiss()
                AlertDialog.Builder(a).setMessage("번역 모델을 받지 못했어요.\n${err ?: ""}\n인터넷 연결을 확인하고 다시 켜 주세요.")
                    .setPositiveButton("확인", null).show()
                return@downloadLight
            }
            dlg.setMessage("글자 인식 모듈을 준비하고 있어요… (구글 플레이 서비스가 받아요)")
            warmUpOcr(System.currentTimeMillis()) { ready ->
                dlg.dismiss()
                if (ready) {
                    a.toastMsg("준비됐어요. 이제 만화 페이지를 보면 번역돼요")
                    begin()
                } else {
                    AlertDialog.Builder(a)
                        .setMessage("글자 인식 모듈을 아직 받지 못했어요. 조금 뒤 다시 켜 주세요.\n(구글 플레이 서비스가 뒤에서 받고 있을 수 있어요)")
                        .setPositiveButton("확인", null).show()
                }
            }
        }
    }

    /** 글자 인식 모듈이 받아질 때까지 작은 그림으로 시험 (최대 2분) */
    private fun warmUpOcr(startAt: Long, done: (Boolean) -> Unit) {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        recognizerOrCreate().process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { done(true) }
            .addOnFailureListener {
                if (System.currentTimeMillis() - startAt > 120_000) done(false)
                else main.postDelayed({ warmUpOcr(startAt, done) }, 3000)
            }
    }

    private fun recognizerOrCreate(): TextRecognizer =
        recognizer ?: TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).also { recognizer = it }

    private fun mlkitOrCreate(): Translator =
        mlkit ?: Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(TranslateLanguage.KOREAN).build()
        ).also { mlkit = it }

    private fun begin() {
        isOn = true
        heavyFailed = false
        mangaFailed = false
        attachOverlay()
        a.toastMsg("실시간 번역 켬 · 글자 인식 " + (if (useManga()) "만화 전용" else "일반") + " · 번역 " + if (useHeavy()) "고급 모델" else "일반 모델")
        lastSig = ""
        main.removeCallbacks(poll)
        main.postDelayed(poll, 400)
        schedule(300)
    }

    fun stop() {
        isOn = false
        gen++
        main.removeCallbacks(poll)
        main.removeCallbacks(runner)
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        val l = llm
        llm = null
        if (l != null) llmThread.execute { try { l.close() } catch (e: Exception) {} }
        val m = manga
        manga = null
        if (m != null) ocrThread.execute { m.close() }
        a.toastMsg("실시간 번역 끔")
    }

    /** 고급 모델을 쓸지 (설정에서 골랐고 받아 두었을 때) */
    private fun useHeavy() = a.store.transModel == 1 && ModelStore.QWEN.ready(a) && !heavyFailed

    /** 만화 전용 글자 인식을 쓸지 (설정에서 골랐고 받아 두었을 때) */
    private fun useManga() = a.store.ocrEngine == 1 && ModelStore.MANGA_OCR.ready(a) && !mangaFailed

    /** 설정에서 모델을 바꿨을 때 */
    fun modelChanged() {
        cache.clear()
        heavyFailed = false
        mangaFailed = false
        if (isOn) {
            overlay?.clear()
            schedule(200)
        }
    }

    // ================= 언제 다시 할지 =================

    private fun attachOverlay() {
        val content = a.contentView()
        if (overlay?.parent === content) return
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val o = Overlay(a)
        content.addView(o, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = o
    }

    /** 탭을 바꿨을 때 (탭 화면이 새로 붙으므로 덮개도 다시 맨 위로) */
    fun onTabChanged() {
        if (!isOn) return
        gen++
        attachOverlay()
        overlay?.clear()
        lastSig = ""
        schedule(500)
    }

    /** 페이지를 굴렸을 때: 덮개를 지우고 멈추면 다시 */
    fun onScroll(w: WebView) {
        if (!isOn || w !== a.currentWebView()) return
        if (overlay?.hasBoxes() == true) {
            gen++
            overlay?.clear()
        }
        schedule(700)
    }

    fun onPageChanged(w: WebView) {
        if (!isOn || w !== a.currentWebView()) return
        gen++
        overlay?.clear()
        schedule(900)
    }

    private val runner = Runnable { run() }

    private fun schedule(ms: Long) {
        main.removeCallbacks(runner)
        main.postDelayed(runner, ms)
    }

    /** 굴리지 않고 넘기는 만화(그림만 바뀜)를 위해: 화면의 그림이 바뀌었는지 주기적으로 확인 */
    private val poll = object : Runnable {
        override fun run() {
            if (!isOn) return
            val w = a.currentWebView()
            if (w != null && a.isShown) {
                val js = "(function(){var o=[Math.round(scrollX),Math.round(scrollY)],W=innerWidth,H=innerHeight;" +
                    "document.querySelectorAll('img,canvas').forEach(function(i){var r=i.getBoundingClientRect();" +
                    "if(r.width*r.height>W*H*0.1&&r.bottom>0&&r.top<H)o.push((i.currentSrc||i.src||'c')+'@'+Math.round(r.top));});" +
                    "return o.join('|');})()"
                w.evaluateJavascript(js) { sig ->
                    if (isOn && sig != null && sig != lastSig) {
                        val first = lastSig.isEmpty()
                        lastSig = sig
                        if (!first) {
                            gen++
                            overlay?.clear()
                            schedule(600)
                        }
                    }
                }
            }
            main.postDelayed(this, 1200)
        }
    }

    // ================= 인식 → 번역 → 덮기 =================

    /**
     * 번역할 덩어리 하나.
     * rect: 원문 글자가 차지한 자리(줄 상자들을 합친 것), vertical: 세로쓰기인지, charPx: 원문 글자 한 개 크기(화면 픽셀)
     */
    private class Box(val rect: Rect, val text: String, val vertical: Boolean, val charPx: Float) {
        fun withText(t: String) = Box(rect, t, vertical, charPx)
    }

    /** 말풍선 바탕색: 글자 자리 둘레의 색을 여러 점 찍어 가운데 값 (글자색은 바탕 밝기에 따라 검정/흰색) */
    private fun sampleColors(bmp: Bitmap, b: Box): Pair<Int, Int> {
        val ring = RectF(b.rect)
        val m = b.charPx * 0.2f + 2f
        ring.inset(-m, -m)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun take(x: Float, y: Float) {
            val xi = x.toInt().coerceIn(0, bmp.width - 1)
            val yi = y.toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(xi, yi)
            rs.add((c shr 16) and 0xff); gs.add((c shr 8) and 0xff); bs.add(c and 0xff)
        }
        val n = 16
        for (i in 0..n) {
            val t = i / n.toFloat()
            take(ring.left + ring.width() * t, ring.top)
            take(ring.left + ring.width() * t, ring.bottom)
            take(ring.left, ring.top + ring.height() * t)
            take(ring.right, ring.top + ring.height() * t)
        }
        rs.sort(); gs.sort(); bs.sort()
        val mid = rs.size / 2
        val bg = Color.rgb(rs[mid], gs[mid], bs[mid])
        val lum = (0.299 * rs[mid] + 0.587 * gs[mid] + 0.114 * bs[mid]) / 255.0
        val fg = if (lum > 0.55) 0xFF141414.toInt() else Color.WHITE
        return Pair(bg, fg)
    }

    private fun run() {
        if (!isOn) return
        val w = a.currentWebView() ?: return
        if (!a.canSeePage()) {
            schedule(1500)
            return
        }
        if (busy) {
            again = true
            return
        }
        busy = true
        val myGen = ++gen
        val o = overlay
        o?.visibility = View.INVISIBLE   // 덮개 없이 찍기 (이전 번역이 다시 읽히지 않게)
        // 덮개가 사라진 화면이 실제로 그려진 뒤(두 번째 프레임)에 찍음
        val shoot = Runnable {
            Thumbs.capture(a.window, w, 1f, null) { bmp ->
                o?.visibility = View.VISIBLE
                if (bmp == null || !isOn || myGen != gen) {
                    finishRun()
                    return@capture
                }
                o?.setStatus("글자 찾는 중…")
                recognizerOrCreate().process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { text ->
                        if (!isOn || myGen != gen) { finishRun(); return@addOnSuccessListener }
                        val found = pickBoxes(text, loose = useManga())
                        if (found.isEmpty()) {
                            o?.setStatus(null)
                            o?.setItems(emptyList())
                            finishRun()
                            return@addOnSuccessListener
                        }
                        // 만화 전용 인식: ML Kit이 찾은 자리를 잘라 manga-ocr로 다시 읽음
                        rereadWithManga(found, bmp, myGen) { boxes ->
                            if (!isOn || myGen != gen || boxes.isEmpty()) {
                                if (isOn && myGen == gen) { o?.setStatus(null); o?.setItems(emptyList()) }
                                finishRun()
                                return@rereadWithManga
                            }
                            translate(boxes, myGen) { results ->
                                if (isOn && myGen == gen) {
                                    o?.setStatus(null)
                                    o?.setItems(boxes.mapIndexed { i, b ->
                                        val (bg, fg) = sampleColors(bmp, b)
                                        Item(b.rect, results[i], b.vertical, b.charPx, bg, fg)
                                    })
                                }
                                finishRun()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        o?.setStatus("글자 인식 준비 중… (${e.message?.take(40) ?: "잠시 뒤 다시"})")
                        finishRun()
                        schedule(3000)
                    }
            }
        }
        if (o != null) o.postOnAnimation { o.postOnAnimation(shoot) } else main.post(shoot)
    }

    private fun finishRun() {
        busy = false
        if (again) {
            again = false
            schedule(200)
        }
    }

    private val jp = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")

    /**
     * 만화 전용 인식을 쓰면 각 자리를 잘라 manga-ocr로 다시 읽는다 (백그라운드).
     * 실패하면 ML Kit 결과를 그대로 씀.
     */
    private fun rereadWithManga(boxes: List<Box>, bmp: Bitmap, myGen: Int, done: (List<Box>) -> Unit) {
        if (!useManga()) {
            done(boxes.filter { jp.findAll(it.text).count() >= 2 })
            return
        }
        overlay?.setStatus("만화 전용으로 글자 읽는 중…")
        ocrThread.execute {
            val out = ArrayList<Box>()
            try {
                val engine = manga ?: MangaOcr(a).also { manga = it }
                val pad = (6 * a.resources.displayMetrics.density).toInt()
                for (b in boxes) {
                    if (!isOn || myGen != gen) break
                    val r = Rect(b.rect)
                    r.inset(-pad, -pad)
                    if (!r.intersect(0, 0, bmp.width, bmp.height) || r.width() < 8 || r.height() < 8) continue
                    val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                    val txt = try { engine.recognize(crop) } finally { crop.recycle() }
                    val use = if (jp.findAll(txt).count() >= 2) txt else b.text
                    if (jp.findAll(use).count() >= 2) out.add(b.withText(use))
                }
            } catch (e: Throwable) {
                // 메모리 부족·모델 파일 이상 등 → 일반 인식으로
                main.post {
                    mangaFailed = true
                    a.toastMsg("만화 전용 인식을 쓰지 못해 일반 인식으로 해요 (${e.message?.take(40) ?: ""})")
                }
                out.clear()
                out.addAll(boxes.filter { jp.findAll(it.text).count() >= 2 })
            }
            main.post { done(out) }
        }
    }

    /**
     * 인식 결과에서 번역할 덩어리 고르기.
     *  - 세로쓰기 판단: 줄이 여러 개면 줄들이 가로로 늘어서 있는지, 하나면 줄이 세로로 긴지
     *  - 원문 글자 크기: 세로쓰기는 줄 폭, 가로쓰기는 줄 높이의 가운데 값
     *  - 자리: 블록 전체가 아니라 실제 글자 줄들을 합친 상자 (그림을 덜 가리게)
     *  - 같은 자리를 두 번 잡은 것은 합침
     * loose면 글자가 적어도 자리만 맞으면 남김 (만화 전용 인식이 다시 읽음)
     */
    private fun pickBoxes(t: Text, loose: Boolean): List<Box> {
        val out = ArrayList<Box>()
        for (b in t.textBlocks) {
            val lines = b.lines.filter { it.boundingBox != null }
            if (lines.isEmpty()) continue
            val rects = lines.map { it.boundingBox!! }
            val vertical = if (rects.size >= 2) {
                val xs = rects.map { it.centerX() }
                val ys = rects.map { it.centerY() }
                (xs.max() - xs.min()) > (ys.max() - ys.min())
            } else rects[0].height() > rects[0].width() * 1.3f
            val ordered = if (vertical) lines.sortedByDescending { it.boundingBox!!.centerX() }
            else lines.sortedBy { it.boundingBox!!.centerY() }
            val s = ordered.joinToString("") { it.text.replace(" ", "") }.trim()
            // 효과음 한 글자·숫자 등은 건너뜀 (만화 전용 인식은 다시 읽어 보므로 1글자도 남김)
            if (jp.findAll(s).count() < (if (loose) 1 else 2)) continue
            val area = Rect(rects[0])
            rects.forEach { area.union(it) }
            val sizes = rects.map { if (vertical) it.width() else it.height() }.sorted()
            val charPx = sizes[sizes.size / 2].toFloat().coerceAtLeast(8f)
            out.add(Box(area, s, vertical, charPx))
        }
        // 큰 것부터: 이미 고른 것과 절반 넘게 겹치면 같은 말풍선을 두 번 잡은 것으로 보고 버림
        val kept = ArrayList<Box>()
        for (b in out.sortedByDescending { it.rect.width() * it.rect.height() }) {
            val area = (b.rect.width() * b.rect.height()).coerceAtLeast(1)
            val dup = kept.any { k ->
                val i = Rect()
                i.setIntersect(k.rect, b.rect) && i.width() * i.height() > area / 2
            }
            if (!dup) kept.add(b)
        }
        return kept
    }

    private fun translate(boxes: List<Box>, myGen: Int, done: (List<String>) -> Unit) {
        val todo = boxes.map { it.text }.distinct().filter { it !in cache }
        val finish = { done(boxes.map { cache[it.text] ?: it.text }) }
        if (todo.isEmpty()) { finish(); return }
        overlay?.setStatus(if (useHeavy()) "고급 모델로 번역 중…" else "번역 중…")
        if (useHeavy()) translateHeavy(todo, myGen) { ok ->
            if (ok) finish() else translateLight(todo, finish)
        } else translateLight(todo, finish)
    }

    private fun translateLight(todo: List<String>, finish: () -> Unit) {
        var left = todo.size
        val t = mlkitOrCreate()
        todo.forEach { src ->
            t.translate(src)
                .addOnSuccessListener { ko -> cache[src] = ko }
                .addOnCompleteListener {
                    left--
                    if (left == 0) finish()
                }
        }
    }

    /** 고급 모델: 한 페이지의 대사를 번호 붙여 한 번에 번역 */
    private fun translateHeavy(todo: List<String>, myGen: Int, done: (Boolean) -> Unit) {
        val path = ModelStore.QWEN.file(a, "model.task").absolutePath
        llmThread.execute {
            var ok = false
            try {
                val engine = llm ?: LlmInference.createFromOptions(
                    a, LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(1280)
                        .setMaxTopK(20)
                        .build()
                ).also { llm = it }
                // 문맥이 너무 길지 않게 12개씩 나눠서
                for (chunk in todo.chunked(12)) {
                    if (!isOn || myGen != gen) break
                    val list = chunk.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
                    val prompt = "<|im_start|>system\nYou are a professional manga translator. Translate Japanese manga dialogue into natural, casual Korean that fits speech bubbles.<|im_end|>\n" +
                        "<|im_start|>user\n아래 일본어 만화 대사를 자연스러운 한국어로 번역하세요. 번호를 그대로 유지하고, 번역문만 한 줄에 하나씩 출력하세요.\n\n$list<|im_end|>\n" +
                        "<|im_start|>assistant\n"
                    val res = engine.generateResponse(prompt) ?: ""
                    val got = HashMap<Int, String>()
                    Regex("(?m)^\\s*(\\d+)[.)．]\\s*(.+)$").findAll(res).forEach { m ->
                        got[m.groupValues[1].toInt()] = m.groupValues[2].trim()
                    }
                    main.post { chunk.forEachIndexed { i, s -> got[i + 1]?.takeIf { it.isNotBlank() }?.let { cache[s] = it } } }
                }
                ok = true
            } catch (e: Throwable) {
                // 메모리 부족 등으로 고급 모델이 안 되면 일반 모델로
                main.post {
                    heavyFailed = true
                    a.toastMsg("고급 모델을 쓰지 못해 일반 모델로 번역해요 (${e.message?.take(40) ?: ""})")
                }
            }
            main.post { done(ok) }
        }
    }

    // ================= 덮개 =================

    /** 그릴 번역 하나: 원문 자리·번역문·세로쓰기 여부·원문 글자 크기·바탕색·글자색 */
    private class Item(val area: Rect, val text: String, val vertical: Boolean, val charPx: Float, val bg: Int, val fg: Int)

    /**
     * 말풍선 자리에 한글 번역을 그리는 투명 덮개 (터치는 아래 페이지로 통과).
     *  - 원문과 같은 글자 크기에서 시작해, 안 들어가면 70%까지 줄임
     *  - 원문이 세로쓰기면 한글도 세로로 (오른쪽 줄부터, 위에서 아래로)
     *  - 원문 글자 자리만 바탕색으로 덮고, 넘치면 다른 번역·원문 자리와 겹치지 않는 쪽으로만 넓힘
     *  - 진행 상황은 오른쪽 아래에 작게
     */
    private class Overlay(ctx: Context) : View(ctx) {
        private class Glyph(val s: String, val cx: Float, val baseline: Float)
        private class Drawn(
            val bgRect: RectF, val bg: Int, val fg: Int, val size: Float,
            val glyphs: List<Glyph>?, val layout: StaticLayout?, val lx: Float, val ly: Float,
        )

        private var items: List<Item> = emptyList()
        private var drawn: List<Drawn> = emptyList()
        private var dirty = false
        private var status: String? = null
        private val d = ctx.resources.displayMetrics.density
        private val sp = ctx.resources.displayMetrics.scaledDensity
        private val face: android.graphics.Typeface =
            if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
            else android.graphics.Typeface.DEFAULT_BOLD
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val statusBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC222222.toInt() }
        private val statusTp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12 * sp
        }

        init {
            isClickable = false
            isFocusable = false
        }

        fun hasBoxes() = items.isNotEmpty()

        fun setItems(list: List<Item>) {
            items = list
            dirty = true
            invalidate()
        }

        fun clear() {
            if (items.isEmpty() && status == null) return
            items = emptyList()
            drawn = emptyList()
            status = null
            invalidate()
        }

        fun setStatus(s: String?) {
            status = s
            invalidate()
        }

        // ---------- 세로쓰기 ----------

        /** 세로쓰기용 글자 모양으로 바꾸기 (말줄임표·장음·괄호·쉼표 등) */
        private fun vChar(c: String): String = when (c) {
            "…", "⋯" -> "︙"
            "‥" -> "︰"
            "ー", "-", "—", "―", "─", "~", "〜", "∼", "～" -> "｜"
            "(", "（" -> "︵"
            ")", "）" -> "︶"
            "「" -> "﹁"
            "」" -> "﹂"
            "『" -> "﹃"
            "』" -> "﹄"
            ",", "、", "，" -> "︑"
            "。" -> "︒"
            else -> c
        }

        /** 번역문을 세로 글자 목록(단어 단위)으로. 끝의 마침표는 만화처럼 뺌 */
        private fun vWords(text: String): List<List<String>> {
            var t = text.trim().replace(Regex("\\.{3,}"), "…").trimEnd('.', '。')
            if (t.isEmpty()) t = text
            return t.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w ->
                val cps = ArrayList<String>()
                var i = 0
                while (i < w.length) {
                    val cp = w.codePointAt(i)
                    cps.add(vChar(String(Character.toChars(cp))))
                    i += Character.charCount(cp)
                }
                cps
            }
        }

        /** 줄(열) 나누기: 한 열에 rows칸. 단어는 가능하면 쪼개지 않음. 단어 사이는 반 칸 */
        private fun columns(words: List<List<String>>, rows: Int): List<List<String>> {
            val cols = ArrayList<MutableList<String>>()
            var cur = ArrayList<String>()
            var used = 0f
            for (w in words) {
                val gap = if (cur.isEmpty()) 0f else 0.5f
                if (used + gap + w.size <= rows) {
                    if (gap > 0) cur.add(" ")
                    cur.addAll(w); used += gap + w.size
                } else if (w.size <= rows) {
                    if (cur.isNotEmpty()) cols.add(cur)
                    cur = ArrayList(w); used = w.size.toFloat()
                } else {
                    // 한 열보다 긴 단어는 나눔
                    var rest = w
                    if (cur.isNotEmpty() && used + gap < rows) {
                        val take = (rows - used - gap).toInt()
                        if (take > 0) { cur.add(" "); cur.addAll(rest.take(take)); rest = rest.drop(take) }
                    }
                    if (cur.isNotEmpty()) cols.add(cur)
                    while (rest.size > rows) { cols.add(ArrayList(rest.take(rows))); rest = rest.drop(rows) }
                    cur = ArrayList(rest); used = rest.size.toFloat()
                }
            }
            if (cur.isNotEmpty()) cols.add(cur)
            return cols
        }

        private class VFit(val size: Float, val cols: List<List<String>>, val w: Float, val h: Float)

        /** 크기 size로 높이 maxH 안에 세웠을 때 필요한 폭·높이 */
        private fun vMeasure(words: List<List<String>>, size: Float, maxH: Float): VFit {
            val cell = size * 1.06f
            val rows = (maxH / cell).toInt().coerceAtLeast(1)
            val cols = columns(words, rows)
            val colW = size * 1.2f
            val longest = cols.maxOf { c -> c.sumOf { g -> if (g == " ") 0.5 else 1.0 } }.toFloat()
            return VFit(size, cols, cols.size * colW, longest * cell)
        }

        // ---------- 배치 ----------

        private fun overlaps(r: RectF, others: List<RectF>) = others.any { RectF.intersects(it, r) }

        private fun layoutAll() {
            val out = ArrayList<Drawn>()
            val minSp = 8 * sp
            // 다른 원문 자리(살짝 넓혀서) — 넓힐 때 침범하지 않게
            val origins = items.map { RectF(it.area).apply { inset(-it.charPx * 0.15f, -it.charPx * 0.15f) } }
            val placed = ArrayList<RectF>()
            val order = items.indices.sortedByDescending { items[it].area.width() * items[it].area.height() }
            for (idx in order) {
                val it = items[idx]
                val others = origins.filterIndexed { j, _ -> j != idx } + placed
                val pad = it.charPx * 0.15f
                val avail = RectF(it.area).apply { inset(-pad * 0.5f, -pad * 0.5f) }
                val start = (it.charPx * 0.95f).coerceIn(9 * sp, 48 * sp)
                val minSize = (it.charPx * 0.7f).coerceAtLeast(minSp).coerceAtMost(start)
                tp.color = it.fg

                if (it.vertical) {
                    val words = vWords(it.text)
                    if (words.isEmpty()) continue
                    var fit = vMeasure(words, start, avail.height())
                    var size = start
                    while (fit.w > avail.width() && size > minSize) {
                        size = (size * 0.94f).coerceAtLeast(minSize)
                        fit = vMeasure(words, size, avail.height())
                    }
                    var box = RectF(avail)
                    if (fit.w > box.width()) {
                        // 안 들어가면: 왼쪽으로 넓혀 보고(세로쓰기는 다음 줄이 왼쪽), 겹치면 아래로 길게
                        val left = RectF(box).apply { this.left = right - fit.w }
                        if (!overlaps(left, others) && left.left >= 0) box = left
                        else {
                            var grow = box.height()
                            var f2 = fit
                            while (f2.w > box.width() && grow < height - box.top) {
                                grow += size * 1.06f
                                f2 = vMeasure(words, size, grow)
                            }
                            val down = RectF(box.left, box.top, box.right, box.top + grow)
                            if (!overlaps(down, others)) { box = down; fit = f2 }
                            else {
                                // 어느 쪽도 겹치면 가장 작은 크기로 자리 안에 맞춤
                                size = (8 * sp).coerceAtMost(minSize)
                                fit = vMeasure(words, size, box.height())
                            }
                        }
                    }
                    // 세로 글자 놓기: 오른쪽 열부터, 가운데 정렬
                    val cell = size * 1.06f
                    val colW = size * 1.2f
                    tp.textSize = size
                    val fm = tp.fontMetrics
                    val glyphs = ArrayList<Glyph>()
                    val totalW = fit.cols.size * colW
                    val rightX = box.centerX() + totalW / 2f
                    val top = box.top + ((box.height() - fit.h) / 2f).coerceAtLeast(0f)
                    fit.cols.forEachIndexed { ci, col ->
                        val cx = rightX - colW * (ci + 0.5f)
                        var y = top
                        for (g in col) {
                            if (g == " ") { y += cell * 0.5f; continue }
                            val baseline = y + (cell - (fm.descent - fm.ascent)) / 2f - fm.ascent
                            glyphs.add(Glyph(g, cx, baseline))
                            y += cell
                        }
                    }
                    val used = RectF(rightX - totalW, top, rightX, top + fit.h)
                    val bgRect = RectF(it.area).apply { inset(-pad, -pad) }
                    bgRect.union(RectF(used).apply { inset(-size * 0.2f, -size * 0.2f) })
                    placed.add(bgRect)
                    out.add(Drawn(bgRect, it.bg, it.fg, size, glyphs, null, 0f, 0f))
                } else {
                    // 가로쓰기: 원문 폭 안에서 줄바꿈, 높이에 맞을 때까지 줄임
                    val text = it.text.trim()
                    var size = start
                    var w = avail.width().toInt().coerceAtLeast((size * 3).toInt())
                    fun make(sz: Float, width: Int): StaticLayout {
                        tp.textSize = sz
                        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setLineSpacing(0f, 1.0f)
                            .setIncludePad(false)
                            .build()
                    }
                    var layout = make(size, w)
                    while (layout.height > avail.height() && size > minSize) {
                        size = (size * 0.94f).coerceAtLeast(minSize)
                        layout = make(size, w)
                    }
                    var box = RectF(avail)
                    if (layout.height > box.height()) {
                        // 아래로 넓혀 보고, 겹치면 양옆으로 넓혀 줄 수를 줄임
                        val down = RectF(box.left, box.top, box.right, box.top + layout.height)
                        if (!overlaps(down, others)) box = down
                        else {
                            val extra = size * 4
                            val wide = RectF(box.left - extra, box.top, box.right + extra, box.bottom)
                            val l2 = make(size, wide.width().toInt())
                            if (!overlaps(wide, others) && l2.height <= wide.height()) { box = wide; layout = l2; w = wide.width().toInt() }
                            else { size = (8 * sp).coerceAtMost(minSize); layout = make(size, w) }
                        }
                    }
                    val lx = box.centerX() - w / 2f
                    val ly = box.top + ((box.height() - layout.height) / 2f).coerceAtLeast(0f)
                    val bgRect = RectF(it.area).apply { inset(-pad, -pad) }
                    bgRect.union(RectF(lx, ly, lx + w, ly + layout.height).apply {
                        // 실제 글자가 차지한 가로 폭만큼만
                        var minL = Float.MAX_VALUE; var maxR = 0f
                        for (li in 0 until layout.lineCount) { minL = minOf(minL, layout.getLineLeft(li)); maxR = maxOf(maxR, layout.getLineRight(li)) }
                        if (minL < maxR) { left = lx + minL; right = lx + maxR }
                        inset(-size * 0.2f, -size * 0.15f)
                    })
                    placed.add(bgRect)
                    out.add(Drawn(bgRect, it.bg, it.fg, size, null, layout, lx, ly))
                }
            }
            drawn = out
        }

        override fun onDraw(c: Canvas) {
            if (dirty && width > 0) {
                dirty = false
                layoutAll()
            }
            for (dr in drawn) {
                fill.color = dr.bg
                c.drawRoundRect(dr.bgRect, dr.size * 0.35f, dr.size * 0.35f, fill)
                tp.color = dr.fg
                tp.textSize = dr.size
                if (dr.glyphs != null) {
                    tp.textAlign = Paint.Align.CENTER
                    for (g in dr.glyphs) c.drawText(g.s, g.cx, g.baseline, tp)
                    tp.textAlign = Paint.Align.LEFT
                } else if (dr.layout != null) {
                    c.save()
                    c.translate(dr.lx, dr.ly)
                    dr.layout.paint.color = dr.fg
                    dr.layout.draw(c)
                    c.restore()
                }
            }
            // 진행 상황: 오른쪽 아래
            status?.let { s ->
                val tw = statusTp.measureText(s)
                val pad = 10 * d
                val h = 26 * d
                val rect = RectF(width - tw - pad * 3, height - h - pad, width - pad, height - pad)
                c.drawRoundRect(rect, 13 * d, 13 * d, statusBg)
                c.drawText(s, rect.left + pad, rect.centerY() + statusTp.textSize / 3, statusTp)
            }
        }
    }
}
