package com.kakario.lightbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 만화 번역에 쓰는 모델 관리 (앱에 넣지 않고 쓸 때 받음).
 *  - 일반 번역: 구글 ML Kit (일본어·한국어 각 약 30MB)
 *  - 고급 번역: Qwen2.5 1.5B (약 1.6GB)
 *  - 만화 전용 글자 인식: manga-ocr (약 461MB)
 * 큰 모델은 파일 묶음(Pack)으로 받고, 끊기면 이어받는다.
 */
object ModelStore {
    const val LIGHT_SIZE = "약 60MB"

    private val main = Handler(Looper.getMainLooper())

    /** 받을 파일 묶음 하나 */
    class Pack(
        val id: String,
        val title: String,
        val sizeText: String,
        val expected: Long,                       // 대략 전체 크기 (진행률 표시용)
        val files: List<Pair<String, String>>,    // (주소, 저장 이름)
    ) {
        @Volatile var downloading = false
            internal set
        @Volatile var done = 0L
            internal set
        @Volatile var error: String? = null
            internal set
        @Volatile internal var cancel = false
        /** 받기가 끝났을 때 (성공 여부, 메인 스레드) */
        var onDone: ((Boolean) -> Unit)? = null

        fun dir(ctx: Context) = File(File(ctx.filesDir, "models"), id).apply { mkdirs() }
        fun file(ctx: Context, name: String) = File(dir(ctx), name)
        fun ready(ctx: Context) = files.all { file(ctx, it.second).let { f -> f.exists() && f.length() > 0 } }
        fun percent() = (done * 100 / expected.coerceAtLeast(1)).toInt().coerceIn(0, if (downloading) 99 else 100)

        /** 지금까지 받아 둔 크기 (다 받은 파일 + 받다 만 파일) */
        fun partialSize(ctx: Context): Long = files.sumOf { (_, name) ->
            val f = file(ctx, name)
            val p = File(f.path + ".part")
            (if (f.exists()) f.length() else 0L) + (if (p.exists()) p.length() else 0L)
        }

        fun readySize(ctx: Context): Long = files.sumOf { file(ctx, it.second).length() }
    }

    val QWEN = Pack(
        "qwen", "고급 번역 모델", "약 1.6GB", 1_600_000_000L,
        listOf(
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
                to "model.task"
        )
    )

    val MANGA_OCR = Pack(
        "manga-ocr", "만화 전용 글자 인식", "약 461MB", 461_000_000L,
        listOf(
            "https://huggingface.co/mayocream/manga-ocr-onnx/resolve/main/vocab.txt" to "vocab.txt",
            "https://huggingface.co/mayocream/manga-ocr-onnx/resolve/main/decoder_model.onnx" to "decoder_model.onnx",
            "https://huggingface.co/mayocream/manga-ocr-onnx/resolve/main/encoder_model.onnx" to "encoder_model.onnx",
        )
    )

    val packs = listOf(QWEN, MANGA_OCR)

    fun anyDownloading() = packs.any { it.downloading }

    /** 받는 중 진행 상황이 바뀔 때 (메인 스레드) */
    val listeners = mutableListOf<() -> Unit>()

