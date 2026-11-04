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
 * 만화 번역 모델 관리 (앱에 넣지 않고 쓸 때 받음).
 *  - 일반 모델: 구글 ML Kit 번역 (일본어·한국어 각 약 30MB, 빠름)
 *  - 고급 모델: Qwen2.5 1.5B (약 1.6GB, 더 자연스럽지만 느림). 이어받기 지원.
 */
object ModelStore {
    const val HEAVY_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val HEAVY_SIZE = "약 1.6GB"
    const val LIGHT_SIZE = "약 60MB"

    private val main = Handler(Looper.getMainLooper())

    fun heavyFile(ctx: Context) = File(File(ctx.filesDir, "models").apply { mkdirs() }, "qwen2.5-1.5b-q8.task")
    private fun heavyPart(ctx: Context) = File(heavyFile(ctx).path + ".part")

    fun heavyReady(ctx: Context) = heavyFile(ctx).let { it.exists() && it.length() > 100L * 1024 * 1024 }

    @Volatile var heavyDownloading = false
        private set
    @Volatile var heavyDone = 0L
        private set
    @Volatile var heavyTotal = 0L
        private set
    @Volatile var heavyError: String? = null
        private set
    @Volatile private var heavyCancel = false

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

    fun heavyPercent(): Int = if (heavyTotal > 0) (heavyDone * 100 / heavyTotal).toInt() else 0

    /** 받다 만 파일이 있으면 그 뒤부터 이어받음 */
    fun downloadHeavy(ctx: Context) {
        if (heavyDownloading) return
        val app = ctx.applicationContext
        heavyDownloading = true
        heavyCancel = false
        heavyError = null
        notifyChange(app, true)
        Thread {
            val part = heavyPart(app)
            try {
                var from = if (part.exists()) part.length() else 0L
                val port = Bypass.port()
                val c = (if (port != null) URL(HEAVY_URL).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                else URL(HEAVY_URL).openConnection()) as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
                val code = c.responseCode
                if (code == 200) from = 0L   // 서버가 이어받기를 안 받아 줌 → 처음부터
                else if (code != 206) throw Exception("서버 응답 $code")
                val len = c.contentLengthLong
                heavyTotal = if (len > 0) from + len else 0L
                heavyDone = from
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(from)
                    raf.seek(from)
                    c.inputStream.use { ins ->
                        val buf = ByteArray(256 * 1024)
                        while (!heavyCancel) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            heavyDone += n
                            notifyChange(app)
                        }
                    }
                }
                c.disconnect()
                if (heavyCancel) {
                    heavyError = "멈춤 (다시 누르면 이어받아요)"
                } else if (heavyTotal > 0 && heavyDone < heavyTotal) {
                    heavyError = "다 받지 못했어요 (다시 누르면 이어받아요)"
                } else {
                    val f = heavyFile(app)
                    f.delete()
                    if (!part.renameTo(f)) throw Exception("파일 정리 실패")
                }
            } catch (e: Exception) {
                heavyError = "받기 실패: ${e.message ?: e.javaClass.simpleName} (다시 누르면 이어받아요)"
            }
            heavyDownloading = false
            notifyChange(app, true)
            main.post { onHeavyDone?.invoke(heavyReady(app)) }
        }.start()
    }

    /** 고급 모델 받기가 끝났을 때 (성공 여부) */
    var onHeavyDone: ((Boolean) -> Unit)? = null

    fun cancelHeavy() {
        heavyCancel = true
    }

    fun deleteHeavy(ctx: Context) {
        heavyCancel = true
        heavyFile(ctx).delete()
        heavyPart(ctx).delete()
        heavyDone = 0
        heavyTotal = 0
        heavyError = null
        notifyChange(ctx.applicationContext, true)
    }

    fun heavyPartialSize(ctx: Context) = heavyPart(ctx).let { if (it.exists()) it.length() else 0L }

    // ================= 일반 모델 (ML Kit 번역) =================

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
