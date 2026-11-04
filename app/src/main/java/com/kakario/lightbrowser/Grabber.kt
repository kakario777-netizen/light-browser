package com.kakario.lightbrowser

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
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 이미지 폴더 받기.
 * 1) 이미지가 들어 있는 서버 폴더의 파일 목록을 열어 이미지를 모두 찾는다
 * 2) 목록이 막혀 있으면 파일 이름의 번호를 앞뒤로 이어 가며 찾는다 (001, 002 …)
 * 받은 이미지는 격자로 보여주고, 고른 것만 ZIP으로 압축하거나 낱개로 저장한다.
 */
class Grabber(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val thumbPool = Executors.newFixedThreadPool(2)

    private var root: View? = null
    private var statusText: TextView? = null
    private var bar: ProgressBar? = null
    private var actions: LinearLayout? = null
    private var failBox: LinearLayout? = null
    private var grid: RecyclerView? = null
    private var preview: View? = null

    @Volatile private var canceled = false
    private var dir: File? = null
    private var referer: String? = null
    private var ua: String? = null
    private var title = "images"

    class Pic(val url: String, val file: File, var selected: Boolean = true)

    private val pics = mutableListOf<Pic>()
    private val adapter = Adapter()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val isOpen get() = root != null

    // ================= 시작 =================

    /** imageUrl: 기준 이미지 주소, pageUrl: 지금 페이지(Referer로 씀) */
    private fun prepare(name: String, pageUrl: String?, userAgent: String?) {
        if (isOpen) close()
        referer = pageUrl
        ua = userAgent
        canceled = false
        pics.clear()
        cache.evictAll()
        title = name
        dir = File(a.cacheDir, "grab/" + System.currentTimeMillis()).apply { mkdirs() }
        buildUi()
    }

    /** 넘기며 모으기 등에서 이미 주소 목록을 가진 경우 */
    fun startWithUrls(urls: List<String>, pageUrl: String?, userAgent: String?, name: String) {
        prepare(name, pageUrl, userAgent)
        if (urls.isEmpty()) {
            showFail("모은 이미지가 없어요.", null)
            return
        }
        confirmDownload(urls, "넘기며 모으기")
    }

    fun start(imageUrl: String, pageUrl: String?, userAgent: String?) {
        if (!imageUrl.startsWith("http")) {
            a.toastMsg("이 이미지는 서버 주소가 없어서 폴더를 찾을 수 없어요. '넘기며 모으기'를 써 보세요")
            return
        }
        val folder = folderOf(imageUrl)
        prepare(folderName(folder), pageUrl, userAgent)
        setStatus("폴더 확인 중…\n$folder", indeterminate = true)

        Thread {
            var urls: List<String> = emptyList()
            var how = ""
            try {
                urls = listFolder(folder)
                if (urls.isNotEmpty()) how = "폴더 목록"
            } catch (e: Exception) {
            }
            if (urls.isEmpty() && !canceled) {
                main.post { setStatus("폴더 목록이 막혀 있어요. 번호로 이어 찾는 중…", indeterminate = true) }
                val run = lastRunInFile(imageUrl)
                if (run != null) {
                    urls = sequence(imageUrl, run, maxMiss = 8, tryExts = false, strip = false)
                    if (urls.size > 1) how = "번호 이어 찾기"
                }
            }
            main.post {
                if (canceled || !isOpen) return@post
                when {
                    urls.size <= 1 -> showFail(
                        "이 폴더에서는 이미지를 더 찾지 못했어요.\n" +
                            "주소를 나눠 보고 다른 번호로 찾거나, 페이지를 넘기며 모아 보세요.", imageUrl
                    )
                    else -> confirmDownload(urls, how)
                }
            }
        }.start()
    }

    /** 못 찾았을 때: 주소 분석 / 넘기며 모으기 버튼 */
    private fun showFail(msg: String, imageUrl: String?) {
        setStatus(msg, indeterminate = false)
        val box = failBox ?: return
        box.removeAllViews()
        box.visibility = View.VISIBLE
        if (imageUrl != null) {
            box.addView(button("주소 분석해서 찾기", filled = true) { analyze(imageUrl, referer, ua) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(46)).apply { bottomMargin = a.dp(8) })
        }
        box.addView(button("페이지 넘기며 모으기", filled = imageUrl == null) {
            close()
            a.startCollector()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(46)))
    }

    // ================= 주소 분석 =================

    private class Run(val start: Int, val end: Int)

    /** 주소에서 사이트 이름 뒤에 있는 숫자 덩어리들 */
    private fun runs(url: String): List<Run> {
        val hostEnd = url.indexOf('/', url.indexOf("://") + 3).let { if (it < 0) url.length else it }
        return Regex("\\d+").findAll(url).filter { it.range.first >= hostEnd && it.value.length <= 12 }
            .map { Run(it.range.first, it.range.last + 1) }.toList()
    }

    /** 파일 이름 안의 마지막 숫자 (기본 선택) */
    private fun lastRunInFile(url: String): Run? {
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val fileStart = url.lastIndexOf('/', q - 1) + 1
        return runs(url).lastOrNull { it.start >= fileStart && it.end <= q }
    }

    /** 이미지 주소를 나눠 보여주고, 어느 번호를 바꿔 가며 찾을지 고르게 한다 */
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
        val allRuns = runs(imageUrl)

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
        val def = lastRunInFile(imageUrl) ?: allRuns.lastOrNull()
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
            isChecked = false
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
            .setView(android.widget.ScrollView(a).apply { addView(body) })
            .setNegativeButton("닫기", null)
            .setNeutralButton("넘기며 모으기") { _, _ ->
                close()
                a.startCollector()
            }
        if (allRuns.isNotEmpty()) b.setPositiveButton("찾기") { _, _ ->
            val run = allRuns.getOrNull(group.checkedRadioButtonId - 100) ?: def ?: return@setPositiveButton
            val miss = misses.getOrElse(missGroup.checkedRadioButtonId - 200) { 20 }
            runAnalyzed(imageUrl, pageUrl, userAgent, run, miss, extBox.isChecked, stripBox.isChecked && q >= 0)
        }
        b.show()
    }

    private fun runAnalyzed(url: String, pageUrl: String?, userAgent: String?, run: Run, miss: Int, exts: Boolean, strip: Boolean) {
        prepare(folderName(folderOf(url)), pageUrl, userAgent)
        setStatus("번호로 이어 찾는 중…", indeterminate = true)
        Thread {
            val urls = sequence(url, run, miss, exts, strip)
            main.post {
                if (canceled || !isOpen) return@post
                if (urls.size <= 1) showFail("이 번호로는 이미지를 더 찾지 못했어요.\n다른 번호를 골라 보거나 넘기며 모아 보세요.", url)
                else confirmDownload(urls, "번호 이어 찾기")
            }
        }.start()
    }

    private fun confirmDownload(urls: List<String>, how: String) {
        failBox?.visibility = View.GONE
        setStatus("$how 으로 이미지 ${urls.size}개를 찾았어요", indeterminate = false)
        AlertDialog.Builder(a)
            .setTitle("이미지 ${urls.size}개")
            .setMessage("$title\n\n모두 받은 뒤 확인하고 고를 수 있어요.")
            .setPositiveButton("받기") { _, _ -> downloadAll(urls) }
            .setNegativeButton("취소") { _, _ -> close() }
            .setCancelable(false)
            .show()
    }

    // ================= 주소 다루기 =================

    private fun folderOf(url: String): String {
        val u = URI(url)
        val path = (u.rawPath ?: "/").substringBeforeLast('/') + "/"
        return URI(u.scheme, u.rawAuthority, null, null, null).toString().removeSuffix("/") + path
    }

    private fun folderName(folder: String): String {
        val p = Uri.parse(folder).pathSegments.lastOrNull()
        val n = try { URLDecoder.decode(p ?: "", "UTF-8") } catch (e: Exception) { p ?: "" }
        return n.ifBlank { Uri.parse(folder).host ?: "images" }
    }

    private val imgExt = Regex("\\.(jpe?g|png|gif|webp|bmp|avif|heic|jfif)$", RegexOption.IGNORE_CASE)

    private fun isImageUrl(u: String) = imgExt.containsMatchIn(Uri.parse(u).path ?: "")

    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        val port = Bypass.port()
        val c = (if (port != null) URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        else URL(url).openConnection()) as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        ua?.let { c.setRequestProperty("User-Agent", it) }
        referer?.let { c.setRequestProperty("Referer", it) }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        return c
    }

    // ================= 1) 폴더 목록 =================

    /** 폴더 주소를 열어 파일 목록(Index of …)이 나오면 그 안의 이미지를 모은다. 하위 폴더도 3단계까지 */
    private fun listFolder(folder: String): List<String> {
        val found = LinkedHashSet<String>()
        val seen = HashSet<String>()
        fun walk(dirUrl: String, depth: Int) {
            if (canceled || !seen.add(dirUrl) || found.size >= 5000) return
            val c = open(dirUrl)
            if (c.responseCode != 200) { c.disconnect(); return }
            val type = c.contentType ?: ""
            if (!type.contains("html", true)) { c.disconnect(); return }
            val html = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val base = URI(dirUrl)
            val subs = mutableListOf<String>()
            val files = mutableListOf<String>()
            for (m in Regex("href\\s*=\\s*[\"']([^\"'#?]+)[\"']", RegexOption.IGNORE_CASE).findAll(html)) {
                val h = m.groupValues[1]
                if (h.startsWith("javascript:") || h.startsWith("mailto:")) continue
                val abs = try { base.resolve(h.replace(" ", "%20")).toString() } catch (e: Exception) { continue }
                // 같은 폴더 아래에 있는 것만 (상위 폴더·다른 사이트 링크 제외)
                if (!abs.startsWith(dirUrl) || abs == dirUrl) continue
                if (abs.endsWith("/")) subs.add(abs)
                else if (isImageUrl(abs)) files.add(abs)
            }
            files.sortedWith(natural).forEach { found.add(it) }
            if (depth < 3) subs.distinct().sortedWith(natural).forEach { walk(it, depth + 1) }
        }
        walk(folder, 0)
        return found.toList()
    }

    /** 1, 2, 10 순서로 (문자 순서면 1, 10, 2가 됨) */
    private val natural = Comparator<String> { x, y ->
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

    private val exts = listOf("jpg", "jpeg", "png", "webp", "gif")
    private val extRe = Regex("\\.(jpe?g|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)

    private fun withExt(u: String, ext: String): String {
        val q = u.indexOf('?')
        val path = if (q >= 0) u.substring(0, q) else u
        val rest = if (q >= 0) u.substring(q) else ""
        return if (extRe.containsMatchIn(path)) path.replace(extRe, ".$ext") + rest else u
    }

    /**
     * 주소의 run 위치 숫자를 앞뒤로 바꿔 가며 실제로 있는 이미지를 찾는다.
     * 6개씩 동시에 확인해서 빠르게, 빈 번호가 maxMiss개 이어지면 멈춤.
     */
    private fun sequence(url: String, run: Run, maxMiss: Int, tryExts: Boolean, strip: Boolean): List<String> {
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
            if (exists(base)) return base
            if (!tryExts) return null
            val order = (listOfNotNull(preferredExt) + exts).distinct()
            for (e in order) {
                val c = withExt(base, e)
                if (c != base && exists(c)) { preferredExt = e; return c }
            }
            return null
        }

        // 기준 이미지 자체 (원본 찾기를 켰는데 원본이 없으면 그대로 사용)
        val self = probe(start) ?: url
        val found = java.util.TreeMap<Long, String>()
        found[start] = self
        val checked = AtomicInteger(0)
        fun scan(step: Int) {
            var n = start + step
            var miss = 0
            while (!canceled && miss < maxMiss && n >= 0 && found.size < 5000) {
                val batch = (0 until 6).map { n + it * step }.filter { it >= 0 }
                if (batch.isEmpty()) break
                val futures = batch.map { k -> pool.submit<String?> { if (canceled) null else probe(k) } }
                for ((i, f) in futures.withIndex()) {
                    val hit = try { f.get() } catch (e: Exception) { null }
                    if (hit != null) { found[batch[i]] = hit; miss = 0 } else miss++
                    if (miss >= maxMiss) break
                }
                n += 6L * step
                val c = checked.addAndGet(batch.size)
                main.post { setStatus("번호로 이어 찾는 중… ${found.size}개 찾음 (${c}번 확인)", indeterminate = true) }
            }
        }
        scan(-1)
        scan(+1)
        return found.values.toList()
    }

    private fun exists(url: String): Boolean {
        return try {
            var c = open(url, "HEAD")
            var code = c.responseCode
            if (code == 405 || code == 403 || code == 501) {
                // HEAD를 안 받는 서버는 첫 1바이트만 요청
                c.disconnect()
                c = open(url)
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

    private fun downloadAll(urls: List<String>) {
        val total = urls.size
        val doneCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val digits = total.toString().length.coerceAtLeast(3)
        setStatus("받는 중… 0 / $total", indeterminate = false, max = total)
        val slots = arrayOfNulls<Pic>(total)
        urls.forEachIndexed { i, url ->
            pool.execute {
                if (canceled) return@execute
                val raw = try { URLDecoder.decode(Uri.parse(url).lastPathSegment ?: "img", "UTF-8") } catch (e: Exception) { "img" }
                val clean = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val f = File(dir, (i + 1).toString().padStart(digits, '0') + "_" + clean)
                var ok = false
                try {
                    val c = open(url)
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
                    if (!isOpen) return@post
                    if (ok) {
                        slots[i] = Pic(url, f)
                        // 원래 순서대로 보이도록 받은 것 중 순서대로 다시 채움
                        pics.clear()
                        slots.forEach { p -> if (p != null) pics.add(p) }
                        adapter.notifyDataSetChanged()
                    }
                    val fails = failCount.get()
                    setStatus("받는 중… $n / $total" + if (fails > 0) " (실패 $fails)" else "", indeterminate = false, max = total, value = n)
                    if (n == total) finishedDownloading(fails)
                }
            }
        }
    }

    private fun finishedDownloading(fails: Int) {
        val size = pics.sumOf { it.file.length() }
        setStatus(
            "${pics.size}개 받음 · ${Downloads.size(size)}" + (if (fails > 0) " · 실패 $fails" else "") +
                "\n눌러서 빼거나 넣고, 꾸욱 눌러 크게 보기",
            indeterminate = false
        )
        bar?.visibility = View.GONE
        actions?.visibility = View.VISIBLE
        updateCount()
    }

    // ================= 화면 =================

    private fun buildUi() {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(a.getColor(R.color.bar))
            isClickable = true
        }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(4), a.dp(4), a.dp(8), a.dp(4))
        }
        head.addView(a.iconBtn(R.drawable.ic_back, "닫기") { askClose() }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
        head.addView(TextView(a).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

        // 아래: 전체 선택 / ZIP / 낱개 저장
        val act = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(10))
            visibility = View.GONE
        }
        act.addView(button("전체", filled = false) {
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
            if (filled) setColor(a.getColor(R.color.accent)) else {
                setColor(a.getColor(R.color.field))
            }
        }
        setOnClickListener { onClick() }
    }

    private fun setStatus(text: String, indeterminate: Boolean, max: Int = 0, value: Int = 0) {
        statusText?.text = text
        bar?.let {
            it.visibility = View.VISIBLE
            it.isIndeterminate = indeterminate
            if (!indeterminate) {
                it.max = max.coerceAtLeast(1)
                it.progress = value
                if (max == 0) it.visibility = View.GONE
            }
        }
    }

    private fun updateCount() {
        val sel = pics.count { it.selected }
        val size = pics.filter { it.selected }.sumOf { it.file.length() }
        if (actions?.visibility == View.VISIBLE) {
            statusText?.text = "${pics.size}개 중 ${sel}개 선택 · ${Downloads.size(size)}\n눌러서 빼거나 넣고, 꾸욱 눌러 크게 보기"
        }
    }

    // ================= 저장 =================

    private fun chosen() = pics.filter { it.selected && it.file.exists() }

    private fun saveZip() {
        val list = chosen()
        if (list.isEmpty()) { a.toastMsg("고른 이미지가 없어요"); return }
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "images" } + ".zip"
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
            if (item.state == Downloads.DONE) askCleanup("$name 저장했어요 (${list.size}개)")
        }
        a.toastMsg("압축 중… 다운로드 화면에서 진행 상황을 볼 수 있어요")
    }

    private fun saveEach() {
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
            }) { if (left.decrementAndGet() == 0) askCleanup("${list.size}개 저장했어요") }
        }
    }

    private fun askCleanup(msg: String) {
        if (!isOpen) return
        AlertDialog.Builder(a)
            .setMessage("$msg\n\n이 화면을 닫을까요? (임시로 받은 이미지는 지워져요)")
            .setPositiveButton("닫기") { _, _ -> close() }
            .setNegativeButton("계속 보기", null)
            .show()
    }

    private fun askClose() {
        if (pics.isEmpty()) { close(); return }
        AlertDialog.Builder(a)
            .setMessage("닫으면 임시로 받은 이미지가 지워져요. 닫을까요?")
            .setPositiveButton("닫기") { _, _ -> close() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 뒤로가기: 크게 보기 → 화면 닫기 순서 */
    fun back(): Boolean {
        if (preview != null) { closePreview(); return true }
        if (isOpen) { askClose(); return true }
        return false
    }

    fun close() {
        canceled = true
        closePreview()
        root?.let { a.frame.removeView(it) }
        root = null
        grid = null
        failBox = null
        pics.clear()
        cache.evictAll()
        val d = dir
        dir = null
        if (d != null) Thread { d.deleteRecursively() }.start()
    }

    // ================= 크게 보기 =================

    private fun openPreview(index: Int) {
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
        // 화면 왼쪽 1/3 이전, 오른쪽 1/3 다음, 가운데 닫기
        box.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
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
            val p = pics[position]
            val cell = h.itemView as FrameLayout
            val w = (grid?.width ?: a.resources.displayMetrics.widthPixels) / ((grid?.layoutManager as? GridLayoutManager)?.spanCount ?: 3)
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
