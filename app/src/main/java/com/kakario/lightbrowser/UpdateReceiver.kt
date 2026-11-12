package com.kakario.lightbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** 앱 안에서 설치(PackageInstaller) 결과 받기 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 안드로이드가 '업데이트할까요?' 한 번 확인을 요구할 때
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (e: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                if (st != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(ctx, "업데이트 설치 실패: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** 업데이트가 끝나면: 사용자가 '지금 업데이트'를 눌렀을 때만 앱을 다시 열어 줌 (앱 나갈 때 한 업데이트는 조용히) */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val p = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)
        if (!p.getBoolean("relaunchAfterUpdate", true)) return
        p.edit().putBoolean("relaunchAfterUpdate", false).apply()
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(open) } catch (e: Exception) {}
        try {
            val m = ctx.getSystemService(NotificationManager::class.java)
            if (m.getNotificationChannel("update") == null) {
                m.createNotificationChannel(NotificationChannel("update", "업데이트", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            m.notify(7001, Notification.Builder(ctx, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("라이트 브라우저 업데이트 완료")
                .setContentText("눌러서 열기")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build())
        } catch (e: Exception) {}
    }
}
