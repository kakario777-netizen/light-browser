package com.kakario.lightbrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder

/** 앱 자체 다운로드: 진행률·속도 표시, 폴더 지정, blob/data 파일 저장 */
object Downloads {
    const val RUNNING = 0
    const val DONE = 1
    const val FAILED = 2
    const val CANCELED = 3

    class Item(
        val id: Long,
        @Volatile var name: String,
        val url: String,
        @Volatile var mime: String?,
        @Volatile var total: Long = -1,
        @Volatile var done: Long = 0,
        @Volatile var state: Int = RUNNING,
        @Volatile var uri: String? = null,
        @Volatile var speed: Long = 0,
        val time: Long = System.currentTimeMillis(),
        @Volatile var cancel: Boolean = false,
        @Volatile var error: String? = null,
    )

    val items = mutableListOf<Item>()   // 메인 스레드에서만 다룸
    var onChange: (() -> Unit)? = null
    var onFinish: ((Item) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var loaded = false
    private var nextId = System.currentTimeMillis()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(prefs(ctx).getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(
                    Item(
                        o.optLong("id"), o.optString("n"), o.optString("url"), o.optString("m").ifEmpty { null },
                        o.optLong("s", -1), o.optLong("d"), o.optInt("st", DONE).let { if (it == RUNNING) FAILED else it },
                        o.optString("u").ifEmpty { null }, 0, o.optLong("t"),
                    )
                )
            }
        } catch (e: Exception) {
        }
    }

    fun save(ctx: Context) {
        val a = JSONArray()
        items.filter { it.state != RUNNING }.take(300).forEach {
            a.put(
                JSONObject().put("id", it.id).put("n", it.name).put("url", it.url).put("m", it.mime ?: "")
                    .put("s", it.total).put("d", it.done).put("st", it.state).put("u", it.uri ?: "").put("t", it.time)
            )
        }
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    private fun changed() = main.post { onChange?.invoke() }

    private fun finished(ctx: Context, it: Item) = main.post {
        save(ctx)
        onChange?.invoke()
        onFinish?.invoke(it)
    }

    // ---------------- 파일 이름 ----------------

    fun fileName(url: String, cd: String?, mime: String?): String {
        var name: String? = null
        if (cd != null) {
            Regex("filename\\*\\s*=\\s*([^']*)''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
                name = try {
                    URLDecoder.decode(it.groupValues[2].trim().trim('"'), it.groupValues[1].ifEmpty { "UTF-8" })
                } catch (e: Exception) {
                    null
                }
            }
            if (name == null) {
                Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
                    var n = it.groupValues[1].trim()
                    if (n.contains('%')) n = try { URLDecoder.decode(n, "UTF-8") } catch (e: Exception) { n }
                    // 한국 사이트에서 흔한 깨진 한글(UTF-8을 Latin-1로 보낸 경우) 복구
                    val fixed = String(n.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    name = if (n.any { c -> c.code in 0x80..0xFF } && !fixed.contains('\uFFFD')) fixed else n
                }
            }
        }
        var n = name ?: URLUtil.guessFileName(url, null, mime)
        n = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "download" }
        if (!n.contains('.') && mime != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        }
        return n
    }

    // ---------------- 저장 위치 ----------------

    private fun openOut(ctx: Context, name: String, mime: String?): Pair<OutputStream, Uri> {
        val type = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        Store(ctx).downloadTree?.let { tree ->
            try {
                val t = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, type, name)
                if (doc != null) return Pair(ctx.contentResolver.openOutputStream(doc)!!, doc)
            } catch (e: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            return Pair(ctx.contentResolver.openOutputStream(uri)!!, uri)
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        var f = File(dir, name)
        var k = 1
        while (f.exists()) f = File(dir, name.substringBeforeLast('.') + " ($k)." + name.substringAfterLast('.', "bin")).also { k++ }
        return Pair(FileOutputStream(f), Uri.fromFile(f))
    }

    private fun publish(ctx: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
            try {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
            }
        }
    }

    fun deleteFile(ctx: Context, it: Item) {
        val u = it.uri ?: return
        try {
            val uri = Uri.parse(u)
            if (uri.scheme == "file") File(uri.path ?: "").delete()
            else if (DocumentsContract.isDocumentUri(ctx, uri)) DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            else ctx.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
        }
    }

    // ---------------- 받기 ----------------

    private fun newItem(url: String, mime: String?): Item {
        val it = Item(nextId++, "준비 중…", url, mime)
        items.add(0, it)
        return it
    }

    /** http/https 파일 받기 (메인 스레드에서 호출) */
    fun start(ctx: Context, url: String, ua: String?, cd: String?, mime: String?, referer: String?): Item {
        val app = ctx.applicationContext
        val item = newItem(url, mime)
        item.name = fileName(url, cd, mime)
        changed()
        Thread {
            var conn: HttpURLConnection? = null
            var out: OutputStream? = null
            var uri: Uri? = null
            try {
                val port = Bypass.port()
                var u = URL(url)
                var hops = 0
                while (true) {
                    conn = (if (port != null) u.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                    else u.openConnection()) as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20000
                    conn.readTimeout = 30000
                    if (ua != null) conn.setRequestProperty("User-Agent", ua)
                    if (referer != null) conn.setRequestProperty("Referer", referer)
                    CookieManager.getInstance().getCookie(u.toString())?.let { conn.setRequestProperty("Cookie", it) }
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 10) {
                        val loc = conn.getHeaderField("Location") ?: break
                        u = URL(u, loc)
                        conn.disconnect()
                        hops++
                        continue
                    }
                    if (code >= 400) throw Exception("서버 오류 $code")
                    break
                }
                val c = conn!!
                val realMime = c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" } ?: mime
                c.getHeaderField("Content-Disposition")?.let { item.name = fileName(u.toString(), it, realMime) }
                if (cd == null && c.getHeaderField("Content-Disposition") == null) item.name = fileName(u.toString(), null, realMime)
                item.mime = realMime
                item.total = c.contentLengthLong
                val (o, ur) = openOut(app, item.name, realMime)
                out = o
                uri = ur
                item.uri = ur.toString()
                changed()
                val ins = c.inputStream
                val buf = ByteArray(64 * 1024)
                var last = System.currentTimeMillis()
                var lastDone = 0L
                while (!item.cancel) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    item.done += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 500) {
                        item.speed = (item.done - lastDone) * 1000 / (now - last)
                        last = now
                        lastDone = item.done
                        changed()
                    }
                }
                o.close()
                ins.close()
                if (item.cancel) {
                    deleteFile(app, item)
                    item.state = CANCELED
                } else {
                    publish(app, ur)
                    if (item.total <= 0) item.total = item.done
                    item.state = DONE
                }
            } catch (e: Exception) {
                try { out?.close() } catch (x: Exception) {}
                if (uri != null) deleteFile(app, item)
                item.error = e.message
                item.state = if (item.cancel) CANCELED else FAILED
            } finally {
                conn?.disconnect()
            }
            item.speed = 0
            finished(app, item)
        }.start()
        return item
    }

    /** data: 주소(또는 blob을 data로 바꾼 것) 저장 */
    fun startData(ctx: Context, dataUrl: String, name: String?, mimeHint: String?): Item {
        val app = ctx.applicationContext
        val head = dataUrl.substringBefore(',', "")
        val mime = head.removePrefix("data:").substringBefore(';').ifEmpty { mimeHint ?: "application/octet-stream" }
        val item = newItem("data:", mime)
        item.name = fileName("http://x/" + (name?.takeIf { it.isNotBlank() } ?: "download"), null, mime)
        changed()
        Thread {
            try {
                val body = dataUrl.substringAfter(',', "")
                val bytes = if (head.contains(";base64")) Base64.decode(body, Base64.DEFAULT)
                else URLDecoder.decode(body, "UTF-8").toByteArray()
                item.total = bytes.size.toLong()
                val (o, ur) = openOut(app, item.name, mime)
                item.uri = ur.toString()
                o.use { it.write(bytes) }
                publish(app, ur)
                item.done = item.total
                item.state = DONE
            } catch (e: Exception) {
                item.error = e.message
                item.state = FAILED
            }
            finished(app, item)
        }.start()
        return item
    }

    fun size(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> String.format("%.1fKB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format("%.1fMB", b / 1048576.0)
        else -> String.format("%.2fGB", b / 1073741824.0)
    }
}