    private var lastNotify = 0L
    private fun notifyChange(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 400) return
        lastNotify = now
        main.post {
            listeners.toList().forEach { it() }
            BgWork.sync(ctx)
        }
    }

    /** 예전 버전에서 받아 둔 고급 모델 파일을 새 자리로 옮김 */
    fun migrate(ctx: Context) {
        val old = File(File(ctx.filesDir, "models"), "qwen2.5-1.5b-q8.task")
        if (old.exists()) {
            val f = QWEN.file(ctx, "model.task")
            if (!f.exists()) old.renameTo(f) else old.delete()
        }
        File(old.path + ".part").let { if (it.exists()) it.renameTo(File(QWEN.file(ctx, "model.task").path + ".part")) }
    }

    /** 묶음 받기 (다 받은 파일은 건너뛰고, 받다 만 파일은 이어받음) */
    fun download(ctx: Context, pack: Pack) {
        if (pack.downloading) return
        val app = ctx.applicationContext
        pack.downloading = true
        pack.cancel = false
        pack.error = null
        pack.done = pack.partialSize(app)
        notifyChange(app, true)
        Thread {
            try {
                var finished = 0L
                for ((url, name) in pack.files) {
                    if (pack.cancel) break
                    val f = pack.file(app, name)
                    if (f.exists() && f.length() > 0) {
                        finished += f.length()
                        continue
                    }
                    val part = File(f.path + ".part")
                    var from = if (part.exists()) part.length() else 0L
                    val port = Bypass.port()
                    val c = (if (port != null) URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                    else URL(url).openConnection()) as HttpURLConnection
                    c.connectTimeout = 20000
                    c.readTimeout = 60000
                    c.instanceFollowRedirects = true
                    if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
                    val code = c.responseCode
                    if (code == 200) from = 0L          // 이어받기를 안 받아 주면 처음부터
                    else if (code == 416) {              // 이미 다 받은 파일
                        c.disconnect()
                        part.renameTo(f)
                        finished += f.length()
                        continue
                    } else if (code != 206) throw Exception("서버 응답 $code ($name)")
                    val len = c.contentLengthLong
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.setLength(from)
                        raf.seek(from)
                        var cur = from
                        c.inputStream.use { ins ->
                            val buf = ByteArray(256 * 1024)
                            while (!pack.cancel) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                cur += n
                                pack.done = finished + cur
                                notifyChange(app)
                            }
                        }
                        if (!pack.cancel && len > 0 && cur < from + len) throw Exception("$name 을(를) 다 받지 못했어요")
                    }
                    c.disconnect()
                    if (pack.cancel) break
                    f.delete()
                    if (!part.renameTo(f)) throw Exception("파일 정리 실패 ($name)")
                    finished += f.length()
                    pack.done = finished
                }
                if (pack.cancel) pack.error = "멈춤 · 다시 누르면 이어받아요"
            } catch (e: Exception) {
                pack.error = "받기 실패: ${e.message ?: e.javaClass.simpleName} · 다시 누르면 이어받아요"
            }
            pack.downloading = false
            notifyChange(app, true)
            val ok = pack.ready(app)
            main.post { pack.onDone?.invoke(ok) }
        }.start()
    }

    fun cancel(pack: Pack) {
        pack.cancel = true
    }

    fun delete(ctx: Context, pack: Pack) {
        pack.cancel = true
        pack.dir(ctx).deleteRecursively()
        pack.done = 0
        pack.error = null
        notifyChange(ctx.applicationContext, true)
    }

    // ================= 일반 번역 모델 (ML Kit) =================

    private fun jaKoOptions() = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.JAPANESE)
        .setTargetLanguage(TranslateLanguage.KOREAN)
        .build()

    /** 일본어·한국어 번역 모델을 받아 두었는지 */
    fun lightReady(cb: (Boolean) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { set ->
                val langs = set.map { it.language }.toSet()
                cb(TranslateLanguage.JAPANESE in langs && TranslateLanguage.KOREAN in langs)
            }
            .addOnFailureListener { cb(false) }
    }

    /** 일반 모델 받기 (와이파이가 아니어도 받음) */
    fun downloadLight(cb: (Boolean, String?) -> Unit) {
        val t = Translation.getClient(jaKoOptions())
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { t.close(); cb(true, null) }
            .addOnFailureListener { e -> t.close(); cb(false, e.message) }
    }

    fun deleteLight(cb: () -> Unit) {
        val mm = RemoteModelManager.getInstance()
        mm.deleteDownloadedModel(TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build())
            .addOnCompleteListener {
                mm.deleteDownloadedModel(TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build())
                    .addOnCompleteListener { cb() }
            }
    }
}
