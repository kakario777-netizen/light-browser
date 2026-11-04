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
                    "더 자연스러운 고급 모델(${ModelStore.HEAVY_SIZE})은 설정 › 자세한 설정에서 따로 받을 수 있어요."
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
        attachOverlay()
        a.toastMsg("실시간 번역 켬 · " + if (useHeavy()) "고급 모델" else "일반 모델")
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
        a.toastMsg("실시간 번역 끔")
    }

    /** 고급 모델을 쓸지 (설정에서 골랐고 받아 두었을 때) */
    private fun useHeavy() = a.store.transModel == 1 && ModelStore.heavyReady(a) && !heavyFailed

    /** 설정에서 모델을 바꿨을 때 */
    fun modelChanged() {
        cache.clear()
        heavyFailed = false
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

    private class Box(val rect: Rect, val text: String)

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
        o?.visibility = View.INVISIBLE   // 덮개 없이 찍기
        main.postDelayed({
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
                        val boxes = pickBoxes(text)
                        if (boxes.isEmpty()) {
                            o?.setStatus(null)
                            o?.setBoxes(emptyList())
                            finishRun()
                            return@addOnSuccessListener
                        }
                        translate(boxes, myGen) { results ->
                            if (isOn && myGen == gen) {
                                o?.setStatus(null)
                                o?.setBoxes(boxes.mapIndexed { i, b -> Pair(b.rect, results[i]) })
                            }
                            finishRun()
                        }
                    }
                    .addOnFailureListener { e ->
                        o?.setStatus("글자 인식 준비 중… (${e.message?.take(40) ?: "잠시 뒤 다시"})")
                        finishRun()
                        schedule(3000)
                    }
            }
        }, 80)
    }

    private fun finishRun() {
        busy = false
        if (again) {
            again = false
            schedule(200)
        }
    }

    private val jp = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")

    /** 인식 결과에서 번역할 덩어리 고르기 (세로쓰기는 오른쪽 줄부터 읽음) */
    private fun pickBoxes(t: Text): List<Box> {
        val out = ArrayList<Box>()
        for (b in t.textBlocks) {
            val r = b.boundingBox ?: continue
            val lines = b.lines.filter { it.boundingBox != null }
            if (lines.isEmpty()) continue
            val vertical = lines.count { val lb = it.boundingBox!!; lb.height() > lb.width() * 1.3f } * 2 > lines.size
            val ordered = if (vertical) lines.sortedByDescending { it.boundingBox!!.centerX() }
            else lines.sortedBy { it.boundingBox!!.centerY() }
            val s = ordered.joinToString("") { it.text.replace(" ", "") }.trim()
            if (jp.findAll(s).count() < 2) continue   // 효과음 한 글자·숫자 등은 건너뜀
            out.add(Box(Rect(r), s))
        }
        return out
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
        val path = ModelStore.heavyFile(a).absolutePath
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

    /** 말풍선 자리에 한글 번역을 그리는 투명 덮개 (터치는 아래 페이지로 통과) */
    private class Overlay(ctx: Context) : View(ctx) {
        private var boxes: List<Pair<Rect, String>> = emptyList()
        private var status: String? = null
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF5FFFFFF.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000
            style = Paint.Style.STROKE
            strokeWidth = ctx.resources.displayMetrics.density
        }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val statusBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC222222.toInt() }
        private val statusTp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12 * ctx.resources.displayMetrics.scaledDensity
        }
        private val d = ctx.resources.displayMetrics.density
        private val sp = ctx.resources.displayMetrics.scaledDensity

        init {
            isClickable = false
            isFocusable = false
        }

        fun hasBoxes() = boxes.isNotEmpty()

        fun setBoxes(b: List<Pair<Rect, String>>) {
            boxes = b
            invalidate()
        }

        fun clear() {
            if (boxes.isEmpty() && status == null) return
            boxes = emptyList()
            status = null
            invalidate()
        }

        fun setStatus(s: String?) {
            status = s
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            for ((r, text) in boxes) {
                // 세로로 좁은 말풍선은 가로로 넓혀서 한글이 읽히게
                val minW = 64 * d
                val box = RectF(r)
                if (box.width() < minW) {
                    val cx = box.centerX()
                    box.left = cx - minW / 2
                    box.right = cx + minW / 2
                }
                box.inset(-3 * d, -3 * d)
                val w = (box.width() - 6 * d).toInt().coerceAtLeast(10)
                // 상자 안에 들어갈 때까지 글자 크기를 줄임 (최소 9sp, 그래도 넘치면 상자를 늘림)
                var size = 16f
                var layout: StaticLayout
                while (true) {
                    tp.textSize = size * sp
                    layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.0f)
                        .build()
                    if (layout.height <= box.height() - 6 * d || size <= 9f) break
                    size -= 1f
                }
                if (layout.height > box.height() - 6 * d) box.bottom = box.top + layout.height + 6 * d
                c.drawRoundRect(box, 6 * d, 6 * d, bg)
                c.drawRoundRect(box, 6 * d, 6 * d, border)
                c.save()
                c.translate(box.left + 3 * d, box.top + (box.height() - layout.height) / 2f)
                layout.draw(c)
                c.restore()
            }
            status?.let { s ->
                val tw = statusTp.measureText(s)
                val pad = 8 * d
                val rect = RectF(width - tw - pad * 3, pad, width - pad, pad + 26 * d)
                c.drawRoundRect(rect, 13 * d, 13 * d, statusBg)
                c.drawText(s, rect.left + pad, rect.centerY() + statusTp.textSize / 3, statusTp)
            }
        }
    }
}
