package com.kakario.lightbrowser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/** 이미지 받기 작업 하나 (폴더 받기·번호 찾기·넘기며 모으기 결과) */
class GrabJob(val id: Long, var title: String, val dir: File, val referer: String?, val ua: String?) {
    @Volatile var state = FINDING
    @Volatile var canceled = false
    var status = ""                 // 지금 하는 일 (화면·목록 표시용)
    var urls: List<String> = emptyList()
    var how = ""                    // 어떻게 찾았는지
    var total = 0
    var done = 0
    var fails = 0
    var baseImage: String? = null   // 기준 이미지 (못 찾았을 때 주소 분석용)
    var seen = false                // 완료 후 사용자가 열어 봤는지
    var askZip = false              // 앱을 다시 열면 '압축할까요?' 물어보기
    var pageTitle: String? = null   // 받은 페이지 제목 (압축 파일 이름에 씀)
    var zipName: String? = null     // 사용자가 정한 압축 파일 이름
    val pics = mutableListOf<Pic>() // 메인 스레드에서만
    internal val slots = ArrayList<Pic?>()          // 메인 스레드에서만 (주소 순서대로)
    internal val failed = sortedSetOf<Int>()        // 실패한 순번 (메인 스레드)
    internal val failCodes = HashMap<Int, Int>()    // 실패 이유별 개수 (메인 스레드)
    /** 페이지가 그 이미지를 불러올 때 보낸 요청 머리말 (같은 조건으로 받기 위해) */
    val headers = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    /** 페이지가 이미지를 불러올 때 앱이 같이 받아 둔 파일 (다시 요청하지 않고 그대로 씀) */
    val captured = java.util.concurrent.ConcurrentHashMap<String, File>()
    internal val failReason = HashMap<Int, Int>()   // 순번별 실패 이유 (메인 스레드)
    /** 받은 이미지 내용 지문 (같은 그림을 두 번 받지 않게) (메인 스레드) */
    internal val hashes = HashMap<String, Int>()   // 지문 → 순번
    internal val dupSlots = sortedSetOf<Int>()      // 이미 받은 것과 같아서 건너뛴 순번
    val dups get() = dupSlots.size
    internal val dupOf = HashMap<Int, Int>()        // 중복 칸 → 원래 칸
    /** 칸별 그림 지문(작은 썸네일 지문) — '같은 장 찾기'를 바로 하려고 받을 때 미리 계산 */
    val dhash = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    /** 화면에 실제로 보였던 그림 (주소 → 그림 지문, 화면 캡처 jpg) — 받은 파일과 비교 */
    val expect = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ByteArray>>()
    /** 페이지가 최근에 불러온 그림 원본들 (화면 그림과 같은 원본을 찾을 때 씀) */
    class Recent(val url: String, val bytes: ByteArray, val ext: String) { @Volatile var hash: Long? = null }
    val recent = java.util.concurrent.ConcurrentLinkedDeque<Recent>()
    internal val fixed = sortedSetOf<Int>()         // 받은 그림이 화면과 달라서 화면 캡처로 바꾼 칸
    /** 작업 기록 (넘기기·실패 상세). 여러 스레드에서 씀 */
    val log = StringBuffer()

    fun addLog(msg: String) {
        if (log.length > 400_000) return   // 너무 커지지 않게
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        log.append(t).append("  ").append(msg).append('\n')
    }

    class Pic(val url: String, val file: File, var selected: Boolean = true)

    val active get() = state == FINDING || state == DOWNLOADING || state == COLLECTING

    /** "403 접근 거부 12 · 연결 오류 3" 같은 실패 이유 요약 */
    fun failSummary(): String = failCodes.entries.sortedByDescending { it.value }.joinToString(" · ") { (code, n) ->
        "${FailReason.short(code)} $n"
    }

    companion object {
        const val FINDING = 0
        const val FOUND = 1        // 찾았고 받기 확인 기다림
        const val DOWNLOADING = 2
        const val DONE = 3
        const val NOTHING = 4      // 못 찾음
        const val CANCELED = 5
        const val COLLECTING = 6   // 넘기며 모으는 중 (찾는 대로 바로 받음)
    }
}

/** 실패 이유 */
object FailReason {
    fun short(code: Int) = when (code) {
        401, 403 -> "접근 거부($code)"
        404, 410 -> "없음($code)"
        429 -> "요청 너무 잦음"
        in 500..599 -> "서버 오류($code)"
        -2 -> "이미지 아님"
        -3 -> "빈 파일"
        else -> "연결 오류"
    }

    /** 실패 이유를 쉬운 말로 */
    fun long(code: Int) = when (code) {
        401, 403 -> "서버가 거부했어요. 로그인이 필요하거나, 다른 사이트·앱에서 직접 가져가는 걸 막거나, 주소의 유효 시간이 지났을 수 있어요."
        404, 410 -> "그 주소에 파일이 없어요. 지워졌거나 주소가 바뀌었어요."
        429 -> "짧은 시간에 너무 많이 요청해서 서버가 잠시 막았어요. 조금 뒤 다시 받기를 해 보세요."
        in 500..599 -> "서버 쪽 문제예요. 조금 뒤 다시 받기를 해 보세요."
        -2 -> "이미지 대신 웹페이지(로그인 화면·안내 화면 등)가 왔어요."
        -3 -> "서버가 빈 파일을 보냈어요."
        else -> "연결이 끊겼거나 응답이 없었어요."
    }
}

