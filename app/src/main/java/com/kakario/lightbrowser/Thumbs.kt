package com.kakario.lightbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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

    /** 화면에 보이는 웹페이지를 작은 그림으로 찍는다. aspect를 주면 위쪽만 그 비율로 자름 */
    fun capture(view: View, scale: Float, aspect: Float? = null): Bitmap? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0 || !view.isAttachedToWindow) return null
        val ch = if (aspect != null) minOf(h, (w * aspect).toInt()) else h
        return try {
            val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (ch * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            view.draw(c)
            bmp
        } catch (e: Throwable) {
            null
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
