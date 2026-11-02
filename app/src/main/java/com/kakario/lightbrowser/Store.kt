package com.kakario.lightbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 즐겨찾기·방문 기록·설정·열린 탭을 폰에 저장한다. */
class Store(ctx: Context) {
    data class Item(val title: String, val url: String)

    private val p = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)

    private fun read(key: String): MutableList<Item>? {
        val s = p.getString(key, null) ?: return null
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                Item(o.optString("t"), o.optString("u"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun write(key: String, list: List<Item>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
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
        list.add(0, Item(title.ifBlank { url }, url))
        while (list.size > 300) list.removeAt(list.size - 1)
        write("history", list)
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

    var darkPages: Boolean
        get() = p.getBoolean("darkPages", true)
        set(v) = p.edit().putBoolean("darkPages", v).apply()

    // ---- 열린 탭 (앱을 다시 켜도 그대로) ----
    fun saveSession(urls: List<String>, current: Int) {
        val a = JSONArray()
        urls.forEach { a.put(it) }
        p.edit().putString("session", a.toString()).putInt("sessionCur", current).apply()
    }

    fun loadSession(): Pair<List<String>, Int> {
        val s = p.getString("session", null) ?: return Pair(emptyList(), 0)
        return try {
            val a = JSONArray(s)
            Pair(List(a.length()) { a.getString(it) }, p.getInt("sessionCur", 0))
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
