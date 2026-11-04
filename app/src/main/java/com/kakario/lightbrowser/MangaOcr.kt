package com.kakario.lightbrowser

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 만화 전용 글자 인식 (manga-ocr, kha-white/manga-ocr-base의 ONNX 판).
 * 말풍선 하나를 잘라 넣으면 그 안의 일본어를 읽어 준다 (세로쓰기·손글씨풍에 강함).
 *  - 그림 → 회색조 → 224×224 → [-1, 1] → 인코더(ViT) → 디코더(BERT)로 한 글자씩
 *  - 시작 토큰 2([CLS]), 끝 토큰 3([SEP])
 * 반드시 백그라운드 스레드에서 쓴다.
 */
class MangaOcr(ctx: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val enc: OrtSession
    private val dec: OrtSession
    private val vocab: List<String>
    private val encInput: String

    init {
        val pack = ModelStore.MANGA_OCR
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        enc = env.createSession(pack.file(ctx, "encoder_model.onnx").absolutePath, opts)
        dec = env.createSession(pack.file(ctx, "decoder_model.onnx").absolutePath, opts)
        vocab = pack.file(ctx, "vocab.txt").readLines(Charsets.UTF_8)
        encInput = enc.inputNames.firstOrNull { it.contains("pixel") } ?: enc.inputNames.first()
    }

    /** 그림을 모델 입력으로: 회색조 → 3채널 → 224×224 → (x/255 - 0.5) / 0.5 */
    private fun preprocess(src: Bitmap): FloatBuffer {
        val s = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        s.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (s !== src) s.recycle()
        val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        val arr = buf.array()
        for (i in 0 until plane) {
            val c = px[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val v = (gray - 0.5f) / 0.5f
            arr[i] = v
            arr[plane + i] = v
            arr[2 * plane + i] = v
        }
        return buf
    }

    private fun ones(n: Long): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n.toInt()) { 1L }), longArrayOf(1, n))

    /** 말풍선 그림 하나 읽기 */
    fun recognize(crop: Bitmap): String {
        val ids = ArrayList<Long>()
        ids.add(START)
        OnnxTensor.createTensor(env, preprocess(crop), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            enc.run(mapOf(encInput to input)).use { er ->
                val hidden = er.get(0) as OnnxTensor
                val encLen = hidden.info.shape.getOrElse(1) { 197L }
                for (step in 0 until MAX_LEN) {
                    val made = ArrayList<OnnxTensor>()
                    val feeds = HashMap<String, OnnxTensor>()
                    for (name in dec.inputNames) {
                        when {
                            name.contains("input_ids") -> OnnxTensor.createTensor(
                                env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong())
                            ).also { made.add(it); feeds[name] = it }
                            name.contains("encoder_hidden_states") -> feeds[name] = hidden
                            name.contains("encoder_attention_mask") -> ones(encLen).also { made.add(it); feeds[name] = it }
                            name.contains("attention_mask") -> ones(ids.size.toLong()).also { made.add(it); feeds[name] = it }
                        }
                    }
                    var next = END
                    try {
                        dec.run(feeds).use { dr ->
                            val logits = dr.get(0) as OnnxTensor
                            val shape = logits.info.shape            // [1, 글자수, 단어 수]
                            val v = shape.last().toInt()
                            val fb = logits.floatBuffer
                            val off = (ids.size - 1) * v
                            var best = 0
                            var bestScore = Float.NEGATIVE_INFINITY
                            for (k in 0 until v) {
                                val sc = fb.get(off + k)
                                if (sc > bestScore) { bestScore = sc; best = k }
                            }
                            next = best.toLong()
                        }
                    } finally {
                        made.forEach { it.close() }
                    }
                    if (next == END) break
                    ids.add(next)
                    // 같은 세 글자가 반복되면 멈춤 (가끔 같은 말을 되풀이함)
                    if (ids.size > 7) {
                        val n = ids.size
                        if (ids[n - 1] == ids[n - 4] && ids[n - 2] == ids[n - 5] && ids[n - 3] == ids[n - 6]) break
                    }
                }
            }
        }
        return decode(ids.drop(1))
    }

    /** 토큰 번호 → 글자 (특수 토큰 빼고, 조각 표시 ## 지우고, 공백 없이) */
    private fun decode(tokens: List<Long>): String {
        val sb = StringBuilder()
        for (t in tokens) {
            val i = t.toInt()
            if (i < 5 || i >= vocab.size) continue   // [PAD][UNK][CLS][SEP][MASK]
            sb.append(vocab[i].removePrefix("##"))
        }
        return sb.toString().replace(Regex("\\s+"), "").replace("…", "...")
    }

    override fun close() {
        try { enc.close() } catch (e: Exception) {}
        try { dec.close() } catch (e: Exception) {}
    }

    companion object {
        private const val SIZE = 224
        private const val START = 2L
        private const val END = 3L
        private const val MAX_LEN = 120
    }
}
