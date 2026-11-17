package com.example.gleannote.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gleannote.GleanNoteApp
import com.example.gleannote.MainActivity
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.Limits
import com.example.gleannote.core.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * 转换期间的前台服务：维持 2 秒轮询，应用切后台也不中断（PRD FR-04 / FR-05）。
 * 服务写入 Room 后由 UI 的 Flow 驱动，避免 UI 与服务双重轮询触发限流。
 */
class ConversionWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val noteId = intent?.getStringExtra(EXTRA_NOTE_ID)
        if (noteId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForegroundCompat(buildNotification("正在转换…", noteId))

        watchJob?.cancel()
        watchJob = scope.launch {
            val repo = (application as GleanNoteApp).container.repository
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                when (val r = repo.fetchJob(noteId)) {
                    is AppResult.Success -> {
                        val job = r.data
                        updateNotification(phaseText(job.phase, job.processedPages, job.totalPages), noteId)
                        if (job.state in TERMINAL) {
                            repo.getNote(noteId)
                            if (job.state == "SUCCEEDED") {
                                Notifications.conversionDone(this@ConversionWatchService, noteId, "")
                            }
                            break
                        }
                    }
                    is AppResult.Failure -> if (r.error.code == "CONVERSION_NOT_FOUND") break
                }
                if (System.currentTimeMillis() - startedAt > Limits.CONVERSION_TIMEOUT_MS + 60_000) break
                delay(Limits.POLL_INTERVAL_MS)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "转换进行中", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, noteId: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse("gleannote://note/$noteId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, noteId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("拾页笔记 · 转换中")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String, noteId: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text, noteId))
    }

    private fun phaseText(phase: String, processed: Int, total: Int): String = when (phase) {
        "QUEUED" -> "排队中…"
        "RECOGNIZING" -> "正在识别第 ${processed.coerceAtLeast(1)} / $total 页"
        "GENERATING" -> "正在生成 Markdown"
        "COMPLETED" -> "已完成"
        else -> "处理中…"
    }

    companion object {
        private const val CHANNEL_ID = "conversion_progress"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_NOTE_ID = "noteId"
        private val TERMINAL = setOf("SUCCEEDED", "FAILED", "CANCELED")

        fun start(context: Context, noteId: String) {
            val intent = Intent(context, ConversionWatchService::class.java)
                .putExtra(EXTRA_NOTE_ID, noteId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
