package com.kakario.lightbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 넘기며 모으기 저장 (게임 세이브처럼).
 * 모으는 동안 자동으로, 그리고 '저장하고 그만'을 누르면 지금 상태를 파일로 남겨 두고
 * 앱을 껐다 켜도 '이어 받을까요?'로 이어서 받는다.
 */
object CollectSaves {
    private lateinit var dir: File

    fun init(ctx: Context) {
        if (!::dir.isInitialized) dir = File(ctx.filesDir, "collect_saves").apply { mkdirs() }
    }

    class Save(val o: JSONObject) {
        val id get() = o.getLong("id")
        val title get() = o.optString("title")
        val page get() = o.optString("page")
        val count get() = o.optJSONArray("ok")?.length() ?: 0
        val savedAt get() = o.optLong("savedAt")
        val failed get() = o.optJSONObject("failed")?.length() ?: 0
        val dups get() = o.optJSONArray("dups")?.length() ?: 0
        val total get() = o.optJSONArray("urls")?.length() ?: 0
    }

    fun list(): List<Save> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") }
        .mapNotNull { f -> runCatching { Save(JSONObject(f.readText())) }.getOrNull() }
        .filter { File(it.o.optString("dir")).exists() }
        .sortedByDescending { it.savedAt }

    /** 이어 받기 시작: 다시 저장할 수 있게 */
    fun revive(id: Long) = synchronized(this) { gone.remove(id) }

    fun has(id: Long) = File(dir, "$id.json").exists()

    private val gone = HashSet<Long>()

    fun delete(id: Long) {
        synchronized(this) {
            gone.add(id)
            File(dir, "$id.json").delete()
        }
    }

    /** 받기 작업 + 넘기기 상태를 저장 (메인 스레드에서 만들고, 파일 쓰기는 따로) */
    fun write(g: GrabJob, page: String?, tapX: Float, tapY: Float, auto: Boolean, method: Int,
              pages: Int, found: Collection<String>) {
        val o = JSONObject()
        o.put("id", g.id)
        o.put("title", g.title)
        o.put("pageTitle", g.pageTitle ?: "")
        o.put("zipName", g.zipName ?: "")
        o.put("dir", g.dir.absolutePath)
        o.put("referer", g.referer ?: "")
        o.put("ua", g.ua ?: "")
        o.put("page", page ?: "")
        o.put("tapX", tapX.toDouble())
        o.put("tapY", tapY.toDouble())
        o.put("auto", auto)
        o.put("method", method)
        o.put("pages", pages)
        o.put("savedAt", System.currentTimeMillis())
        o.put("urls", JSONArray(g.urls))
        val ok = JSONArray()
        g.slots.forEachIndexed { i, p -> if (p != null) ok.put(JSONObject().put("i", i).put("f", p.file.name)) }
        o.put("ok", ok)
        val failed = JSONObject()
        g.failed.forEach { i -> failed.put(i.toString(), g.failReason[i] ?: -1) }
        o.put("failed", failed)
        o.put("dups", JSONArray(g.dupSlots.toList()))
        o.put("hashes", JSONObject(g.hashes as Map<*, *>))
        o.put("dh", JSONObject().also { d -> g.dhash.forEach { (i, h) -> d.put(i.toString(), h) } })
        o.put("found", JSONArray(found.toList()))
        val text = o.toString()
        val id = g.id
        val logText = g.log.toString()
        val logFile = File(g.dir, "log.txt")
        Thread {
            synchronized(this) {
                if (id in gone) return@Thread
                val tmp = File(dir, "$id.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(dir, "$id.json"))
                // 작업 기록도 같이 남김 (앱을 껐다 켜도 '기록'에서 볼 수 있게)
                runCatching { logFile.writeText(logText) }
            }
        }.start()
    }
}
