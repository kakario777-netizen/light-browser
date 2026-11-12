package com.kakario.lightbrowser

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 광고 차단.
 *  - 주소 차단: 광고·악성 광고 서버로 가는 요청을 빈 응답으로 막음 (빌드 때 받은 대형 목록 + 기본 목록)
 *  - 화면 숨김: 광고 칸(배너·움짤·동영상 광고 자리)을 CSS로 숨김
 */
object AdBlock {
    private val builtin = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "amazon-adsystem.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "smartadserver.com", "moatads.com", "advertising.com", "adform.net",
        "bidswitch.net", "media.net", "popads.net", "popcash.net", "propellerads.com",
        "exoclick.com", "juicyads.com", "trafficjunky.net", "adsterra.com",
        "onclickads.net", "onclickmega.com", "clickadu.com", "hilltopads.net",
        "adcash.com", "a-ads.com", "yieldmo.com", "teads.tv", "sharethrough.com",
        "mobon.net", "dable.io", "realclick.co.kr", "adop.cc", "tenping.kr",
        "cauly.co.kr", "ad.daum.net", "veta.naver.com", "mediacategory.com",
        "interworksmedia.co.kr", "adpnut.com", "tpmn.co.kr", "adinc.co.kr",
        "aceplanet.co.kr", "targetpush.co.kr", "wider.co.kr",
    )

    /** 목록에 있어도 막으면 안 되는 주소 (사이트가 깨지는 경우) */
    private val allow = hashSetOf(
        "naver.com", "naver.net", "pstatic.net", "daum.net", "kakao.com", "kakaocdn.net",
        "google.com", "gstatic.com", "googleapis.com", "youtube.com", "ytimg.com", "googlevideo.com",
        "coupang.com", "coupangcdn.com",
    )

    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile private var hideGeneric: List<String> = emptyList()
    @Volatile private var hideBySite: Map<String, List<String>> = emptyMap()
    @Volatile var loaded = false
        private set
    private val cssCache = ConcurrentHashMap<String, String>()

    /** 기본으로 숨길 광고 자리 (목록을 못 받았을 때도 동작) */
    private val baseHide = listOf(
        "ins.adsbygoogle", ".adsbygoogle", "[id^=\"div-gpt-ad\"]", "[id^=\"google_ads_iframe\"]",
        "iframe[src*=\"googlesyndication\"]", "iframe[src*=\"doubleclick\"]", "iframe[src*=\"mobon.net\"]",
        "iframe[src*=\"adop.cc\"]", "iframe[src*=\"realclick\"]", "iframe[src*=\"cauly\"]",
        "iframe[id*=\"aswift\"]", "[data-ad-slot]", "[data-ad-client]", ".dable_placeholder",
        "div[id*=\"taboola\"]", "div[id^=\"mobon\"]", "a[href*=\"mobon.net\"]", "a[href*=\"realclick\"]",
    )

    /** 앱 업데이트 없이 받은 최신 목록이 있으면 그걸, 없으면 앱에 들어 있는 목록 */
    private fun open(ctx: Context, name: String): java.io.InputStream {
        val f = java.io.File(ctx.filesDir, "adblock/$name")
        return if (f.exists() && f.length() > 0) f.inputStream() else ctx.assets.open("adblock/$name")
    }

    /**
     * 광고 차단 목록만 따로 갱신 (앱 업데이트 없이, 하루에 한 번).
     * 서버(GitHub)가 매일 새 목록을 만들어 'filters' 릴리스에 올려 둠.
     */
    fun refresh(ctx: Context, st: Store) {
        if (System.currentTimeMillis() - st.lastFilterCheck < 24 * 3600_000L) return
        st.lastFilterCheck = System.currentTimeMillis()
        val app = ctx.applicationContext
        Thread {
            try {
                val dir = java.io.File(app.filesDir, "adblock").apply { mkdirs() }
                val got = ArrayList<Pair<java.io.File, java.io.File>>()
                for (name in listOf("hosts.txt", "cosmetic.txt")) {
                    val c = java.net.URL("https://github.com/kakario777-netizen/light-browser/releases/download/filters/$name.gz")
                        .openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 60000
                    if (c.responseCode != 200) { c.disconnect(); return@Thread }
                    val tmp = java.io.File(dir, "$name.tmp")
                    java.util.zip.GZIPInputStream(c.inputStream).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                    c.disconnect()
                    if (tmp.length() < 1000) return@Thread
                    got.add(tmp to java.io.File(dir, name))
                }
                got.forEach { (t, f) -> t.renameTo(f) }
                loaded = false
                load(app)
            } catch (e: Exception) {
            }
        }.start()
    }

    fun load(ctx: Context) {
        if (loaded) return
        Thread {
            val h = HashSet<String>(builtin)
            try {
                open(ctx, "hosts.txt").bufferedReader().useLines { lines ->
                    lines.forEach { if (it.isNotBlank()) h.add(it.trim()) }
                }
            } catch (e: Exception) {
            }
            val gen = ArrayList<String>(baseHide)
            val site = HashMap<String, MutableList<String>>()
            try {
                open(ctx, "cosmetic.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab < 0) return@forEach
                        val d = line.substring(0, tab)
                        val sel = line.substring(tab + 1)
                        if (d.isEmpty()) gen.add(sel) else site.getOrPut(d) { ArrayList() }.add(sel)
                    }
                }
            } catch (e: Exception) {
            }
            hosts = h
            hideGeneric = gen
            hideBySite = site
            cssCache.clear()
            loaded = true
        }.start()
    }

    /** host 또는 그 상위 주소가 목록에 있으면 true */
    fun blocked(host: String?): Boolean {
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        if (h.isEmpty()) return false
        val set = hosts.ifEmpty { builtin }
        if (site(h) in allow) return false
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun blockedUrl(url: String?): Boolean =
        url != null && url.startsWith("http") && blocked(android.net.Uri.parse(url).host)

    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private val secondLevel = setOf(
        "co.kr", "or.kr", "go.kr", "ac.kr", "ne.kr", "re.kr", "pe.kr",
        "co.jp", "ne.jp", "or.jp", "co.uk", "org.uk", "com.cn", "com.tw", "com.au", "com.br",
    )

    /** 같은 사이트 판별용: sub.news.example.co.kr → example.co.kr */
    fun site(host: String?): String {
        val h = host?.lowercase()?.trimEnd('.') ?: return ""
        val p = h.split('.')
        if (p.size <= 2) return h
        val last2 = p.takeLast(2).joinToString(".")
        return if (last2 in secondLevel && p.size >= 3) p.takeLast(3).joinToString(".") else last2
    }

    /** 이 사이트에 넣을 광고 숨김 스크립트 */
    fun hideScript(host: String?): String {
        val h = host?.lowercase() ?: ""
        val css = cssCache.getOrPut(h) {
            val sb = StringBuilder()
            val sels = ArrayList<String>()
            var x = h
            while (x.isNotEmpty()) {
                hideBySite[x]?.let { sels.addAll(it) }
                val i = x.indexOf('.')
                if (i < 0) break
                x = x.substring(i + 1)
            }
            // 선택자 하나가 틀려도 나머지는 적용되도록 한 줄씩 따로 넣는다
            for (s in sels) sb.append(s).append("{display:none!important}\n")
            for (s in hideGeneric) sb.append(s).append("{display:none!important}\n")
            sb.toString()
        }
        return "(function(){try{if(document.getElementById('__lb_hide'))return;" +
            "var s=document.createElement('style');s.id='__lb_hide';s.textContent=" + JSONObject.quote(css) + ";" +
            "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
    }
}
