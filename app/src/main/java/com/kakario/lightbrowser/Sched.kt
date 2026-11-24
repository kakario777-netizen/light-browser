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
        var times: List<Int> = emptyList(),         // 매일 확인할 시각들 (분 단위, everyMin == 0일 때)
        var ftp: Boolean = false,                   // FTP로도 올리기
        var bfPage: Int = 0,                        // 처음부터 전부 받기: 지금 받는 쪽 (0이면 안 함)
        var bfMax: Int = 0,
        var bfTpl: String = "",                     // 쪽 주소 틀 ({N} 자리에 쪽 번호)
        var bfText: String = "",
        var zip: Boolean = true,                    // 글마다 압축(zip) 하나로 저장·올리기
        var full: Boolean = false,                  // 예약이 아니라 '목록 전부 받기' 한 번짜리
    ) {
        fun toJson() = JSONObject().put("id", id).put("url", url).put("name", name).put("every", everyMin)
            .put("h", atHour).put("m", atMin).put("min", minPx).put("on", enabled).put("last", lastRun)
            .put("res", lastResult).put("total", total).put("next", nextAt).put("mode", mode)
            .put("pat", JSONArray(patterns)).put("times", JSONArray(times)).put("ftp", ftp)
            .put("bfp", bfPage).put("bfm", bfMax).put("bft", bfTpl).put("bfx", bfText).put("zip", zip).put("full", full)

        fun whenText(): String = when {
            everyMin == 0 -> "매일 " + dayTimes().joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }
            everyMin % 1440 == 0 -> "${everyMin / 1440}일마다"
            everyMin % 60 == 0 -> "${everyMin / 60}시간마다"
            else -> "${everyMin}분마다"
        }

        fun dayTimes(): List<Int> = times.ifEmpty { listOf(atHour * 60 + atMin) }.sorted()

        companion object {
            fun of(o: JSONObject) = Item(o.getLong("id"), o.getString("url"), o.optString("name"), o.optInt("every", 180),
                o.optInt("h", 9), o.optInt("m", 0), o.optInt("min", 300), o.optBoolean("on", true), o.optLong("last"),
                o.optString("res"), o.optInt("total"), o.optLong("next"), o.optInt("mode"),
                o.optJSONArray("pat")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                o.optJSONArray("times")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList(),
                o.optBoolean("ftp"), o.optInt("bfp"), o.optInt("bfm"), o.optString("bft"), o.optString("bfx"), o.optBoolean("zip", true), o.optBoolean("full"))
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sched", Context.MODE_PRIVATE)

    /** 테스트할 때만: 한 단계씩 무슨 일이 있었는지 기록 */
    @Volatile var report: StringBuilder? = null
    private fun rlog(s: String) {
        report?.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date()))?.append("  ")?.append(s)?.append('\n')
    }
    fun lastTest(ctx: Context): String = prefs(ctx).getString("lastTest", "") ?: ""
    fun setLastTest(ctx: Context, s: String) = prefs(ctx).edit().putString("lastTest", s).apply()

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
        WorkManager.getInstance(ctx).cancelUniqueWork("bf-$id")
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
        // 정해 둔 시각들 중 가장 가까운 다음 시각
        return it.dayTimes().map { t ->
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, t / 60); set(Calendar.MINUTE, t % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= now + 60_000) c.add(Calendar.DAY_OF_MONTH, 1)
            c.timeInMillis
        }.min()
    }

    /** 앱을 켤 때: 예약이 빠져 있으면 다시 걸어 둠 (폰을 껐다 켠 경우 등) */
    fun ensure(ctx: Context) {
        val now = System.currentTimeMillis()
        list(ctx).filter { it.enabled && !it.full }.forEach { if (it.nextAt < now - 60_000) plan(ctx, it, 10_000L) }
    }

    // ---------------- 실제 실행 ----------------

    private val main = Handler(Looper.getMainLooper())

    /** 페이지를 화면 없이 열어서 그림 주소들을 모음 (주소 → 그림 너비, 모르면 -1) */
    fun collect(ctx: Context, url: String, onlyPost: Boolean = false): Pair<Map<String, Int>, String> {
        val (res, title) = loadAndRun(ctx, url, if (onlyPost) POST_JS else COLLECT_JS, scroll = true)
        val m = LinkedHashMap<String, Int>()
        try {
            val o = JSONObject(res)
            lastReader = null
            o.keys().forEach { key ->
                when {
                    key == "__r" -> { lastReader = o.optString(key); rlog("그림 뷰어 링크: $lastReader") }
                    key.startsWith("__") -> rlog("페이지 분석: ${o.optString(key)}")
                    else -> m[key] = o.optInt(key, -1)
                }
            }
        } catch (e: Exception) {
            rlog("페이지 분석 결과를 못 읽음: ${res.take(200)}")
        }
        rlog("페이지 제목: ${title.ifBlank { "(없음)" }} · 고른 그림 주소 ${m.size}개")
        return m to title
    }

    @Volatile private var lastReader: String? = null

    /** 화면 없는 탭 하나를 열어 두고, 일하는 스레드에서 차례로 시키기 */
    class Headless(private val ctx: Context) {
        private var wv: WebView? = null

        fun open(url: String): Boolean {
            val done = CountDownLatch(1)
            var ok = false
            main.post {
                val w = WebView(ctx.applicationContext)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.offscreenPreRaster = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                val dw = ctx.resources.displayMetrics.widthPixels
                val dh = ctx.resources.displayMetrics.heightPixels
                w.measure(View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dh, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, dw, dh)
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) { ok = true; done.countDown() }
                }
                wv = w
                w.loadUrl(url)
            }
            done.await(40, TimeUnit.SECONDS)
            return ok
        }

        /** JS 결과(문자열) */
        fun eval(js: String): String {
            val done = CountDownLatch(1)
            var out = ""
            main.post {
                val w = wv ?: run { done.countDown(); return@post }
                w.evaluateJavascript(js) { r ->
                    out = try { (JSONTokener(r).nextValue() ?: "").toString() } catch (e: Exception) { r ?: "" }
                    done.countDown()
                }
            }
            done.await(10, TimeUnit.SECONDS)
            return out
        }

        fun close() = main.post { wv?.stopLoading(); wv?.destroy(); wv = null }
    }

    private const val COUNT_JS = "(function(){try{if(typeof galleryinfo!='undefined'&&galleryinfo.files)return ''+galleryinfo.files.length}catch(e){}" +
        "var s=document.querySelector('select');if(s&&s.options.length>1)return ''+s.options.length;return '0'})()"

    /** 화면에 가장 크게 보이는 그림: '주소|다 떴는지' */
    private const val MAIN_IMG_JS = "(function(){var W=innerWidth,H=innerHeight,b=null,ba=0;document.querySelectorAll('img').forEach(function(e){" +
        "var r=e.getBoundingClientRect();var w=Math.min(r.right,W)-Math.max(r.left,0),h=Math.min(r.bottom,H)-Math.max(r.top,0);" +
        "if(w>0&&h>0&&w*h>ba){ba=w*h;b=e}});if(!b)return '';return (b.currentSrc||b.src)+'|'+(b.complete?b.naturalWidth:0)})()"

    /**
     * 그림 뷰어(한 장씩 보는 페이지): 주소 끝 번호(#1, #2 …)만 바꿔 가며 한 장씩 그림 주소를 모음.
     * 화면을 누르지 않아서 화면이 꺼져 있어도 됨.
     */
    fun readerImages(ctx: Context, readerUrl: String): List<String> {
        val h = Headless(ctx)
        val out = LinkedHashSet<String>()
        try {
            val base = readerUrl.substringBefore('#')
            if (!h.open("$base#1")) { rlog("그림 뷰어를 못 열었어요: $base"); return emptyList() }
            Thread.sleep(2500)
            val count = h.eval(COUNT_JS).toIntOrNull() ?: 0
            rlog("그림 뷰어 열림: $base · 장 수 ${if (count > 0) "${count}장" else "모름 (같은 그림이 계속 나오면 멈춤)"}")
            var prev = ""
            var miss = 0
            for (n in 1..(if (count > 0) count else 3000)) {
                if (n > 1) h.eval("location.hash='#$n';'1'")
                var src = ""
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 6000) {
                    Thread.sleep(250)
                    val r = h.eval(MAIN_IMG_JS)
                    val s = r.substringBeforeLast('|')
                    val nw = r.substringAfterLast('|').toIntOrNull() ?: 0
                    // 다 뜬 '진짜' 그림만: 로딩 중 그림(작은 빈 그림)이나 이미 모은 앞 장은 기다림
                    if (s.startsWith("http") && s != prev && s !in out && nw >= 300) { src = s; break }
                }
                if (src.isEmpty()) {
                    miss++
                    rlog("${n}번째 장 그림이 안 바뀜 (${miss}번 연속)")
                    if (miss >= 3 && (count == 0 || n >= count)) break
                    if (miss >= 8) break
                    continue
                }
                miss = 0
                prev = src
                out.add(src)
                if (n % 50 == 0) rlog("그림 뷰어 ${n}장째까지 주소 모음")
            }
            rlog("그림 뷰어에서 주소 ${out.size}개 모음")
        } catch (e: Exception) {
            rlog("그림 뷰어 오류: ${e.message}")
        } finally {
            h.close()
        }
        return out.toList()
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
                override fun onPageFinished(view: WebView, u: String) { rlog("페이지 열림: $u"); finish() }
                override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                    if (req.isForMainFrame) rlog("페이지 열기 오류: ${err.errorCode} ${err.description}")
                }
                override fun onReceivedHttpError(view: WebView, req: android.webkit.WebResourceRequest, r: android.webkit.WebResourceResponse) {
                    if (req.isForMainFrame) rlog("페이지 서버 응답: ${r.statusCode} ${r.reasonPhrase}")
                }
            }
            rlog("화면 없이 열기: $url (크기 ${w}x${h})")
            wv.loadUrl(url)
            // 페이지가 끝까지 안 떠도 40초 뒤에는 있는 만큼 모음
            main.postDelayed({ if (!started) rlog("40초 동안 다 안 떠서 있는 만큼 모음"); finish() }, 40_000)
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

    /**
     * 목록 아래 쪽 번호(1 2 3 … 끝)에서 '쪽 주소 틀'과 마지막 쪽 번호를 알아냄.
     * 목록 주소와 숫자 하나(쪽 번호)만 다른 링크들을 찾음: ?page=3 또는 /page/3 같은 것
     */
    fun pageTemplate(listUrl: String, links: List<String>): Pair<String, Int>? {
        val lp = Uri.parse(listUrl)
        val byKey = HashMap<String, MutableList<Pair<String, Int>>>()
        for (l in links) {
            val p = Uri.parse(l)
            if (p.host != lp.host) continue
            if (p.path == lp.path) {
                // 쿼리에서 숫자 하나만 다름
                val keys = p.queryParameterNames + lp.queryParameterNames
                val diff = keys.filter { k -> p.getQueryParameter(k) != lp.getQueryParameter(k) }
                if (diff.size != 1) continue
                val k = diff[0]
                val v = p.getQueryParameter(k)?.toIntOrNull() ?: continue
                val tpl = lp.buildUpon().clearQuery().apply {
                    (lp.queryParameterNames + k).forEach { q -> appendQueryParameter(q, if (q == k) "{N}" else lp.getQueryParameter(q)) }
                }.build().toString().replace("%7BN%7D", "{N}")
                byKey.getOrPut("q:$k") { mutableListOf() }.add(tpl to v)
            } else {
                // 경로에서 숫자 한 칸만 다름 (/list/3, /page/3)
                val a = lp.pathSegments
                val b = p.pathSegments
                val idx: Int
                if (b.size == a.size) {
                    val d = a.indices.filter { i -> a[i] != b[i] }
                    if (d.size != 1 || b[d[0]].toIntOrNull() == null) continue
                    idx = d[0]
                } else if (b.size == a.size + 2 && b.take(a.size) == a && b.last().toIntOrNull() != null) {
                    idx = b.size - 1
                } else continue
                if (p.query != lp.query) continue
                val segs = b.toMutableList().also { s2 -> s2[idx] = "{N}" }
                val tpl = p.buildUpon().path("/" + segs.joinToString("/")).build().toString().replace("%7BN%7D", "{N}")
                byKey.getOrPut("p:$tpl") { mutableListOf() }.add(tpl to b[idx].toInt())
            }
        }
        val best = byKey.values.filter { it.size >= 2 }.maxByOrNull { it.size } ?: return null
        return best[0].first to best.maxOf { it.second }
    }

    /** 테스트: 글 하나를 지금 바로 (화면 없이) 받아 봄 */
    fun test(ctx: Context, post: String, ftp: Boolean) {
        val req = OneTimeWorkRequestBuilder<TestWorker>().setInputData(workDataOf("post" to post, "ftp" to ftp)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sched-test", ExistingWorkPolicy.REPLACE, req)
    }

    /** 처음부터 전부 받기 시작: 마지막 쪽 맨 아래 글부터 1쪽 맨 위 글까지 */
    fun startBackfill(ctx: Context, id: Long) {
        update(ctx, id) { x -> x.bfPage = -1; x.bfText = "쪽 수 알아보는 중…" }
        val req = OneTimeWorkRequestBuilder<BackfillWorker>().setInputData(workDataOf("id" to id)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("bf-$id", ExistingWorkPolicy.REPLACE, req)
    }

    fun stopBackfill(ctx: Context, id: Long) {
        WorkManager.getInstance(ctx).cancelUniqueWork("bf-$id")
        update(ctx, id) { x -> x.bfPage = 0; x.bfText = "전부 받기 멈춤" }
    }

    fun seenFile(ctx: Context, id: Long) = File(ctx.filesDir, "sched/$id.links").apply { parentFile?.mkdirs() }

    /** 등록할 때: 지금 보이는 글들은 '이미 본 것'으로 */
    fun baseline(ctx: Context, id: Long, links: List<String>) {
        runCatching { seenFile(ctx, id).writeText(JSONArray(links).toString()) }
    }

    /** 목록을 열어 새 글을 찾고, 새 글마다 (새 탭처럼) 열어 그림을 다 받고 닫음 */
    private fun runPosts(ctx: Context, it: Item, up: Ftp.Session?): Pair<Int, String> {
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
            val (n, _) = saveImagesOf(ctx, one, folderOverride = "", up = up)
            seen.add(post)
            posts++
            pics += n.coerceAtLeast(0)
            runCatching { seenFile(ctx, it.id).writeText(JSONArray(seen.toList().takeLast(5000)).toString()) }
        }
        return pics to "새 글 ${posts}개 · 그림 ${pics}장 저장"
    }

    /** 글 하나 (처음부터 전부 받기에서 씀) */
    fun savePost(ctx: Context, it: Item, post: String, up: Ftp.Session?): Int =
        saveImagesOf(ctx, Item(it.id, post, it.name, 0, minPx = it.minPx, zip = it.zip), folderOverride = "", up = up).first.coerceAtLeast(0)

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
        "var all=document.querySelectorAll('img').length,wide=[].slice.call(document.querySelectorAll('img')).filter(function(e){return e.getBoundingClientRect().width>=W*0.4}).length;" +
        "out['__d']='주소 '+location.href+' · 그림 태그 '+all+'개 · 화면 폭 40% 이상 '+wide+'개 · 다른 글 링크 아닌 큰 그림 '+imgs.length+'개 · canvas '+document.querySelectorAll('canvas').length+'개 · 화면 폭 '+W;" +
        // 그림 뷰어(리더)로 가는 링크: 큰 표지 그림이 가리키는 곳, 또는 /reader/ 링크
        "var rd=null;[].slice.call(document.querySelectorAll('img')).some(function(e){var r=e.getBoundingClientRect();var a=e.closest('a[href]');" +
        "if(r.width>=W*0.4&&a&&a.host==location.host&&a.href.split('#')[0]!=here){rd=a.href;return true}return false});" +
        "if(!rd){var ra=document.querySelector('a[href*=\"/reader/\"],a[href*=\"/viewer/\"]');if(ra)rd=ra.href;}" +
        "if(rd)out['__r']=rd;" +
        "if(!imgs.length)return JSON.stringify(out);" +
        // 본문 칸: 첫 큰 그림에서 위로 올라가며, 그 칸 안의 큰 그림 수가 가장 많아질 때까지 (다른 글 링크가 많이 든 칸은 넘지 않음)
        "var box=imgs[0],best=imgs[0],bestN=1;for(var p=imgs[0].parentElement;p&&p!=document.body;p=p.parentElement){" +
        "var n=imgs.filter(function(i){return p.contains(i)}).length;var links=[].slice.call(p.querySelectorAll('a[href] img')).filter(foreign).length;" +
        "if(links>=4)break;if(n>bestN){bestN=n;best=p;}}" +
        "imgs.forEach(function(e){if(best===e||best.contains(e)){var w=e.naturalWidth||-1;add(e.currentSrc||e.src,w);" +
        "['data-src','data-original','data-lazy-src','data-url'].forEach(function(a){var v=e.getAttribute(a);if(v)add(v,-1)});}});" +
        "out['__d']+=' · 본문 칸 안 그림 '+(Object.keys(out).length-1)+'개';" +
        "return JSON.stringify(out);})()"

    /** 한 번 실행: 새 그림만 받아 저장. 결과 문구를 돌려줌 */
    fun runOnce(ctx: Context, it: Item): Pair<Int, String> {
        val conf = Ftp.conf(ctx)
        val up = if (it.ftp && conf.ok) Ftp.Session(conf) else null
        try {
            val (n, text) = if (it.mode == 1) runPosts(ctx, it, up) else saveImagesOf(ctx, it, null, up)
            val ftpNote = when {
                it.ftp && !conf.ok -> " · FTP 서버 설정이 없어요"
                up?.error != null -> " · FTP 오류: ${up.error}"
                else -> ""
            }
            return n.coerceAtLeast(0) to text + ftpNote
        } finally {
            up?.close()
        }
    }

    /** 한 페이지를 열어 그 안의 그림을 받음. folderOverride가 있으면 '예약이름/글 제목' 폴더에 */
    private fun saveImagesOf(ctx: Context, it: Item, folderOverride: String?, up: Ftp.Session?): Pair<Int, String> {
        val perPost = folderOverride != null
        var (found, title) = if (perPost && it.url.contains("/reader/")) emptyMap<String, Int>() to "" else collect(ctx, it.url, onlyPost = perPost)
        // 글 페이지에 본문 그림이 없고 그림 뷰어로 가는 링크가 있으면 → 뷰어에서 한 장씩
        val reader = if (perPost && found.isEmpty()) (if (it.url.contains("/reader/")) it.url else lastReader) else null
        if (reader != null) {
            val list = readerImages(ctx, reader)
            found = LinkedHashMap<String, Int>().also { m -> list.forEach { u -> m[u] = -1 } }
            if (title.isBlank()) title = Uri.parse(it.url).lastPathSegment?.substringBeforeLast('.') ?: "글"
        }
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
        // 글마다 압축: 그림은 임시 폴더에 모았다가 끝나면 zip 하나로 저장·올림
        val zipMode = perPost && it.zip
        val tmp = File(ctx.cacheDir, "schedzip/${it.id}_${System.nanoTime()}").apply { if (zipMode) mkdirs() }
        var idx = 0
        for ((u, w) in found) {
            idx++
            if (u in seen) { rlog("건너뜀 (이미 받음): $u"); continue }
            if (w in 1 until it.minPx) { seen.add(u); rlog("건너뜀 (작은 그림 ${w}px): $u"); continue }
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Referer", it.url)
                CookieManager.getInstance().getCookie(u)?.let { ck -> c.setRequestProperty("Cookie", ck) }
                if (c.responseCode != 200) { rlog("받기 실패 (서버 ${c.responseCode}): $u"); failed++; c.disconnect(); continue }
                val type = c.contentType ?: ""
                val bytes = c.inputStream.use { s -> s.readBytes() }
                c.disconnect()
                seen.add(u)
                if (!type.startsWith("image") && !Regex("\\.(jpe?g|png|webp|gif|avif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
                    rlog("그림이 아님 ($type, ${bytes.size}B): $u"); continue
                }
                // 작은 그림(아이콘·버튼) 거르기
                val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
                if (op.outWidth <= 0 && bytes.size < 20_000) { rlog("건너뜀 (그림으로 안 읽히는 작은 파일 ${bytes.size}B · 로딩 그림 같음): $u"); continue }
                if (op.outWidth > 0 && maxOf(op.outWidth, op.outHeight) < it.minPx) { rlog("건너뜀 (작은 그림 ${op.outWidth}x${op.outHeight}): $u"); continue }
                val h = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { b -> "%02x".format(b) }
                if (!hashes.add(h)) { rlog("건너뜀 (같은 그림): $u"); continue }   // 주소만 다른 같은 그림
                val ext = when {
                    type.contains("png") -> "png"; type.contains("webp") -> "webp"; type.contains("gif") -> "gif"
                    type.contains("avif") -> "avif"; else -> "jpg"
                }
                val base = clean(Uri.parse(u).lastPathSegment?.substringBeforeLast('.') ?: "img").take(60)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(java.util.Date())
                // 글마다 받을 때는 순서대로 번호 (0001, 0002 …) — 만화처럼 순서가 중요
                val fname = if (perPost) "%04d.%s".format(idx, ext) else "${stamp}_${base}.$ext"
                if (zipMode) {
                    File(tmp, fname).writeBytes(bytes)
                    saved++
                    rlog("받음 ${bytes.size / 1024}KB ${op.outWidth}x${op.outHeight} → (압축할) $fname")
                    continue
                }
                if (saveImage(ctx, folder, fname, if (type.startsWith("image")) type else "image/$ext", bytes)) {
                    saved++; rlog("저장 ${bytes.size / 1024}KB ${op.outWidth}x${op.outHeight} → $folder/$fname")
                } else { failed++; rlog("저장 실패: $fname") }
                up?.upload(folder, fname, bytes)
            } catch (e: Exception) {
                failed++
                rlog("받기 오류 ${e.javaClass.simpleName}: ${e.message} · $u")
            }
        }
        if (zipMode) {
            try {
                val files = tmp.listFiles()?.sortedBy { f -> f.name } ?: emptyList()
                if (files.isNotEmpty()) {
                    val zipName = clean(title.ifBlank { Uri.parse(it.url).lastPathSegment ?: "글" }).take(120) + ".zip"
                    val zf = File(tmp.parentFile, "${tmp.name}.zip")
                    java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(zf))).use { z ->
                        z.setLevel(1)   // 그림은 이미 압축돼 있어서 빠르게
                        files.forEach { f -> z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { s -> s.copyTo(z) }; z.closeEntry() }
                    }
                    val sub = clean(it.name)
                    if (saveFile(ctx, sub, zipName, "application/zip", zf)) rlog("압축 저장 ${zf.length() / 1024}KB (${files.size}장) → $sub/$zipName")
                    else { failed++; rlog("압축 저장 실패: $zipName") }
                    if (up != null) {
                        if (up.uploadFile(sub, zipName, zf)) rlog("FTP 올림 → $sub/$zipName") else rlog("FTP 올리기 실패: ${up.error}")
                    }
                    zf.delete()
                }
            } catch (e: Exception) {
                failed++
                rlog("압축 오류: ${e.message}")
            } finally {
                tmp.deleteRecursively()
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

    /** 큰 파일(zip)을 다운로드/라이트 브라우저/<폴더>/에 저장 */
    private fun saveFile(ctx: Context, folder: String, name: String, mime: String, f: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/라이트 브라우저/" + folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), folder).apply { mkdirs() }
            f.copyTo(File(dir, name), overwrite = true)
            true
        }
    } catch (e: Exception) { false }

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

    fun doneNote(ctx: Context, it: Item, text: String, test: Boolean = false) {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, if (test) 901 else 900, Intent(ctx, MainActivity::class.java)
            .putExtra(if (test) "open_test_log" else "open_sched", true)
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

/** 화면이 꺼져도 받는 동안 폰이 잠들지 않게 */
private inline fun <T> awake(ctx: Context, tag: String, f: () -> T): T {
    val wl = (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "lightbrowser:$tag").apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
    try { return f() } finally { runCatching { wl.release() } }
}

private fun Worker.goForeground(id: Long, text: String) {
    try {
        val n = Sched.working(applicationContext, text)
        val info = if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(4100 + (id % 100).toInt(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4100 + (id % 100).toInt(), n)
        setForegroundAsync(info).get()
    } catch (e: Exception) {
    }
}

/**
 * 처음부터 전부 받기: 마지막 쪽 → 1쪽, 쪽마다 맨 아래 글 → 맨 위 글 순서로.
 * 글 하나 받을 때마다 어디까지 했는지 저장해서, 끊겨도 그 다음부터 이어 받음.
 * 오래 걸리니 30개쯤마다 끊어서 다시 이어 돌림.
 */
class BackfillWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = awake(applicationContext, "backfill") { work() }

    private fun work(): Result {
        val ctx = applicationContext
        val id = inputData.getLong("id", -1)
        var it = Sched.list(ctx).firstOrNull { x -> x.id == id } ?: return Result.success()
        goForeground(id, "${it.name} · 처음부터 전부 받기")
        if (it.bfPage <= 0 || it.bfTpl.isEmpty()) {
            // 처음: 목록에서 쪽 주소 틀과 마지막 쪽 찾기
            val (res, _) = Sched.loadAndRun(ctx, it.url, Sched.LINKS_JS, scroll = false)
            val tpl = Sched.pageTemplate(it.url, Sched.parseLinks(res))
            if (tpl == null) {
                Sched.update(ctx, id) { x -> x.bfPage = 0; x.bfText = "목록 아래 쪽 번호를 못 찾았어요" }
                return Result.success()
            }
            // 쪽 번호 줄에 끝 쪽이 다 안 보일 수 있어서, 마지막으로 보인 쪽을 열어 더 큰 번호가 있는지 몇 번 더 확인
            var max = tpl.second
            for (r in 0 until 8) {
                val (r2, _) = Sched.loadAndRun(ctx, tpl.first.replace("{N}", max.toString()), Sched.LINKS_JS, scroll = false)
                val more = Sched.pageTemplate(it.url, Sched.parseLinks(r2))?.second ?: 0
                if (more <= max) break
                max = more
            }
            Sched.update(ctx, id) { x -> x.bfTpl = tpl.first; x.bfMax = max; x.bfPage = max; x.bfText = "마지막 ${max}쪽부터 시작" }
            it = Sched.list(ctx).first { x -> x.id == id }
        }
        val conf = Ftp.conf(ctx)
        val up = if (it.ftp && conf.ok) Ftp.Session(conf) else null
        var done = 0
        try {
            val seenF = Sched.seenFile(ctx, id)
            val seen = LinkedHashSet<String>()
            try { org.json.JSONArray(seenF.readText()).let { a -> for (i in 0 until a.length()) seen.add(a.getString(i)) } } catch (e: Exception) {}
            val shapes = it.patterns.toSet()
            var page = it.bfPage
            while (page >= 1 && !isStopped) {
                val (res, _) = Sched.loadAndRun(ctx, it.bfTpl.replace("{N}", page.toString()), Sched.LINKS_JS, scroll = false)
                val posts = Sched.parseLinks(res).filter { l -> shapes.isEmpty() || Sched.shape(l) in shapes }
                // 쪽 안에서는 맨 아래(오래된) 글부터
                for (post in posts.reversed()) {
                    if (isStopped) return Result.success()
                    if (post in seen) continue
                    val n = Sched.savePost(ctx, it, post, up)
                    seen.add(post)
                    done++
                    runCatching { seenF.writeText(org.json.JSONArray(seen.toList().takeLast(50000)).toString()) }
                    Sched.update(ctx, id) { x -> x.total += n; x.bfText = "${page}쪽 받는 중 (${it.bfMax}쪽 중) · 모두 그림 ${x.total}장" }
                    goForeground(id, "${it.name} · ${page}/${it.bfMax}쪽 · 그림 ${it.total + n}장")
                    if (done >= 30) {
                        // 너무 오래 붙잡지 않게 끊고 이어서 다시
                        Sched.update(ctx, id) { x -> x.bfPage = page }
                        WorkManager.getInstance(ctx).enqueueUniqueWork("bf-$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
                            OneTimeWorkRequestBuilder<BackfillWorker>().setInputData(workDataOf("id" to id)).build())
                        return Result.success()
                    }
                }
                page--
                Sched.update(ctx, id) { x -> x.bfPage = page.coerceAtLeast(0) }
            }
            if (page < 1) {
                Sched.update(ctx, id) { x -> x.bfPage = 0; x.bfText = "처음부터 전부 받기 끝 (${it.bfMax}쪽)" }
                Sched.list(ctx).firstOrNull { x -> x.id == id }?.let { x -> Sched.doneNote(ctx, x, "처음부터 전부 받기 끝 · ${it.bfMax}쪽") }
            }
        } finally {
            up?.close()
        }
        return Result.success()
    }
}

/** 설정 › 테스트 */
class TestWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = awake(applicationContext, "test") {
        val ctx = applicationContext
        val post = inputData.getString("post") ?: return@awake Result.success()
        goForeground(1, "테스트 받는 중")
        val conf = Ftp.conf(ctx)
        val up = if (inputData.getBoolean("ftp", false) && conf.ok) Ftp.Session(conf) else null
        val started = System.currentTimeMillis()
        Sched.report = StringBuilder()
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        Sched.report?.append("테스트 시작 · 화면 ${if (pm.isInteractive) "켜짐" else "꺼짐"} · 배터리 최적화 ${if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "빠짐" else "켜짐"}\n")
        val n = try { Sched.savePost(ctx, Sched.Item(1, post, "테스트", 0), post, up) } catch (e: Exception) {
            Sched.report?.append("오류: ${e.javaClass.simpleName} ${e.message}\n"); 0
        }
        up?.close()
        Sched.report?.append("끝 · 화면 ${if (pm.isInteractive) "켜짐" else "꺼짐"} · 저장 ${n}장" + (up?.error?.let { " · FTP 오류: $it" } ?: "") + "\n")
        Sched.setLastTest(ctx, Sched.report.toString())
        Sched.report = null
        val secs = (System.currentTimeMillis() - started) / 1000
        val text = (if (n > 0) "그림 ${n}장 저장 (${secs}초)" else "그림을 못 받았어요 (${secs}초)") +
            (up?.error?.let { " · FTP 오류: $it" } ?: if (up != null) " · FTP에도 올림" else "")
        Sched.doneNote(ctx, Sched.Item(1, post, "테스트", 0), text, test = true)
        Result.success()
    }
}

/** 예약된 때가 되면 깨어나서 한 번 실행하고, 다음 실행을 다시 예약 */
class SchedWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = awake(applicationContext, "sched") { work() }

    private fun work(): Result {
        val id = inputData.getLong("id", -1)
        val it = Sched.list(applicationContext).firstOrNull { x -> x.id == id } ?: return Result.success()
        if (!it.enabled) return Result.success()
        goForeground(id, it.name)
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
