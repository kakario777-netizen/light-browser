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
 * 화면이 꺼지거나 홈으로 나가도 작업(넘기며 모으기·이미지 받기)이 멈추지 않도록
 * 알림창에 진행 상황을 띄우고 CPU·와이파이를 깨워 두는 서비스.
 */
object BgWork {
    private const val CH = "work"
    private const val CH_DONE = "done"
    private const val ID = 41
    private const val DONE_ID = 42
    const val ACT_STOP = "stop_collect"
    const val ACT_CANCEL = "cancel"

    var onStop: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    @Volatile var running = false
        private set

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channels(ctx: Context) {
        val m = nm(ctx)
        if (m.getNotificationChannel(CH) == null) {
            m.createNotificationChannel(NotificationChannel(CH, "진행 중인 작업", NotificationManager.IMPORTANCE_LOW))
        }
        if (m.getNotificationChannel(CH_DONE) == null) {
            m.createNotificationChannel(NotificationChannel(CH_DONE, "작업 완료", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(ctx: Context, act: String, code: Int) = PendingIntent.getService(
        ctx, code, Intent(ctx, BgService::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun build(ctx: Context, text: String, collecting: Boolean, progress: Int = -1, max: Int = 0): Notification {
        channels(ctx)
        val b = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("라이트 브라우저")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
        if (max > 0) b.setProgress(max, progress, false) else b.setProgress(0, 0, true)
        if (collecting) {
            b.addAction(Notification.Action.Builder(null, "멈추고 받기", action(ctx, ACT_STOP, 1)).build())
        }
        b.addAction(Notification.Action.Builder(null, "취소", action(ctx, ACT_CANCEL, 2)).build())
        return b.build()
    }

    fun start(ctx: Context, text: String, collecting: Boolean) {
        nm(ctx).cancel(DONE_ID)
        running = true
        lastCollecting = collecting
        val i = Intent(ctx, BgService::class.java).putExtra("text", text).putExtra("collecting", collecting)
        try {
            ctx.startForegroundService(i)
        } catch (e: Exception) {
            running = false
        }
    }

    private var lastCollecting = false

    @SuppressLint("MissingPermission")
    fun update(ctx: Context, text: String, collecting: Boolean = lastCollecting, progress: Int = -1, max: Int = 0) {
        if (!running) return
        lastCollecting = collecting
        try {
            nm(ctx).notify(ID, build(ctx, text, collecting, progress, max))
        } catch (e: Exception) {
        }
    }

    fun stop(ctx: Context) {
        if (!running) return
        running = false
        onStop = null
        onCancel = null
        ctx.stopService(Intent(ctx, BgService::class.java))
        nm(ctx).cancel(ID)
    }

    /** 작업이 끝났다는 알림 (눌러서 앱으로) */
    @SuppressLint("MissingPermission")
    fun done(ctx: Context, text: String) {
        stop(ctx)
        channels(ctx)
        try {
            nm(ctx).notify(
                DONE_ID, Notification.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle("라이트 브라우저")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(ctx))
                    .build()
            )
        } catch (e: Exception) {
        }
    }

    fun clearDone(ctx: Context) = nm(ctx).cancel(DONE_ID)
}

class BgService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BgWork.ACT_STOP -> {
                BgWork.onStop?.invoke() ?: stopIfIdle()
                return START_NOT_STICKY
            }
            BgWork.ACT_CANCEL -> {
                BgWork.onCancel?.invoke() ?: stopIfIdle()
                return START_NOT_STICKY
            }
        }
        val n = BgWork.build(this, intent?.getStringExtra("text") ?: "작업 중", intent?.getBooleanExtra("collecting", false) == true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(41, n)
        acquire()
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (!BgWork.running) stopSelf()
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
