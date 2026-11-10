package com.kakario.lightbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 즐겨찾기·방문 기록·설정·열린 탭을 폰에 저장한다. */
class Store(ctx: Context) {
    data class Item(val title: String, val url: String, val time: Long = 0)

    private val p = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)

    private fun read(key: String): MutableList<Item>? {
        val s = p.getString(key, null) ?: return null
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                Item(o.optString("t"), o.optString("u"), o.optLong("d", 0))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun write(key: String, list: List<Item>) {
        val a = JSONArray()
        list.forEach {
            val o = JSONObject().put("t", it.title).put("u", it.url)
            if (it.time > 0) o.put("d", it.time)
            a.put(o)
        }
        p.edit().putString(key, a.toString()).apply()
    }

    // ---- 즐겨찾기(첫 화면 바로가기) ----
    fun dials(): MutableList<Item> = read("dials") ?: mutableListOf(
        Item("네이버", "https://m.naver.com"),
        Item("구글", "https://www.google.com"),
        Item("유튜브", "https://m.youtube.com"),
        Item("다음", "https://m.daum.net"),
        Item("나무위키", "https://namu.wiki"),
        Item("쿠팡", "https://m.coupang.com"),
    )

    fun saveDials(list: List<Item>) = write("dials", list)

    // ---- 방문 기록 ----
    fun history(): MutableList<Item> = read("history") ?: mutableListOf()

    fun addHistory(title: String, url: String) {
        val list = history()
        list.removeAll { it.url == url }
        list.add(0, Item(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (list.size > 1000) list.removeAt(list.size - 1)
        write("history", list)
    }

    /** 고른 기록만 지우기 (주소+시간으로 구분) */
    fun removeHistory(items: Collection<Item>) {
        val keys = items.map { it.url + "|" + it.time }.toHashSet()
        write("history", history().filter { (it.url + "|" + it.time) !in keys })
    }

    fun clearHistory() = p.edit().remove("history").apply()

    // ---- 설정 ----
    /** 0 = 네이버, 1 = 구글, 2 = 다음 */
    var engine: Int
        get() = p.getInt("engine", 0)
        set(v) = p.edit().putInt("engine", v).apply()

    var adblock: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    var bypass: Boolean
        get() = p.getBoolean("bypass", false)
        set(v) = p.edit().putBoolean("bypass", v).apply()

    /** 광고 차단에 걸려도 파일 다운로드는 허용 */
    var unblockDownloads: Boolean
        get() = p.getBoolean("unblockDl", true)
        set(v) = p.edit().putBoolean("unblockDl", v).apply()

    /** 다운로드 폴더 (null = 기본 Download 폴더) */
    var downloadTree: String?
        get() = p.getString("dlTree", null)
        set(v) = p.edit().putString("dlTree", v).apply()

    /** 웹페이지 글자 크기(%) — 폰 글꼴 설정과 상관없이 이 값으로 고정 */
    var textZoom: Int
        get() = p.getInt("textZoom", 100)
        set(v) = p.edit().putInt("textZoom", v).apply()

    /** 앱을 켤 때 새 버전 자동 확인 */
    var autoUpdate: Boolean
        get() = p.getBoolean("autoUpdate", true)
        set(v) = p.edit().putBoolean("autoUpdate", v).apply()
    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0L)
        set(v) = p.edit().putLong("lastUpdateCheck", v).apply()
    var skipBuild: Int
        get() = p.getInt("skipBuild", 0)
        set(v) = p.edit().putInt("skipBuild", v).apply()

    /** 받기가 다 끝났을 때(실패 없음): 0 = 물어보기, 1 = 자동 압축 후 이미지 삭제, 2 = 그냥 두기 */
    var autoZip: Int
        get() = p.getInt("autoZip", 0)
        set(v) = p.edit().putInt("autoZip", v).apply()

    /** 만화 글자 인식: 0 = 일반(ML Kit), 1 = 만화 전용(manga-ocr) */
    var ocrEngine: Int
        get() = p.getInt("ocrEngine", 0)
        set(v) = p.edit().putInt("ocrEngine", v).apply()

    /** 만화 번역 모델: 0 = 일반(가벼움), 1 = 고급(무거움) */
    var transModel: Int
        get() = p.getInt("transModel", 0)
        set(v) = p.edit().putInt("transModel", v).apply()

    var askedOverlay: Boolean
        get() = p.getBoolean("askedOverlay", false)
        set(v) = p.edit().putBoolean("askedOverlay", v).apply()

    var askedBattery: Boolean
        get() = p.getBoolean("askedBattery", false)
        set(v) = p.edit().putBoolean("askedBattery", v).apply()

    var darkPages: Boolean
        get() = p.getBoolean("darkPages", true)
        set(v) = p.edit().putBoolean("darkPages", v).apply()

    // ---- 열린 탭 (앱을 다시 켜도 그대로) ----
    /** (주소, 탭 고유 번호) 목록과 지금 탭 위치 */
    fun saveSession(tabs: List<Pair<String, Long>>, current: Int) {
        val a = JSONArray()
        tabs.forEach { (u, id) -> a.put(JSONObject().put("u", u).put("id", id)) }
        p.edit().putString("session", a.toString()).putInt("sessionCur", current).apply()
    }

    fun loadSession(): Pair<List<Pair<String, Long>>, Int> {
        val s = p.getString("session", null) ?: return Pair(emptyList(), 0)
        return try {
            val a = JSONArray(s)
            val list = List(a.length()) { i ->
                // 예전 형식(주소만)도 읽음
                val o = a.optJSONObject(i)
                if (o != null) Pair(o.optString("u"), o.optLong("id", 0L)) else Pair(a.getString(i), 0L)
            }
            Pair(list, p.getInt("sessionCur", 0))
        } catch (e: Exception) {
            Pair(emptyList(), 0)
        }
    }

    fun searchUrl(q: String): String {
        val e = java.net.URLEncoder.encode(q, "UTF-8")
        return when (engine) {
            1 -> "https://www.google.com/search?q=$e"
            2 -> "https://m.search.daum.net/search?q=$e"
            else -> "https://m.search.naver.com/search.naver?query=$e"
        }
    }

    companion object {
        val ENGINES = arrayOf("네이버", "구글", "다음")
    }
}
