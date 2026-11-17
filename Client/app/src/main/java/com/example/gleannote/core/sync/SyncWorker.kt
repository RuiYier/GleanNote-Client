package com.example.gleannote.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.gleannote.GleanNoteApp
import com.example.gleannote.core.contract.AppResult
import java.util.concurrent.TimeUnit

/*
 * 后台增量同步：网络恢复后按序拉取变更并重放待同步队列。
 * WorkManager 周期下限 15 分钟，前台交互仍以页面内触发为准（主文档 6.7 / 6.12）。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? GleanNoteApp ?: return Result.success()
        return when (app.container.repository.sync()) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> Result.retry()
        }
    }
}

object SyncScheduler {
    private const val WORK_NAME = "gleannote-periodic-sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
