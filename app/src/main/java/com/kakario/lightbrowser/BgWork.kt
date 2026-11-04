package com.kakario.lightbrowser

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 화면이 꺼지거나 홈·다른 앱으로 가도 작업(넘기며 모으기·이미지 받기·파일 다운로드)이 멈추지 않도록
 * 알림창에 진행 요약을 띄우고 CPU·와이파이를 깨워 두는 서비스.
 * 작업이 끝나면 작업마다 '완료' 알림을 따로 띄운다.
 */
object BgWork {
    private const val CH = "work"
    private const val CH_DONE = "done"
    const val ID = 41
    const val EXTRA_JOBS = "open_jobs"
    const val EXTRA_JOB = "job_id"
    const val EXTRA_DOWNLOADS = "open_downloads"

    /** 넘기며 모으는 작업 수와 모은 장수 (Collector가 알려줌) */
    var collectJobs = 0
    var collectImages = 0

    @Volatile var running = false
        private set

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channels(ctx: Context) {
        val m = nm(ctx)
        if (m.getNotificationChannel(CH) == null) {
            m.createNotificationChannel(NotificationChannel(CH, "진행 중인 작업", NotificationManager.IMPORTANCE_LOW))
        }
        if (m.getNotificationChannel(CH_DONE) == null) {
            m.createNotificationChannel(NotificationChannel(CH_DONE, "작업 완료", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun openApp(ctx: Context, code: Int, extra: (Intent) -> Unit) = PendingIntent.getActivity(
        ctx, code,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).also(extra),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 진행 중인 작업 요약 */
    private fun summary(ctx: Context): Triple<String, Int, Int> {
        val parts = mutableListOf<String>()
        var prog = 0
        var max = 0
        if (collectJobs > 0) parts.add("넘기며 모으는 중 ${collectJobs}개 (${collectImages}장)")
        val finding = JobStore.jobs.count { it.state == GrabJob.FINDING }
        if (finding > 0) parts.add("이미지 찾는 중 ${finding}개")
        val dl = JobStore.jobs.filter { it.state == GrabJob.DOWNLOADING || it.state == GrabJob.COLLECTING }
        if (dl.isNotEmpty()) {
            prog = dl.sumOf { it.done }
            max = dl.sumOf { it.total }
            parts.add("이미지 받는 중 ${dl.size}개 ($prog/$max)")
        }
        val files = Downloads.items.filter { it.state == Downloads.RUNNING }
        if (files.isNotEmpty()) {
            parts.add("파일 다운로드 ${files.size}개")
            if (max == 0) {
                val t = files.sumOf { it.total.coerceAtLeast(0) }
                if (t > 0) { max = 1000; prog = (files.sumOf { it.done } * 1000 / t).toInt() }
            }
        }
        ModelStore.packs.filter { it.downloading }.forEach { p ->
            parts.add("${p.title} 받는 중 ${p.percent()}%")
            if (max == 0) { max = 100; prog = p.percent() }
        }
        return Triple(parts.joinToString(" · "), prog, max)
    }

    fun build(ctx: Context): Notification {
        channels(ctx)
        val (text, prog, max) = summary(ctx)
        val b = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("작업 진행 중")
            .setContentText(text.ifEmpty { "작업 중" })
            .setStyle(Notification.BigTextStyle().bigText(text.replace(" · ", "\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, 1) { it.putExtra(EXTRA_JOBS, true) })
        if (max > 0) b.setProgress(max, prog, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun anyActive() = collectJobs > 0 || JobStore.jobs.any { it.active } ||
        Downloads.items.any { it.state == Downloads.RUNNING } || ModelStore.anyDownloading()

    /** 작업 상태가 바뀔 때마다 부름: 진행 중인 게 있으면 서비스 유지·알림 갱신, 없으면 정리 */
    @SuppressLint("MissingPermission")
    fun sync(ctx: Context) {
        if (anyActive()) {
            if (!running) {
                running = true
                try {
                    ctx.startForegroundService(Intent(ctx, BgService::class.java))
                } catch (e: Exception) {
                    running = false
                }
            } else {
                try { nm(ctx).notify(ID, build(ctx)) } catch (e: Exception) {}
            }
        } else if (running) {
            running = false
            ctx.stopService(Intent(ctx, BgService::class.java))
            nm(ctx).cancel(ID)
        }
    }

    @SuppressLint("MissingPermission")
    private fun doneNote(ctx: Context, id: Int, title: String, text: String, intent: PendingIntent) {
        channels(ctx)
        try {
            nm(ctx).notify(
                id, Notification.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(intent)
                    .build()
            )
        } catch (e: Exception) {
        }
    }

    private fun noteId(jobId: Long) = 1000 + (jobId % 100000).toInt()

    /** 이미지 받기 작업 완료 알림 (눌러서 그 작업 열기) */
    fun jobDone(ctx: Context, j: GrabJob) {
        val code = noteId(j.id)
        doneNote(
            ctx, code,
            if (j.pics.isEmpty()) "이미지 받기 실패" else "이미지 받기 완료",
            "${j.title} · ${j.pics.size}장" + (if (j.fails > 0) " (실패 ${j.fails})" else "") + " · 눌러서 확인하고 압축하세요",
            openApp(ctx, code) { it.putExtra(EXTRA_JOB, j.id) }
        )
    }

    fun clearJob(ctx: Context, jobId: Long) = nm(ctx).cancel(noteId(jobId))

    /** 넘기며 모으기가 더 넘어가지 않아 멈췄을 때 (앱을 보고 있지 않을 때만) */
    fun collectPaused(ctx: Context, title: String, count: Int) {
        val code = 300000 + (title.hashCode() and 0xffff)
        doneNote(ctx, code, "넘기며 모으기 멈춤", "$title · ${count}장에서 더 넘어가지 않아요. 눌러서 이어 넘기거나 끝내세요",
            openApp(ctx, code) { it.putExtra(EXTRA_JOBS, true) })
    }

    /** 파일 다운로드 완료 알림 */
    fun fileDone(ctx: Context, it: Downloads.Item) {
        val code = 200000 + (it.id % 100000).toInt()
        doneNote(
            ctx, code,
            if (it.state == Downloads.DONE) "다운로드 완료" else "다운로드 실패",
            it.name,
            openApp(ctx, code) { i -> i.putExtra(EXTRA_DOWNLOADS, true) }
        )
    }
}

class BgService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = BgWork.build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(BgWork.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(BgWork.ID, n)
        acquire()
        if (!BgWork.running) stopSelf()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquire() {
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lightbrowser:work").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L)
                }
        }
        if (wifi == null) {
            try {
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(mode, "lightbrowser:work").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            } catch (e: Exception) {
            }
        }
    }

    override fun onDestroy() {
        try { wake?.release() } catch (e: Exception) {}
        try { wifi?.release() } catch (e: Exception) {}
        wake = null
        wifi = null
        super.onDestroy()
    }
}
