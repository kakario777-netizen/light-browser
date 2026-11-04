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
import java.util.concurrent.atomic.AtomicInteger

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
    val pics = mutableListOf<Pic>() // 메인 스레드에서만
    internal var slots: Array<Pic?> = emptyArray()

    class Pic(val url: String, val file: File, var selected: Boolean = true)

    val active get() = state == FINDING || state == DOWNLOADING

    companion object {
        const val FINDING = 0
        const val FOUND = 1        // 찾았고 받기 확인 기다림
        const val DOWNLOADING = 2
        const val DONE = 3
        const val NOTHING = 4      // 못 찾음
        const val CANCELED = 5
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

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
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
        val j = GrabJob(id, title, File(app.cacheDir, "grab/$id").apply { mkdirs() }, referer, ua)
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
        if (j.active) {
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

    fun download(j: GrabJob, urls: List<String> = j.urls) {
        if (urls.isEmpty()) return
        j.urls = urls
        j.state = GrabJob.DOWNLOADING
        j.total = urls.size
        j.done = 0
        j.fails = 0
        j.slots = arrayOfNulls(urls.size)
        j.pics.clear()
        j.status = "받는 중… 0 / ${urls.size}"
        changed(j, true)
        val doneCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val digits = urls.size.toString().length.coerceAtLeast(3)
        urls.forEachIndexed { i, url ->
            pool.execute {
                if (j.canceled) return@execute
                val raw = try { URLDecoder.decode(Uri.parse(url).lastPathSegment ?: "img", "UTF-8") } catch (e: Exception) { "img" }
                val clean = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
                val f = File(j.dir, (i + 1).toString().padStart(digits, '0') + "_" + clean)
                var ok = false
                try {
                    val c = open(j, url)
                    if (c.responseCode in 200..299 && !(c.contentType ?: "").contains("html", true)) {
                        c.inputStream.use { ins -> FileOutputStream(f).use { ins.copyTo(it, 64 * 1024) } }
                        ok = f.length() > 0
                    }
                    c.disconnect()
                } catch (e: Exception) {
                }
                if (!ok) { f.delete(); failCount.incrementAndGet() }
                val n = doneCount.incrementAndGet()
                main.post {
                    if (j.canceled) return@post
                    if (ok) {
                        j.slots[i] = GrabJob.Pic(url, f)
                        j.pics.clear()
                        j.slots.forEach { p -> if (p != null) j.pics.add(p) }
                    }
                    j.done = n
                    j.fails = failCount.get()
                    j.status = "받는 중… $n / ${j.total}" + if (j.fails > 0) " (실패 ${j.fails})" else ""
                    if (n == j.total) finish(j) else changed(j)
                }
            }
        }
    }

    private fun finish(j: GrabJob) {
        j.state = GrabJob.DONE
        val size = j.pics.sumOf { it.file.length() }
        j.status = "${j.pics.size}개 받음 · ${Downloads.size(size)}" + if (j.fails > 0) " · 실패 ${j.fails}" else ""
        changed(j, true)
        BgWork.jobDone(app, j)
        onFinished?.invoke(j)
    }
}
