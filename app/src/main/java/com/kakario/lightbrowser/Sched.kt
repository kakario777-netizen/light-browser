package com.kakario.lightbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 예약 저장: 정해 둔 페이지를 정해 둔 때마다 몰래 열어서, 안에 있는 그림 중 아직 안 받은 것만 저장.
 * 화면이 꺼져 있어도 돈다 (생방 녹화 예약처럼). 저장 위치: 다운로드/라이트 브라우저/<이름>/
 */
object Sched {

    class Item(
        val id: Long,
        var url: String,
        var name: String,
        var everyMin: Int,          // 몇 분마다 (0이면 매일 정해진 시각)
        var atHour: Int = 9,
        var atMin: Int = 0,
        var minPx: Int = 300,       // 이보다 작은 그림(아이콘·광고)은 안 받음
        var enabled: Boolean = true,
        var lastRun: Long = 0,
        var lastResult: String = "",
        var total: Int = 0,
        var nextAt: Long = 0,
        var mode: Int = 0,          // 0: 이 페이지 그림, 1: 목록에 새 글이 올라오면 그 글의 그림
        var patterns: List<String> = emptyList(),   // 새 글 링크 모양 (mode 1)
    ) {
        fun toJson() = JSONObject().put("id", id).put("url", url).put("name", name).put("every", everyMin)
            .put("h", atHour).put("m", atMin).put("min", minPx).put("on", enabled).put("last", lastRun)
            .put("res", lastResult).put("total", total).put("next", nextAt).put("mode", mode)
            .put("pat", JSONArray(patterns))

        fun whenText(): String = when {
            everyMin == 0 -> "매일 %02d:%02d".format(atHour, atMin)
            everyMin % 1440 == 0 -> "${everyMin / 1440}일마다"
            everyMin % 60 == 0 -> "${everyMin / 60}시간마다"
            else -> "${everyMin}분마다"
        }

        companion object {
            fun of(o: JSONObject) = Item(o.getLong("id"), o.getString("url"), o.optString("name"), o.optInt("every", 180),
                o.optInt("h", 9), o.optInt("m", 0), o.optInt("min", 300), o.optBoolean("on", true), o.optLong("last"),
                o.optString("res"), o.optInt("total"), o.optLong("next"), o.optInt("mode"),
                o.optJSONArray("pat")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList())
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sched", Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<Item> = try {
        val a = JSONArray(prefs(ctx).getString("items", "[]"))
        MutableList(a.length()) { Item.of(a.getJSONObject(it)) }
    } catch (e: Exception) { mutableListOf() }

    @Synchronized
    fun saveAll(ctx: Context, items: List<Item>) {
        prefs(ctx).edit().putString("items", JSONArray(items.map { it.toJson() }).toString()).apply()
    }

    @Synchronized
    fun update(ctx: Context, id: Long, f: (Item) -> Unit) {
        val l = list(ctx)
        l.firstOrNull { it.id == id }?.let(f)
        saveAll(ctx, l)
    }

    fun add(ctx: Context, it: Item, runNow: Boolean) {
        val l = list(ctx)
        l.add(it)
        saveAll(ctx, l)
        plan(ctx, it, if (runNow) 5_000L else null)
    }

    fun remove(ctx: Context, id: Long) {
        saveAll(ctx, list(ctx).filter { it.id != id })
        WorkManager.getInstance(ctx).cancelUniqueWork("sched-$id")
        File(ctx.filesDir, "sched/$id.json").delete()
        File(ctx.filesDir, "sched/$id.links").delete()
    }

    /** 다음 실행 예약 (delay를 주면 그만큼 뒤에, 아니면 주기·시각대로) */
    fun plan(ctx: Context, it: Item, delay: Long? = null) {
        val wm = WorkManager.getInstance(ctx)
        if (!it.enabled) {
            wm.cancelUniqueWork("sched-${it.id}")
            update(ctx, it.id) { x -> x.nextAt = 0 }
            return
        }
        val now = System.currentTimeMillis()
        val at = delay?.let { now + it } ?: nextTime(it, now)
        update(ctx, it.id) { x -> x.nextAt = at }
        val req = OneTimeWorkRequestBuilder<SchedWorker>()
            .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to it.id))
            .build()
        wm.enqueueUniqueWork("sched-${it.id}", ExistingWorkPolicy.REPLACE, req)
    }

    private fun nextTime(it: Item, now: Long): Long {
        if (it.everyMin > 0) return now + it.everyMin * 60_000L
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, it.atHour); set(Calendar.MINUTE, it.atMin); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now + 60_000) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** 앱을 켤 때: 예약이 빠져 있으면 다시 걸어 둠 (폰을 껐다 켠 경우 등) */
    fun ensure(ctx: Context) {
        val now = System.currentTimeMillis()
        list(ctx).filter { it.enabled }.forEach { if (it.nextAt < now - 60_000) plan(ctx, it, 10_000L) }
    }

    // ---------------- 실제 실행 ----------------

    private val main = Handler(Looper.getMainLooper())

    /** 페이지를 화면 없이 열어서 그림 주소들을 모음 (주소 → 그림 너비, 모르면 -1) */
    fun collect(ctx: Context, url: String, onlyPost: Boolean = false): Pair<Map<String, Int>, String> {
        val (res, title) = loadAndRun(ctx, url, if (onlyPost) POST_JS else COLLECT_JS, scroll = true)
        val m = LinkedHashMap<String, Int>()
        try {
            val o = JSONObject(res)
            o.keys().forEach { key -> m[key] = o.optInt(key, -1) }
        } catch (e: Exception) {
        }
        return m to title
    }

    /** 페이지를 화면 없이 열고(새 탭처럼) JS를 돌린 결과와 제목. 끝나면 그 '탭'은 닫음 */
    fun loadAndRun(ctx: Context, url: String, js: String, scroll: Boolean): Pair<String, String> {
        val done = CountDownLatch(1)
        var result = ""
        var title = ""
        main.post {
            val wv = WebView(ctx.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.loadsImagesAutomatically = true
            wv.settings.offscreenPreRaster = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            // 화면에 붙이지 않고 폰 화면 크기로 그려지게
            val w = ctx.resources.displayMetrics.widthPixels
            val h = ctx.resources.displayMetrics.heightPixels
            wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            wv.layout(0, 0, w, h)
            var started = false
            fun finish() {
                if (started) return
                started = true
                // 아래로 조금씩 내리며 늦게 뜨는 그림까지 불러옴
                var k = 0
                val steps = 14
                val scroller = object : Runnable {
                    override fun run() {
                        if (scroll && k <= steps) {
                            wv.evaluateJavascript("window.scrollTo(0,(document.body?document.body.scrollHeight:0)*$k/$steps)", null)
                            k++
                            main.postDelayed(this, 700)
                            return
                        }
                        wv.evaluateJavascript(js) { res ->
                            result = try { JSONTokener(res).nextValue() as? String ?: "" } catch (e: Exception) { "" }
                            title = wv.title ?: ""
                            wv.stopLoading()
                            wv.destroy()
                            done.countDown()
                        }
                    }
                }
                main.postDelayed(scroller, 2500)
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) = finish()
            }
            wv.loadUrl(url)
            // 페이지가 끝까지 안 떠도 40초 뒤에는 있는 만큼 모음
            main.postDelayed({ finish() }, 40_000)
        }
        done.await(120, TimeUnit.SECONDS)
        return result to title
    }

    private const val COLLECT_JS = "(function(){var out={};" +
        "function add(u,w){if(!u||u.indexOf('data:')==0||u.indexOf('blob:')==0)return;try{u=new URL(u,location.href).href}catch(e){return}" +
        "if(!(u in out)||out[u]<w)out[u]=w;}" +
        "document.querySelectorAll('img').forEach(function(e){var w=e.naturalWidth||-1;add(e.currentSrc||e.src,w);" +
        "['data-src','data-original','data-lazy-src','data-url','data-full','data-zoom-image'].forEach(function(a){var v=e.getAttribute(a);if(v)add(v,-1)});" +
        "var ss=e.getAttribute('srcset')||e.getAttribute('data-srcset');if(ss){var best=null,bw=0;ss.split(',').forEach(function(p){var t=p.trim().split(/\\s+/);" +
        "var n=parseInt(t[1])||1;if(n>=bw){bw=n;best=t[0]}});add(best,-1)}});" +
        "document.querySelectorAll('a[href]').forEach(function(a){if(/\\.(jpe?g|png|webp|gif|avif)(\\?|$)/i.test(a.href))add(a.href,-1)});" +
        "return JSON.stringify(out);})()"

    // ---------------- 목록에 새 글 ----------------

    /** 페이지 안 링크들 (주소 → 글자) */
    const val LINKS_JS = "(function(){var o=[];document.querySelectorAll('a[href]').forEach(function(a){" +
        "var h=a.href;if(!/^https?:/.test(h))return;h=h.split('#')[0];o.push([h,(a.innerText||'').trim().slice(0,80)]);});" +
        "return JSON.stringify(o);})()"

    fun parseLinks(res: String?): List<String> = try {
        val a = JSONArray(res ?: "[]")
        List(a.length()) { a.getJSONArray(it).getString(0) }.distinct()
    } catch (e: Exception) { emptyList() }

    /** 링크 모양: 같은 사이트·같은 경로에 번호만 다른 것끼리 같은 모양 (글 목록의 글 링크를 가려내려고) */
    fun shape(u: String): String {
        val p = Uri.parse(u)
        val path = (p.path ?: "").replace(Regex("\\d+"), "#")
        val q = p.queryParameterNames.sorted().joinToString("&") { k ->
            val v = p.getQueryParameter(k) ?: ""
            k + "=" + (if (v.any { it.isDigit() }) v.replace(Regex("\\d+"), "#") else v)
        }
        return (p.host ?: "") + path + if (q.isNotEmpty()) "?$q" else ""
    }

    /** 글 링크로 보이는 모양들: 번호가 들어 있고 5개 이상 반복되는 모양 */
    fun postShapes(listUrl: String, links: List<String>): List<String> {
        val host = Uri.parse(listUrl).host
        val count = links.filter { Uri.parse(it).host == host }.groupingBy { shape(it) }.eachCount()
        val good = count.filter { (k, n) -> n >= 5 && k.contains('#') }.keys
        return good.sortedByDescending { count[it] }.take(3)
    }

    fun seenFile(ctx: Context, id: Long) = File(ctx.filesDir, "sched/$id.links").apply { parentFile?.mkdirs() }

    /** 등록할 때: 지금 보이는 글들은 '이미 본 것'으로 */
    fun baseline(ctx: Context, id: Long, links: List<String>) {
        runCatching { seenFile(ctx, id).writeText(JSONArray(links).toString()) }
    }

    /** 목록을 열어 새 글을 찾고, 새 글마다 (새 탭처럼) 열어 그림을 다 받고 닫음 */
    private fun runPosts(ctx: Context, it: Item): Pair<Int, String> {
        val (res, _) = loadAndRun(ctx, it.url, LINKS_JS, scroll = false)
        val links = parseLinks(res)
        if (links.isEmpty()) return 0 to "목록을 못 열었어요"
        val seen = LinkedHashSet<String>()
        try { JSONArray(seenFile(ctx, it.id).readText()).let { a -> for (i in 0 until a.length()) seen.add(a.getString(i)) } } catch (e: Exception) {}
        val shapes = it.patterns.toSet()
        val fresh = links.filter { l -> l !in seen && (shapes.isEmpty() || shape(l) in shapes) }.take(20)
        if (fresh.isEmpty()) return 0 to "새 글 없음"
        var pics = 0
        var posts = 0
        // 오래된 글(목록 아래쪽)부터
        for (post in fresh.reversed()) {
            val one = Item(it.id, post, it.name, 0, minPx = it.minPx)
            val (n, _) = saveImagesOf(ctx, one, folderOverride = "")
            seen.add(post)
            posts++
            pics += n.coerceAtLeast(0)
            runCatching { seenFile(ctx, it.id).writeText(JSONArray(seen.toList().takeLast(5000)).toString()) }
        }
        return pics to "새 글 ${posts}개 · 그림 ${pics}장 저장"
    }

    /**
     * 글 본문 그림만: 아래쪽 '다른 글' 미리보기·추천 글 썸네일은 빼기.
     * - 다른 페이지로 가는 링크 안에 든 그림은 뺌 (다른 글 썸네일은 그 글로 가는 링크)
     * - 화면 폭의 40%보다 좁게 그려진 그림은 뺌 (목록 썸네일·아이콘). 본문 그림은 거의 화면 폭 가득
     * - 본문 첫 그림이 든 영역(글 내용 칸) 밖에 있는 그림은 뺌
     */
    private const val POST_JS = "(function(){var out={},W=innerWidth,here=location.href.split('#')[0];" +
        "function add(u,w){if(!u||u.indexOf('data:')==0||u.indexOf('blob:')==0)return;try{u=new URL(u,location.href).href}catch(e){return}if(!(u in out)||out[u]<w)out[u]=w;}" +
        "function foreign(e){var a=e.closest('a[href]');if(!a)return false;var h=a.href.split('#')[0];" +
        "if(!/^https?:/.test(h)||h==here)return false;if(/\\.(jpe?g|png|webp|gif|avif)(\\?|$)/i.test(h))return false;return true;}" +
        "var imgs=[].slice.call(document.querySelectorAll('img')).filter(function(e){var r=e.getBoundingClientRect();return r.width>=W*0.4&&!foreign(e);});" +
        "if(!imgs.length)return '{}';" +
        // 본문 칸: 첫 큰 그림에서 위로 올라가며, 그 칸 안의 큰 그림 수가 가장 많아질 때까지 (다른 글 링크가 많이 든 칸은 넘지 않음)
        "var box=imgs[0],best=imgs[0],bestN=1;for(var p=imgs[0].parentElement;p&&p!=document.body;p=p.parentElement){" +
        "var n=imgs.filter(function(i){return p.contains(i)}).length;var links=[].slice.call(p.querySelectorAll('a[href] img')).filter(foreign).length;" +
        "if(links>=4)break;if(n>bestN){bestN=n;best=p;}}" +
        "imgs.forEach(function(e){if(best===e||best.contains(e)){var w=e.naturalWidth||-1;add(e.currentSrc||e.src,w);" +
        "['data-src','data-original','data-lazy-src','data-url'].forEach(function(a){var v=e.getAttribute(a);if(v)add(v,-1)});}});" +
        "return JSON.stringify(out);})()"

    /** 한 번 실행: 새 그림만 받아 저장. 결과 문구를 돌려줌 */
    fun runOnce(ctx: Context, it: Item): Pair<Int, String> {
        if (it.mode == 1) return runPosts(ctx, it)
        val (n, text) = saveImagesOf(ctx, it, null)
        return n.coerceAtLeast(0) to text
    }

    /** 한 페이지를 열어 그 안의 그림을 받음. folderOverride가 있으면 '예약이름/글 제목' 폴더에 */
    private fun saveImagesOf(ctx: Context, it: Item, folderOverride: String?): Pair<Int, String> {
        val perPost = folderOverride != null
        val (found, title) = collect(ctx, it.url, onlyPost = perPost)
        if (found.isEmpty()) return 0 to "그림을 못 찾음 (페이지가 안 열렸거나 그림이 없음)"
        val seenFile = File(ctx.filesDir, "sched/${it.id}.json").apply { parentFile?.mkdirs() }
        // 글마다 받을 때는 그 글 안에서만 중복을 봄 (다른 글의 같은 그림도 받음)
        val seen = LinkedHashSet<String>()
        val hashes = LinkedHashSet<String>()
        try {
            val o = JSONObject(seenFile.readText())
            o.optJSONArray("u")?.let { a -> for (i in 0 until a.length()) seen.add(a.getString(i)) }
            o.optJSONArray("h")?.let { a -> for (i in 0 until a.length()) hashes.add(a.getString(i)) }
        } catch (e: Exception) {
        }
        val ua = android.webkit.WebSettings.getDefaultUserAgent(ctx)
        var saved = 0
        var failed = 0
        val folder = if (perPost) clean(it.name) + "/" + clean(title.ifBlank { Uri.parse(it.url).lastPathSegment ?: "글" }).take(80)
            else clean(it.name.ifBlank { title }.ifBlank { Uri.parse(it.url).host ?: "예약 저장" })
        if (perPost) { seen.clear(); hashes.clear() }
        for ((u, w) in found) {
            if (u in seen) continue
            if (w in 1 until it.minPx) { seen.add(u); continue }
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Referer", it.url)
                CookieManager.getInstance().getCookie(u)?.let { ck -> c.setRequestProperty("Cookie", ck) }
                if (c.responseCode != 200) { failed++; c.disconnect(); continue }
                val type = c.contentType ?: ""
                val bytes = c.inputStream.use { s -> s.readBytes() }
                c.disconnect()
                seen.add(u)
                if (!type.startsWith("image") && !Regex("\\.(jpe?g|png|webp|gif|avif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u)) continue
                // 작은 그림(아이콘·버튼) 거르기
                val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
                if (op.outWidth > 0 && maxOf(op.outWidth, op.outHeight) < it.minPx) continue
                val h = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { b -> "%02x".format(b) }
                if (!hashes.add(h)) continue   // 주소만 다른 같은 그림
                val ext = when {
                    type.contains("png") -> "png"; type.contains("webp") -> "webp"; type.contains("gif") -> "gif"
                    type.contains("avif") -> "avif"; else -> "jpg"
                }
                val base = clean(Uri.parse(u).lastPathSegment?.substringBeforeLast('.') ?: "img").take(60)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(java.util.Date())
                if (saveImage(ctx, folder, "${stamp}_${base}.$ext", if (type.startsWith("image")) type else "image/$ext", bytes)) saved++ else failed++
            } catch (e: Exception) {
                failed++
            }
        }
        // 기억은 최근 것 위주로 (파일이 끝없이 커지지 않게)
        if (!perPost) {
            val o = JSONObject().put("u", JSONArray(seen.toList().takeLast(20000))).put("h", JSONArray(hashes.toList().takeLast(20000)))
            runCatching { seenFile.writeText(o.toString()) }
        }
        val text = (if (saved > 0) "새 그림 ${saved}장 저장" else "새 그림 없음") + (if (failed > 0) " · 실패 $failed" else "")
        return saved to text
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifBlank { "img" }

    /** 다운로드/라이트 브라우저/<폴더>/에 저장 */
    private fun saveImage(ctx: Context, folder: String, name: String, mime: String, bytes: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/라이트 브라우저/" + folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), folder).apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            true
        }
    } catch (e: Exception) { false }

    // ---------------- 알림 ----------------

    private fun channel(ctx: Context) {
        val m = ctx.getSystemService(NotificationManager::class.java)
        if (m.getNotificationChannel("sched") == null) {
            m.createNotificationChannel(NotificationChannel("sched", "예약 저장", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun working(ctx: Context, name: String): Notification {
        channel(ctx)
        return Notification.Builder(ctx, "sched")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("예약 저장 중")
            .setContentText(name)
            .setOngoing(true)
            .build()
    }

    fun doneNote(ctx: Context, it: Item, text: String) {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 900, Intent(ctx, MainActivity::class.java).putExtra("open_sched", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(NotificationManager::class.java).notify(5000 + (it.id % 1000).toInt(),
            Notification.Builder(ctx, "sched")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("예약 저장 · ${it.name}")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
    }
}

/** 예약된 때가 되면 깨어나서 한 번 실행하고, 다음 실행을 다시 예약 */
class SchedWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val id = inputData.getLong("id", -1)
        val it = Sched.list(applicationContext).firstOrNull { x -> x.id == id } ?: return Result.success()
        if (!it.enabled) return Result.success()
        try {
            val info = if (Build.VERSION.SDK_INT >= 29)
                ForegroundInfo(4100 + (id % 100).toInt(), Sched.working(applicationContext, it.name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(4100 + (id % 100).toInt(), Sched.working(applicationContext, it.name))
            setForegroundAsync(info).get()
        } catch (e: Exception) {
        }
        val (n, text) = try { Sched.runOnce(applicationContext, it) } catch (e: Exception) { 0 to "실패: ${e.message ?: e.javaClass.simpleName}" }
        Sched.update(applicationContext, id) { x ->
            x.lastRun = System.currentTimeMillis()
            x.lastResult = text
            x.total += n
        }
        if (n > 0) Sched.doneNote(applicationContext, it, text)
        Sched.list(applicationContext).firstOrNull { x -> x.id == id }?.let { x -> Sched.plan(applicationContext, x) }
        return Result.success()
    }
}
