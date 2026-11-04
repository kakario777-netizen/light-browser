package com.kakario.lightbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** 즐겨찾기 미리보기(첫 화면 캡처)와 탭 미리보기 */
object Thumbs {
    /** 즐겨찾기 카드 비율 (세로/가로) */
    const val DIAL_ASPECT = 1.1f

    private val memo = HashMap<String, Bitmap?>()

    /** m.naver.com, www.naver.com, naver.com 을 같은 사이트로 본다 */
    fun key(url: String): String {
        val h = (Uri.parse(url).host ?: url).lowercase()
        return h.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
    }

    private fun file(ctx: Context, url: String): File {
        val dir = File(ctx.filesDir, "dials").apply { mkdirs() }
        val md = MessageDigest.getInstance("MD5").digest(key(url).toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    /**
     * 화면에 실제로 보이는 웹페이지 픽셀을 그대로 복사해 작은 그림으로 만든다.
     * (웹뷰를 일반 그림판에 다시 그리면 하드웨어 가속 때문에 깨지거나 비어 나와서 PixelCopy 사용)
     * aspect를 주면 위쪽만 그 비율로 자름. 결과는 메인 스레드에서 cb로.
     */
    fun capture(window: android.view.Window, view: View, scale: Float, aspect: Float?, cb: (Bitmap?) -> Unit) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0 || !view.isAttachedToWindow || !view.isShown) { cb(null); return }
        val ch = if (aspect != null) minOf(h, (w * aspect).toInt()) else h
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            val full = Bitmap.createBitmap(w, ch, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(
                window, android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + ch), full,
                { res ->
                    if (res == android.view.PixelCopy.SUCCESS) {
                        val small = try {
                            Bitmap.createScaledBitmap(full, (w * scale).toInt().coerceAtLeast(1), (ch * scale).toInt().coerceAtLeast(1), true)
                        } catch (e: Throwable) { null }
                        if (small !== full) full.recycle()
                        cb(small)
                    } else {
                        full.recycle()
                        cb(null)
                    }
                }, main
            )
        } catch (e: Throwable) {
            cb(null)
        }
    }

    fun hasFresh(ctx: Context, url: String, maxAgeMs: Long): Boolean {
        val f = file(ctx, url)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs
    }

    fun saveDial(ctx: Context, url: String, bmp: Bitmap) {
        val k = key(url)
        memo[k] = bmp
        val f = file(ctx, url)
        Thread {
            try {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            } catch (e: Exception) {
            }
        }.start()
    }

    fun dial(ctx: Context, url: String): Bitmap? {
        val k = key(url)
        if (memo.containsKey(k)) return memo[k]
        val f = file(ctx, url)
        val b = if (f.exists()) try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null } else null
        memo[k] = b
        return b
    }

    fun forget(ctx: Context, url: String) {
        memo.remove(key(url))
        file(ctx, url).delete()
    }
}