/** 받기 작업들을 화면과 상관없이 관리 (여러 개 동시에) */
object JobStore {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(8)
    private lateinit var app: Context

    val jobs = mutableListOf<GrabJob>()            // 메인 스레드에서만
    val listeners = mutableListOf<(GrabJob?) -> Unit>()
    /** 찾기가 끝났을 때 (화면에서 보고 있으면 확인 창, 아니면 바로 받기) */
    var onFound: ((GrabJob) -> Unit)? = null
    /** 받기가 끝났을 때 (앱 안 완료 표시) */
    var onFinished: ((GrabJob) -> Unit)? = null
    /** 받고 보니 이미 받은 그림과 같을 때 (넘기며 모으기가 '안 넘어간 것'으로 다시 판단) */
    var onDuplicate: ((GrabJob, Int) -> Unit)? = null

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        EngineFetch.init(ctx)
    }

    fun get(id: Long) = jobs.firstOrNull { it.id == id }

    fun activeCount() = jobs.count { it.active }

    private var lastPost = 0L
    private var pendingPost = false

    /** 화면 갱신 (너무 자주 부르지 않게 0.25초에 한 번) */
    fun changed(job: GrabJob?, force: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { changed(job, force) }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastPost < 250) {
            if (!pendingPost) {
                pendingPost = true
                main.postDelayed({ pendingPost = false; changed(job, true) }, 260)
            }
            return
        }
        lastPost = now
        listeners.toList().forEach { it(job) }
        BgWork.sync(app)
    }

    fun create(title: String, referer: String?, ua: String?): GrabJob {
        val id = System.currentTimeMillis() + jobs.size
        val j = GrabJob(id, title, File(app.filesDir, "grab/$id").apply { mkdirs() }, referer, ua)
        jobs.add(0, j)
        changed(j, true)
        return j
    }

    fun remove(j: GrabJob) {
        j.canceled = true
        jobs.remove(j)
        Thread { j.dir.deleteRecursively() }.start()
        changed(null, true)
    }

    fun cancel(j: GrabJob) {
        j.canceled = true
        if (j.active || j.state == GrabJob.FOUND) {
            j.state = GrabJob.CANCELED
            j.status = "취소됨"
        }
        changed(j, true)
    }

    // ================= 찾기 =================

    /** 폴더 목록 → 안 되면 번호 이어 찾기 */
    fun findFolder(j: GrabJob, imageUrl: String) {
        j.baseImage = imageUrl
        val folder = folderOf(imageUrl)
        j.status = "폴더 확인 중…\n$folder"
        changed(j, true)
        Thread {
            var urls: List<String> = emptyList()
            try {
                urls = listFolder(j, folder)
                if (urls.isNotEmpty()) j.how = "폴더 목록"
            } catch (e: Exception) {
            }
            if (urls.isEmpty() && !j.canceled) {
                j.status = "폴더 목록이 막혀 있어요. 번호로 이어 찾는 중…"
                changed(j)
                val run = lastRunInFile(imageUrl)
                if (run != null) {
                    urls = sequence(j, imageUrl, run, 8, tryExts = false, strip = false)
                    if (urls.size > 1) j.how = "번호 이어 찾기"
                }
            }
            main.post { found(j, urls, "이 폴더에서는 이미지를 더 찾지 못했어요.\n주소를 나눠 보고 다른 번호로 찾거나, 페이지를 넘기며 모아 보세요.") }
        }.start()
    }

    fun findSequence(j: GrabJob, url: String, run: Run, miss: Int, exts: Boolean, strip: Boolean) {
        j.baseImage = url
        j.status = "번호로 이어 찾는 중…"
        changed(j, true)
        Thread {
            val urls = sequence(j, url, run, miss, exts, strip)
            if (urls.size > 1) j.how = "번호 이어 찾기"
            main.post { found(j, urls, "이 번호로는 이미지를 더 찾지 못했어요.\n다른 번호를 골라 보거나 넘기며 모아 보세요.") }
        }.start()
    }

    private fun found(j: GrabJob, urls: List<String>, failMsg: String) {
        if (j.canceled) return
        if (urls.size <= 1) {
            j.state = GrabJob.NOTHING
            j.status = failMsg
            changed(j, true)
            return
        }
        j.urls = urls
        j.state = GrabJob.FOUND
        j.status = "${j.how}으로 이미지 ${urls.size}개를 찾았어요"
        changed(j, true)
        val cb = onFound
        if (cb != null) cb(j) else download(j)
    }

    // ================= 주소 다루기 =================

    fun folderOf(url: String): String {
        val u = URI(url)
        val path = (u.rawPath ?: "/").substringBeforeLast('/') + "/"
        return URI(u.scheme, u.rawAuthority, null, null, null).toString().removeSuffix("/") + path
    }

    fun folderName(folder: String): String {
        val p = Uri.parse(folder).pathSegments.lastOrNull()
        val n = try { URLDecoder.decode(p ?: "", "UTF-8") } catch (e: Exception) { p ?: "" }
        return n.ifBlank { Uri.parse(folder).host ?: "images" }
    }

    private val imgExt = Regex("\\.(jpe?g|png|gif|webp|bmp|avif|heic|jfif)$", RegexOption.IGNORE_CASE)

    private fun isImageUrl(u: String) = imgExt.containsMatchIn(Uri.parse(u).path ?: "")

    private fun open(j: GrabJob, url: String, method: String = "GET"): HttpURLConnection {
        val port = Bypass.port()
        val c = (if (port != null) URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        else URL(url).openConnection()) as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        j.ua?.let { c.setRequestProperty("User-Agent", it) }
        j.referer?.let { c.setRequestProperty("Referer", it) }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        return c
    }

    // ================= 1) 폴더 목록 =================

    private fun listFolder(j: GrabJob, folder: String): List<String> {
        val found = LinkedHashSet<String>()
        val seen = HashSet<String>()
        fun walk(dirUrl: String, depth: Int) {
            if (j.canceled || !seen.add(dirUrl) || found.size >= 5000) return
            val c = open(j, dirUrl)
            if (c.responseCode != 200) { c.disconnect(); return }
            if (!(c.contentType ?: "").contains("html", true)) { c.disconnect(); return }
            val html = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val base = URI(dirUrl)
            val subs = mutableListOf<String>()
            val files = mutableListOf<String>()
            for (m in Regex("href\\s*=\\s*[\"']([^\"'#?]+)[\"']", RegexOption.IGNORE_CASE).findAll(html)) {
                val h = m.groupValues[1]
                if (h.startsWith("javascript:") || h.startsWith("mailto:")) continue
                val abs = try { base.resolve(h.replace(" ", "%20")).toString() } catch (e: Exception) { continue }
                if (!abs.startsWith(dirUrl) || abs == dirUrl) continue
                if (abs.endsWith("/")) subs.add(abs) else if (isImageUrl(abs)) files.add(abs)
            }
            files.sortedWith(natural).forEach { found.add(it) }
            if (depth < 3) subs.distinct().sortedWith(natural).forEach { walk(it, depth + 1) }
        }
        walk(folder, 0)
        return found.toList()
    }

    val natural = Comparator<String> { x, y ->
        val rx = Regex("(\\d+)|(\\D+)")
        val px = rx.findAll(x.lowercase()).map { it.value }.toList()
        val py = rx.findAll(y.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(px.size, py.size)) {
            val p = px[i]; val q = py[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val d = p.trimStart('0').length - q.trimStart('0').length
                if (d != 0) d else p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        px.size - py.size
    }

    // ================= 2) 번호 이어 찾기 =================

    class Run(val start: Int, val end: Int)

    fun runs(url: String): List<Run> {
        val hostEnd = url.indexOf('/', url.indexOf("://") + 3).let { if (it < 0) url.length else it }
        return Regex("\\d+").findAll(url).filter { it.range.first >= hostEnd && it.value.length <= 12 }
            .map { Run(it.range.first, it.range.last + 1) }.toList()
    }

    fun lastRunInFile(url: String): Run? {
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val fileStart = url.lastIndexOf('/', q - 1) + 1
        return runs(url).lastOrNull { it.start >= fileStart && it.end <= q }
    }

    private val exts = listOf("jpg", "jpeg", "png", "webp", "gif")
    private val extRe = Regex("\\.(jpe?g|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)

    private fun withExt(u: String, ext: String): String {
        val q = u.indexOf('?')
        val path = if (q >= 0) u.substring(0, q) else u
        val rest = if (q >= 0) u.substring(q) else ""
        return if (extRe.containsMatchIn(path)) path.replace(extRe, ".$ext") + rest else u
    }

    private fun sequence(j: GrabJob, url: String, run: Run, maxMiss: Int, tryExts: Boolean, strip: Boolean): List<String> {
        val numStr = url.substring(run.start, run.end)
        val width = numStr.length
        val padded = numStr.startsWith("0") && width > 1
        val start = numStr.toLong()
        val q = url.indexOf('?')
        val cutQuery = strip && q >= 0 && run.start < q
        fun make(n: Long): String {
            val ns = if (padded) n.toString().padStart(width, '0') else n.toString()
            val u = url.substring(0, run.start) + ns + url.substring(run.end)
            return if (cutQuery) u.substring(0, u.indexOf('?')) else u
        }
        var preferredExt: String? = null
        fun probe(n: Long): String? {
            val base = make(n)
            if (exists(j, base)) return base
            if (!tryExts) return null
            for (e in (listOfNotNull(preferredExt) + exts).distinct()) {
                val c = withExt(base, e)
                if (c != base && exists(j, c)) { preferredExt = e; return c }
            }
            return null
        }
        val found = java.util.TreeMap<Long, String>()
        found[start] = probe(start) ?: url
        var checked = 0
        fun scan(step: Int) {
            var n = start + step
            var miss = 0
            while (!j.canceled && miss < maxMiss && n >= 0 && found.size < 5000) {
                val batch = (0 until 6).map { n + it * step }.filter { it >= 0 }
                if (batch.isEmpty()) break
                val futures = batch.map { k -> pool.submit<String?> { if (j.canceled) null else probe(k) } }
                for ((i, f) in futures.withIndex()) {
                    val hit = try { f.get() } catch (e: Exception) { null }
                    if (hit != null) { found[batch[i]] = hit; miss = 0 } else miss++
                    if (miss >= maxMiss) break
                }
                n += 6L * step
                checked += batch.size
                j.status = "번호로 이어 찾는 중… ${found.size}개 찾음 (${checked}번 확인)"
                changed(j)
            }
        }
        scan(-1)
        scan(+1)
        return found.values.toList()
    }

    private fun exists(j: GrabJob, url: String): Boolean {
        return try {
            var c = open(j, url, "HEAD")
            var code = c.responseCode
            if (code == 405 || code == 403 || code == 501) {
                c.disconnect()
                c = open(j, url)
                c.setRequestProperty("Range", "bytes=0-0")
                code = c.responseCode
            }
            val type = c.contentType ?: ""
            val len = c.contentLengthLong
            c.disconnect()
            (code == 200 || code == 206) && !type.contains("html", true) && (len < 0 || len > 200)
        } catch (e: Exception) {
            false
        }
    }

    // ================= 받기 =================

    private val retryTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** 찾은 주소를 한꺼번에 받기 (폴더 받기·번호 찾기) */
    fun download(j: GrabJob, urls: List<String> = j.urls) {
        if (urls.isEmpty()) return
        resetProgress(j)
        j.state = GrabJob.DOWNLOADING
        j.status = "받는 중… 0 / ${urls.size}"
        urls.forEach { add(j, it) }
        changed(j, true)
    }

    /** 넘기며 모으기: 작업을 먼저 만들고, 이미지를 찾을 때마다 바로 받는다 (주소가 만료되기 전에) */
    fun startCollecting(title: String, referer: String?, ua: String?): GrabJob {
        val j = create(title, referer, ua)
        j.how = "넘기며 모으기"
        resetProgress(j)
        j.state = GrabJob.COLLECTING
        j.status = "모으면서 받는 중…"
        changed(j, true)
        return j
    }

    /** 모으기가 끝남: 남은 이미지를 마저 받고 완료 */
    fun endCollecting(j: GrabJob) {
        if (j.state != GrabJob.COLLECTING) return
        if (j.total == 0) {
            remove(j)
            return
        }
        j.state = GrabJob.DOWNLOADING
        updateStatus(j)
        if (j.done >= j.total) finish(j) else changed(j, true)
    }

    private fun resetProgress(j: GrabJob) {
        j.urls = emptyList()
        j.total = 0
        j.done = 0
        j.fails = 0
        j.slots.clear()
        j.failed.clear()
        j.failCodes.clear()
        j.failReason.clear()
        j.pics.clear()
        j.hashes.clear()
        j.dupSlots.clear()
        j.dupOf.clear()
        j.fixed.clear()
        j.expect.clear()
    }

    /** 저장해 둔 넘기며 모으기를 다시 불러옴 (받던 폴더 그대로, 못 받은 건 다시 받음) */
    fun restore(s: CollectSaves.Save): GrabJob? {
        get(s.id)?.let { return it }
        val j = build(s) ?: return null
        val urls = j.urls
        j.state = GrabJob.COLLECTING
        val missing = j.slots.indices.filter { j.slots[it] == null && it !in j.failed && it !in j.dupSlots }
            .filter { i ->
                // 페이지에서 직접 꺼낸 그림은 다시 받을 주소가 없음 → 실패로
                if (urls[i].startsWith("http")) true else { j.failed.add(i); j.failReason[i] = -1; j.failCodes[-1] = (j.failCodes[-1] ?: 0) + 1; false }
            }
        j.fails = j.failed.size
        j.done = j.total - missing.size
        j.addLog("저장해 둔 작업 다시 불러옴 · 받은 ${j.pics.size}장 · 다시 받을 ${missing.size}장")
        updateStatus(j)
        jobs.add(0, j)
        missing.forEach { i -> fetch(j, i, urls[i], attempt = 1) }
        changed(j, true)
        return j
    }

    /** n번(0부터) 칸부터 끝까지 지움: '이 페이지부터 새로 받기' (메인 스레드) */
    fun truncateFrom(j: GrabJob, n: Int) {
        if (n < 0 || n >= j.slots.size) return
        for (i in n until j.slots.size) {
            j.slots[i]?.file?.delete()
            j.failed.remove(i)
            j.failReason.remove(i)
            j.dupSlots.remove(i)
            j.dupOf.remove(i)
            j.fixed.remove(i)
            j.dhash.remove(i)
        }
        while (j.slots.size > n) j.slots.removeAt(j.slots.size - 1)
        j.urls = j.urls.take(n)
        j.hashes.entries.removeAll { it.value >= n }
        j.failCodes.clear()
        j.failReason.values.forEach { c -> j.failCodes[c] = (j.failCodes[c] ?: 0) + 1 }
        j.total = j.slots.size
        j.fails = j.failed.size
        j.done = j.total
        j.pics.clear()
        j.slots.forEach { p -> if (p != null) j.pics.add(p) }
        j.addLog("${n + 1}번부터 지우고 새로 받기 시작")
        updateStatus(j)
        changed(j, true)
    }

    /** 저장해 둔 작업을 보기만 하려고 불러옴 (받기 시작 안 함, 작업 목록에도 안 넣음) */
    fun peek(s: CollectSaves.Save): GrabJob? {
        val j = build(s) ?: return null
        j.state = GrabJob.CANCELED
        j.done = j.total
        j.status = "저장해 둔 작업 · 받은 ${j.pics.size}장" + (if (j.fails > 0) " · 실패 ${j.fails}" else "") +
            (if (j.dups > 0) " · 중복 건너뜀 ${j.dups}" else "")
        return j
    }

    private fun build(s: CollectSaves.Save): GrabJob? {
        val o = s.o
        val dir = File(o.optString("dir"))
        if (!dir.exists()) return null
        val j = GrabJob(s.id, o.optString("title"), dir, o.optString("referer").ifEmpty { null }, o.optString("ua").ifEmpty { null })
        j.how = "넘기며 모으기"
        j.pageTitle = o.optString("pageTitle").ifEmpty { null }
        j.zipName = o.optString("zipName").ifEmpty { null }
        val urls = ArrayList<String>()
        o.optJSONArray("urls")?.let { a -> for (i in 0 until a.length()) urls.add(a.getString(i)) }
        j.urls = urls
        urls.forEach { _ -> j.slots.add(null) }
        o.optJSONArray("ok")?.let { a ->
            for (k in 0 until a.length()) {
                val e = a.getJSONObject(k)
                val i = e.getInt("i")
                val f = File(dir, e.getString("f"))
                if (i < j.slots.size && f.exists() && f.length() > 0) j.slots[i] = GrabJob.Pic(urls[i], f)
            }
        }
        o.optJSONObject("failed")?.let { f ->
            f.keys().forEach { k ->
                val i = k.toIntOrNull() ?: return@forEach
                if (i < j.slots.size && j.slots[i] == null) {
                    val code = f.optInt(k, -1)
                    j.failed.add(i)
                    j.failReason[i] = code
                    j.failCodes[code] = (j.failCodes[code] ?: 0) + 1
                }
            }
        }
        o.optJSONArray("dups")?.let { a -> for (k in 0 until a.length()) j.dupSlots.add(a.getInt(k)) }
        runCatching { File(dir, "log.txt").takeIf { it.exists() }?.readText()?.let { j.log.append(it).append("---- 저장한 작업 다시 열기 ----\n") } }
        o.optJSONObject("dh")?.let { h -> h.keys().forEach { k -> k.toIntOrNull()?.let { i -> j.dhash[i] = h.optLong(k) } } }
        o.optJSONObject("hashes")?.let { h -> h.keys().forEach { k -> j.hashes[k] = h.optInt(k) } }
        j.slots.forEach { p -> if (p != null) j.pics.add(p) }
        j.total = j.slots.size
        j.fails = j.failed.size
        return j
    }

    /** 저장하고 그만: 받던 걸 멈추고 목록에서 빼되, 받은 파일은 남겨 둔다 */
    fun detach(j: GrabJob) {
        j.canceled = true
        jobs.remove(j)
        changed(null, true)
    }

    /** 이미지 하나 추가하고 바로 받기 시작 (메인 스레드) */
    fun add(j: GrabJob, url: String, fill: Int? = null) {
        if (j.canceled) return
        if (fill != null && reuse(j, fill, url)) {
            fetch(j, fill, url, attempt = 1)
            return
        }
        val i = j.slots.size
        j.slots.add(null)
        j.urls = j.urls + url
        j.total = j.slots.size
        fetch(j, i, url, attempt = 1)
    }

    /** 페이지에서 직접 꺼낸 그림(주소 없는 그림)을 바로 저장 (메인 스레드) */
    fun addBytes(j: GrabJob, bytes: ByteArray, ext: String, fill: Int? = null) {
        if (j.canceled) return
        val i: Int
        val tag: String
        if (fill != null && reuse(j, fill, "page-image-${fill + 1}.$ext")) {
            i = fill
            tag = j.urls[i]
        } else {
            i = j.slots.size
            tag = "page-image-${i + 1}.$ext"
            j.slots.add(null)
            j.urls = j.urls + tag
            j.total = j.slots.size
        }
        pool.execute {
            val f = fileFor(j, i, tag)
            val ok = runCatching { FileOutputStream(f).use { it.write(bytes) } }.isSuccess
            main.post { fetched(j, i, tag, f, ok, if (ok) 200 else -1) }
        }
    }

    /** 실패했던 칸을 새 주소로 다시 씀 (앞에서 이어 받을 때 순서 유지) */
    private fun reuse(j: GrabJob, i: Int, url: String): Boolean {
        if (i !in j.failed || i >= j.slots.size) return false
        j.failed.remove(i)
        j.failReason.remove(i)?.let { c -> j.failCodes[c]?.let { n -> if (n <= 1) j.failCodes.remove(c) else j.failCodes[c] = n - 1 } }
        j.fails = j.failed.size
        j.urls = j.urls.toMutableList().also { it[i] = url }
        j.done--
        j.addLog("${i + 1}번(실패했던 칸)을 새로 찾은 그림으로 채움\n          $url")
        return true
    }

    /** 실패한 것만 다시 받기 */
    fun retryFailed(j: GrabJob) {
        if (j.failed.isEmpty() || j.active) return
        val list = j.failed.toList()
        j.failed.clear()
        j.failCodes.clear()
        j.fails = 0
        j.done -= list.size
        j.state = GrabJob.DOWNLOADING
        updateStatus(j)
        changed(j, true)
        list.forEach { i -> fetch(j, i, j.urls[i], attempt = 1) }
    }

    private fun fileFor(j: GrabJob, i: Int, url: String): File {
        val raw = try { URLDecoder.decode(Uri.parse(url).lastPathSegment ?: "img", "UTF-8") } catch (e: Exception) { "img" }
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "img" }
        return File(j.dir, (i + 1).toString().padStart(4, '0') + "_" + clean)
    }

    /** 응답을 사람이 읽을 수 있게 요약 (오류 로그용) */
    private fun describe(c: HttpURLConnection, code: Int): String {
        val sb = StringBuilder()
        sb.append("HTTP ").append(code)
        c.responseMessage?.takeIf { it.isNotBlank() }?.let { sb.append(' ').append(it) }
        sb.append(" · 형식 ").append(c.contentType ?: "없음")
        sb.append(" · 크기 ").append(c.contentLengthLong.let { if (it < 0) "?" else Downloads.size(it) })
        c.getHeaderField("Server")?.let { sb.append(" · 서버 ").append(it) }
        if (c.getHeaderField("CF-RAY") != null || c.getHeaderField("cf-mitigated") != null) sb.append(" · Cloudflare 보호")
        c.getHeaderField("Location")?.let { sb.append(" · 이동 → ").append(it) }
        // 오류 응답이 웹페이지면 제목을 읽어 둠 (예: "Just a moment..." = 보호 장치, "로그인")
        val type = c.contentType ?: ""
        if (type.contains("html", true)) {
            try {
                val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { ins ->
                    val buf = ByteArray(8192)
                    val n = ins.read(buf).coerceAtLeast(0)
                    String(buf, 0, n, Charsets.UTF_8)
                } ?: ""
                Regex("<title[^>]*>([^<]{1,120})", RegexOption.IGNORE_CASE).find(body)?.let {
                    sb.append(" · 페이지 제목 \"").append(it.groupValues[1].trim()).append('"')
                }
            } catch (e: Exception) {
            }
        }
        return sb.toString()
    }

    /**
     * 받기 하나.
     * 1) 페이지가 불러올 때 같이 받아 둔 파일이 있으면 그대로 사용
     * 2) 없으면 같은 조건(머리말·쿠키)으로 직접 요청 — 연결 오류·서버 오류는 2초·5초 뒤 다시
     * 3) 그래도 안 되면 브라우저 엔진으로 다시 (보호 장치가 앱 요청만 막는 경우)
     * 실패하면 단계마다 상세 내용을 작업 기록에 남긴다.
     */
    private fun fetch(j: GrabJob, i: Int, url: String, attempt: Int) {
        pool.execute {
            if (j.canceled) return@execute
            val f = fileFor(j, i, url)
            val no = i + 1

            // 1) 같이 받아 둔 파일
            var cap = j.captured[url]
            if (cap == null && j.state == GrabJob.COLLECTING && attempt == 1) {
                // 화면에서 주소를 먼저 찾고 이미지가 아직 내려오는 중일 수 있어 잠깐 기다림
                for (w in 0 until 25) {
                    Thread.sleep(200)
                    cap = j.captured[url]
                    if (cap != null || j.canceled) break
                }
            }
            val got = cap
            if (got != null && got.exists() && got.length() > 0) {
                if (got.renameTo(f) || (runCatching { got.copyTo(f, overwrite = true) }.isSuccess && f.length() > 0)) {
                    j.captured.remove(url)
                    main.post { fetched(j, i, url, f, true, 200) }
                    return@execute
                }
            }

            // 2) 직접 요청
            var code = -1
            var detail = ""
            try {
                val c = open(j, url)
                j.headers[url]?.forEach { (k, v) ->
                    if (!k.equals("Range", true) && !k.equals("Accept-Encoding", true) && !k.equals("Cookie", true)) {
                        c.setRequestProperty(k, v)
                    }
                }
                code = c.responseCode
                if (code in 200..299 && !(c.contentType ?: "").contains("html", true)) {
                    c.inputStream.use { ins -> FileOutputStream(f).use { ins.copyTo(it, 64 * 1024) } }
                    if (f.length() <= 0) { code = -3; detail = "빈 파일이 왔어요" }
                } else {
                    detail = describe(c, code)
                    if (code in 200..299) code = -2   // 이미지 대신 웹페이지
                }
                c.disconnect()
            } catch (e: Exception) {
                code = -1
                detail = e.javaClass.simpleName + ": " + (e.message ?: "")
            }
            if (code in 200..299) {
                main.post { fetched(j, i, url, f, true, code) }
                return@execute
            }
            f.delete()
            j.addLog("${no}번 직접 받기 실패 (시도 $attempt) · ${FailReason.short(code)} · $detail\n          $url")
            val retryable = code == -1 || code == 429 || code in 500..599
            if (retryable && attempt < 3 && !j.canceled) {
                retryTimer.schedule(Runnable { fetch(j, i, url, attempt + 1) }, if (attempt == 1) 2L else 5L,
                    java.util.concurrent.TimeUnit.SECONDS)
                return@execute
            }
            if (code == 404 || code == 410) {
                main.post { fetched(j, i, url, f, false, code) }
                return@execute
            }

            // 3) 브라우저 엔진으로 다시
            val referer = j.headers[url]?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value ?: j.referer
            j.addLog("${no}번 브라우저 엔진으로 다시 시도")
            EngineFetch.enqueue(j, url, referer) { file, msg ->
                // 메인 스레드
                if (file != null && file.length() > 0 && (file.renameTo(f) || runCatching { file.copyTo(f, true) }.isSuccess)) {
                    file.delete()
                    j.addLog("${no}번 브라우저 엔진으로 받음")
                    fetched(j, i, url, f, true, 200)
                } else {
                    file?.delete()
                    j.addLog("${no}번 브라우저 엔진도 실패 · $msg")
                    fetched(j, i, url, f, false, code)
                }
            }
        }
    }

    /** 작업 기록 전체 (저장·공유용) */
    fun logText(j: GrabJob): String {
        val sb = StringBuilder()
        sb.append("라이트 브라우저 작업 기록\n")
        sb.append("작업: ").append(j.title).append('\n')
        sb.append("방식: ").append(j.how).append('\n')
        sb.append("시작 페이지: ").append(j.referer ?: "-").append('\n')
        sb.append("브라우저 정보: ").append(j.ua ?: "-").append('\n')
        sb.append("상태: ").append(j.status.replace("\n", " ")).append('\n')
        sb.append("전체 ").append(j.total).append(" · 받음 ").append(j.pics.size).append(" · 실패 ").append(j.fails).append('\n')
        if (j.fails > 0) sb.append("실패 이유: ").append(j.failSummary()).append('\n')
        sb.append("차단 우회: ").append(if (Bypass.port() != null) "켜짐" else "꺼짐").append('\n')
        sb.append("\n---- 기록 ----\n")
        sb.append(j.log)
        if (j.failed.isNotEmpty()) {
            sb.append("\n---- 실패한 이미지 ----\n")
            failList(j).forEach { (i, url, code) -> sb.append(i + 1).append("번 ").append(FailReason.short(code)).append("  ").append(url).append('\n') }
        }
        return sb.toString()
    }

    private fun fetched(j: GrabJob, i: Int, url: String, f: File, ok: Boolean, code: Int) {
        if (j.canceled || i >= j.slots.size || j.urls.getOrNull(i) != url) return
        if (ok) {
            val h = hashOf(f)
            val same = h?.let { j.hashes[it] }
            if (same != null && same != i) {
                // 주소는 달라도 이미 받은 그림과 똑같음 → 지우고 건너뜀 (넘기기가 실제로 안 됐을 수 있음)
                f.delete()
                j.dupSlots.add(i)
                j.dupOf[i] = same
                j.addLog("${i + 1}번은 ${same + 1}번과 똑같은 그림이라 건너뜀 (주소만 다름)\n          $url")
                if (j.expect.containsKey(url)) checkScreen(j, i, url)
                onDuplicate?.invoke(j, same)
                j.done++
                updateStatus(j)
                if (j.state != GrabJob.COLLECTING && j.done >= j.total) finish(j) else changed(j)
                return
            }
            if (h != null) j.hashes[h] = i
            j.slots[i] = GrabJob.Pic(url, f)
            j.pics.clear()
            j.slots.forEach { p -> if (p != null) j.pics.add(p) }
            j.failReason.remove(i)
            if (j.expect.containsKey(url)) checkScreen(j, i, url)
            pool.execute { dHashFile(f)?.let { j.dhash[i] = it } }
        } else {
            j.failed.add(i)
            j.failCodes[code] = (j.failCodes[code] ?: 0) + 1
            j.failReason[i] = code
        }
        j.done++
        j.fails = j.failed.size
        updateStatus(j)
        if (j.state != GrabJob.COLLECTING && j.done >= j.total) finish(j) else changed(j)
    }

    // ================= 화면과 비교 =================

    /** 넘기며 모으기가 화면에 보인 그림을 찍어 보냄 (아무 스레드) */
    fun expectShot(j: GrabJob, url: String, hash: Long, jpg: ByteArray) {
        j.expect[url] = hash to jpg
        main.post {
            val i = j.urls.indexOf(url)
            if (i >= 0 && (j.slots.getOrNull(i) != null || i in j.dupSlots)) checkScreen(j, i, url)
        }
    }

    /** 받은 그림(또는 중복이라 버린 그림)을 화면에 보였던 그림과 비교 → 다르면 화면 캡처로 바꿈 (메인 스레드) */
    private fun checkScreen(j: GrabJob, i: Int, url: String) {
        val e = j.expect.remove(url) ?: return
        val dup = i in j.dupSlots
        val f = if (dup) j.slots.getOrNull(j.dupOf[i] ?: -1)?.file else j.slots.getOrNull(i)?.file
        if (f == null || !f.exists()) return
        pool.execute {
            val h = dHashFile(f) ?: return@execute
            val dist = java.lang.Long.bitCount(h xor e.first)
            if (dist <= 14) {
                if (!dup) j.addLog("${i + 1}번 화면과 같음 확인 (차이 $dist)")
                return@execute
            }
            // 화면에 보였던 그림과 다름 → 페이지가 받은 원본 중 화면과 같은 걸 찾고, 없으면 화면 캡처로
            val orig = bestOriginal(j, e.first)
            val nf = File(j.dir, (i + 1).toString().padStart(4, '0') + (if (orig != null) "_page." + orig.ext else "_screen.jpg"))
            val ok = runCatching { FileOutputStream(nf).use { it.write(orig?.bytes ?: e.second) } }.isSuccess
            main.post {
                if (!ok || j.canceled || i >= j.slots.size) return@post
                if (dup) {
                    j.dupSlots.remove(i)
                    j.dupOf.remove(i)
                } else {
                    j.slots[i]?.file?.let { old -> if (old != nf) old.delete() }
                }
                j.slots[i] = GrabJob.Pic(url, nf)
                j.fixed.add(i)
                j.pics.clear()
                j.slots.forEach { p -> if (p != null) j.pics.add(p) }
                j.addLog("${i + 1}번 받은 그림이 화면과 달라서(차이 $dist) " + (if (orig != null) "원본 화질 그림으로" else "화면 캡처로") + " 바꿈" +
                    (if (dup) " · 중복으로 버렸던 장" else "") + "\n          $url")
                updateStatus(j)
                changed(j)
            }
        }
    }

    /** 페이지가 최근 불러온 원본 중 화면 그림(지문)과 같은 것 (아무 스레드) */
    fun bestOriginal(j: GrabJob, hash: Long): GrabJob.Recent? {
        var best: GrabJob.Recent? = null
        var bestD = 11
        for (r in j.recent.descendingIterator()) {
            val h = r.hash ?: dHashBytes(r.bytes)?.also { r.hash = it } ?: continue
            val d = java.lang.Long.bitCount(h xor hash)
            if (d < bestD) { bestD = d; best = r }
        }
        return best
    }

    private fun dHashBytes(b: ByteArray): Long? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 64 && o.outHeight / (sample * 2) >= 64) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?.let { val h = dHash(it); it.recycle(); h }
    } catch (e: Throwable) { null }

    /** 그림 지문(dHash 64비트): 크기·화질이 달라도 같은 그림이면 비슷하게 나옴 */
    fun dHash(b: android.graphics.Bitmap): Long {
        val s = android.graphics.Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val l = gray(s.getPixel(x, y))
            val r = gray(s.getPixel(x + 1, y))
            h = (h shl 1) or (if (l > r) 1L else 0L)
        }
        if (s !== b) s.recycle()
        return h
    }

    private fun gray(c: Int) = ((c shr 16) and 0xff) * 3 + ((c shr 8) and 0xff) * 6 + (c and 0xff)

    private fun dHashFile(f: File): Long? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 64 && o.outHeight / (sample * 2) >= 64) sample *= 2
        val b = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        b?.let { val h = dHash(it); it.recycle(); h }
    } catch (e: Throwable) { null }

    private fun hashOf(f: File): String? = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) } + ":" + f.length()
    } catch (e: Exception) { null }

    /** 실패한 이미지 목록: (순번, 주소, 실패 코드) */
    fun failList(j: GrabJob): List<Triple<Int, String, Int>> =
        j.failed.map { i -> Triple(i, j.urls.getOrElse(i) { "" }, j.failReason[i] ?: -1) }

    // ================= 페이지와 함께 받기 =================

    /**
     * 모으는 중인 탭이 이미지를 요청할 때 앱이 대신 받아서
     * 1) 페이지에는 그대로 넘겨 주고 2) 같은 내용을 작업 폴더에 저장해 둔다.
     * (shouldInterceptRequest에서 부름: 다른 스레드) 실패하면 null → 웹뷰가 원래대로 받음
     */
    fun interceptImage(j: GrabJob, url: String, reqHeaders: Map<String, String>): android.webkit.WebResourceResponse? {
        if (j.canceled) return null
        return try {
            val c = open(j, url)
            reqHeaders.forEach { (k, v) ->
                if (!k.equals("Range", true) && !k.equals("Accept-Encoding", true) && !k.equals("Cookie", true)) {
                    c.setRequestProperty(k, v)
                }
            }
            val code = c.responseCode
            val type = c.contentType ?: ""
            val msg = c.responseMessage?.takeIf { it.isNotBlank() } ?: "OK"
            if (code !in 200..299 || type.contains("html", true)) {
                c.disconnect()
                return null
            }
            val bytes = c.inputStream.use { it.readBytes() }
            // 서버가 보낸 쿠키는 브라우저에도 반영
            c.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(url, it) }
            val outHeaders = HashMap<String, String>()
            c.headerFields.forEach { (k, v) ->
                if (k != null && v != null && !k.equals("Content-Encoding", true) && !k.equals("Content-Length", true) &&
                    !k.equals("Transfer-Encoding", true) && !k.equals("Set-Cookie", true)
                ) outHeaders[k] = v.joinToString(", ")
            }
            c.disconnect()
            if (bytes.isNotEmpty() && !j.canceled) {
                val dir = File(j.dir, "cap").apply { mkdirs() }
                val f = File(dir, "c" + System.nanoTime())
                FileOutputStream(f).use { it.write(bytes) }
                j.captured.putIfAbsent(url, f)
                // 최근 원본으로도 보관 (같은 주소에 다른 그림이 와도 원본 화질로 저장할 수 있게)
                val ext = when {
                    type.contains("png") -> "png"; type.contains("webp") -> "webp"; type.contains("gif") -> "gif"
                    type.contains("avif") -> "avif"; else -> "jpg"
                }
                j.recent.addLast(GrabJob.Recent(url, bytes, ext))
                while (j.recent.size > 15) j.recent.pollFirst()
            }
            val mime = type.substringBefore(';').trim().ifEmpty { "image/*" }
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
            android.webkit.WebResourceResponse(mime, charset, code, msg, outHeaders,
                java.io.ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun updateStatus(j: GrabJob) {
        val head = if (j.state == GrabJob.COLLECTING) "모으면서 받는 중…" else "받는 중…"
        j.status = "$head ${j.done} / ${j.total}" + (if (j.dups > 0) " · 중복 ${j.dups} 건너뜀" else "") + (if (j.fixed.isNotEmpty()) " · 화면으로 고침 ${j.fixed.size}" else "") + if (j.fails > 0) " (실패 ${j.fails}: ${j.failSummary()})" else ""
    }

    private fun finish(j: GrabJob) {
        j.state = GrabJob.DONE
        val size = j.pics.sumOf { it.file.length() }
        j.status = "${j.pics.size}개 받음 · ${Downloads.size(size)}" + (if (j.dups > 0) " · 중복 ${j.dups} 건너뜀" else "") + if (j.fails > 0) " · 실패 ${j.fails} (${j.failSummary()})" else ""
        changed(j, true)
        BgWork.jobDone(app, j)
        onFinished?.invoke(j)
    }
}
