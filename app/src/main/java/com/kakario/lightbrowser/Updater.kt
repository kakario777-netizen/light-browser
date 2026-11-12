package com.kakario.lightbrowser

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ProgressBar
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 자동 업데이트.
 * GitHub 릴리스(latest)의 빌드 번호를 이 앱의 빌드 번호와 비교해서, 새 빌드가 있으면 변경 내용을 보여주고
 * 받아서 설치 화면을 띄운다. (설치 확인 한 번은 안드로이드 규칙상 직접 눌러야 함)
 * 앱을 켤 때 6시간에 한 번 확인, 설정 › 업데이트 확인으로 바로 확인.
 */
class Updater(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    companion object {
        const val API = "https://api.github.com/repos/kakario777-netizen/light-browser/releases/tags/latest"
        const val PAGE = "https://github.com/kakario777-netizen/light-browser/releases/tag/latest"
    }

    /** 이 앱의 빌드 번호 */
    fun myBuild(): Int = try {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    /** 앱을 켤 때: 자동 확인이 켜져 있고 6시간 지났으면 조용히 확인 (새 버전 있을 때만 알림) */
    fun autoCheck() {
        val st = a.store
        if (!st.autoUpdate) return
        if (System.currentTimeMillis() - st.lastUpdateCheck < 6 * 3600_000L) return
        check(silent = true)
    }

    private class Release(val build: Int, val notes: String, val apkUrl: String, val size: Long)

    /** silent면 새 버전이 없거나 확인 실패해도 아무 말 안 함 */
    fun check(silent: Boolean) {
        if (busy) return
        busy = true
        if (!silent) a.toastMsg("업데이트 확인 중…")
        Thread {
            var rel: Release? = null
            var err: String? = null
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val code = c.responseCode
                if (code == 404) throw Exception("업데이트 정보를 볼 수 없어요 (저장소가 비공개이거나 아직 빌드가 없어요)")
                if (code != 200) throw Exception("서버 응답 $code")
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                val build = Regex("#(\\d+)").find(o.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val assets = o.optJSONArray("assets")
                var url = ""
                var size = 0L
                if (assets != null) for (i in 0 until assets.length()) {
                    val as1 = assets.getJSONObject(i)
                    if (as1.optString("name").endsWith(".apk")) {
                        url = as1.optString("browser_download_url")
                        size = as1.optLong("size")
                    }
                }
                rel = Release(build, o.optString("body"), url, size)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            main.post {
                busy = false
                a.store.lastUpdateCheck = System.currentTimeMillis()
                val r = rel
                when {
                    r == null -> if (!silent) AlertDialog.Builder(a).setTitle("업데이트 확인 실패")
                        .setMessage(err ?: "").setPositiveButton("확인", null).show()
                    r.build <= myBuild() || r.apkUrl.isEmpty() -> if (!silent) a.toastMsg("최신 버전이에요 (빌드 ${myBuild()})")
                    r.build == a.store.skipBuild && silent -> {}
                    silent -> quietDownload(r)
                    else -> offer(r)
                }
            }
        }.start()
    }

    /** 새 버전 안내: 변경 내용 + 받기 / 이번 건 넘기기 */
    private fun offer(r: Release) {
        val notes = r.notes.substringAfter("변경 내용:", r.notes).trim().take(800)
        AlertDialog.Builder(a)
            .setTitle("새 버전이 있어요 (빌드 ${myBuild()} → ${r.build})")
            .setMessage((if (notes.isNotBlank()) "바뀐 점\n$notes\n\n" else "") + "받아서 설치할까요? (${Downloads.size(r.size)})")
            .setPositiveButton("업데이트") { _, _ -> download(r) }
            .setNeutralButton("이번 버전 건너뛰기") { _, _ -> a.store.skipBuild = r.build }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun download(r: Release) {
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(a.dp(24), a.dp(12), a.dp(24), a.dp(4))
        }
        val dlg = AlertDialog.Builder(a)
            .setTitle("업데이트 받는 중")
            .setView(bar)
            .setCancelable(false)
            .setNegativeButton("취소", null)
            .show()
        var canceled = false
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { canceled = true; dlg.dismiss() }
        val dir = File(a.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "light-browser-${r.build}.apk")
        Thread {
            var ok = false
            var err: String? = null
            try {
                dir.listFiles()?.forEach { if (it != f) it.delete() }
                val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                if (c.responseCode != 200) throw Exception("서버 응답 ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                var done = 0L
                var last = 0L
                c.inputStream.use { ins ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (!canceled) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 200 && total > 0) {
                                last = now
                                val p = (done * 100 / total).toInt()
                                main.post { bar.progress = p }
                            }
                        }
                    }
                }
                c.disconnect()
                ok = !canceled && f.length() > 0
            } catch (e: Exception) {
                err = e.message
            }
            main.post {
                if (dlg.isShowing) dlg.dismiss()
                when {
                    canceled -> f.delete()
                    ok -> install(f)
                    else -> AlertDialog.Builder(a).setTitle("업데이트를 받지 못했어요").setMessage(err ?: "")
                        .setPositiveButton("확인", null).show()
                }
            }
        }.start()
    }

    private var pendingApk: File? = null

    /** 앱을 나갈 때 설치할 준비된 업데이트 */
    var readyApk: File? = null
        private set

    /** 자동 확인: 몰래 받아 두고, 다 받으면 짧게 물어봄 (지금 / 앱 나갈 때) */
    private fun quietDownload(r: Release) {
        val dir = File(a.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "light-browser-${r.build}.apk")
        Thread {
            val ok = try {
                dir.listFiles()?.forEach { if (it != f) it.delete() }
                if (!(f.exists() && r.size > 0 && f.length() == r.size)) {
                    val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000
                    c.readTimeout = 60000
                    if (c.responseCode != 200) throw Exception()
                    c.inputStream.use { ins -> FileOutputStream(f).use { ins.copyTo(it, 128 * 1024) } }
                    c.disconnect()
                }
                f.length() > 0
            } catch (e: Exception) { false }
            main.post {
                if (!ok || a.isFinishing) return@post
                readyApk = f
                val notes = r.notes.substringAfter("변경 내용:", r.notes).trim().lines().firstOrNull()?.take(120) ?: ""
                AlertDialog.Builder(a)
                    .setTitle("새 버전 준비됨 (빌드 ${r.build})")
                    .setMessage((if (notes.isNotBlank()) "$notes\n\n" else "") + "지금 업데이트하면 앱이 잠깐 껐다 켜져요.\n'나중에'를 누르면 앱을 나갈 때 알아서 업데이트해요.")
                    .setPositiveButton("지금 업데이트") { _, _ -> install(f, relaunch = true) }
                    .setNegativeButton("나중에", null)
                    .show()
            }
        }.start()
    }

    /** 앱을 나갈 때: 받아 둔 업데이트가 있고 하는 작업이 없으면 조용히 설치 */
    fun installOnLeave(busy: Boolean) {
        val f = readyApk ?: return
        if (busy || !f.exists() || !a.packageManager.canRequestPackageInstalls()) return
        readyApk = null
        install(f, relaunch = false)
    }

    /** 설치 화면 띄우기 (처음 한 번은 '이 출처 허용'이 필요) */
    fun install(f: File, relaunch: Boolean = true) {
        a.store.relaunchAfterUpdate = relaunch
        if (!a.packageManager.canRequestPackageInstalls()) {
            pendingApk = f
            AlertDialog.Builder(a)
                .setTitle("설치 허용이 필요해요")
                .setMessage("앱이 스스로 업데이트를 설치하려면 '출처를 알 수 없는 앱 설치'를 한 번 허용해야 해요.\n허용한 뒤 돌아오면 바로 설치 화면이 떠요.")
                .setPositiveButton("허용하러 가기") { _, _ ->
                    try {
                        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                    } catch (e: Exception) {
                        a.toastMsg("설정 화면을 열 수 없어요")
                    }
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        pendingApk = null
        // 앱 안에서 바로 업데이트 (안드로이드 12+: 이 앱이 설치한 뒤부터는 확인 없이도 됨)
        try {
            val pi = a.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(a.packageName)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            params.setSize(f.length())
            if (android.os.Build.VERSION.SDK_INT >= 34) params.setRequestUpdateOwnership(true)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out, 256 * 1024) }
                    s.fsync(out)
                }
                val cb = android.app.PendingIntent.getBroadcast(a, id,
                    Intent(a, InstallResultReceiver::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
                s.commit(cb.intentSender)
            }
            if (relaunch) a.toastMsg("업데이트 설치 중… 잠시 뒤 앱이 다시 열려요")
            return
        } catch (e: Exception) {
            // 안 되면 예전 방식(설치 화면)으로
        }
        try {
            val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
            a.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AlertDialog.Builder(a).setTitle("설치 화면을 열지 못했어요")
                .setMessage("릴리스 페이지에서 직접 받아 주세요.\n${e.message ?: ""}")
                .setPositiveButton("릴리스 페이지 열기") { _, _ -> a.openUrl(PAGE) }
                .setNegativeButton("닫기", null).show()
        }
    }

    /** 설치 허용하고 돌아왔을 때 이어서 설치 */
    fun onResume() {
        val f = pendingApk ?: return
        if (a.packageManager.canRequestPackageInstalls() && f.exists()) install(f)
    }
}
