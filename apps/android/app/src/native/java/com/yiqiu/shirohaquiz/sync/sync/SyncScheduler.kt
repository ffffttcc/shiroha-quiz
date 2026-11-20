package com.yiqiu.shirohaquiz.sync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.sync.webdav.WebDavBackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 后台同步调度。
 *
 * 有意**不在 MainActivity/冷启动里调 schedule**：WorkManager 自己持久化周期任务，
 * 重启不需要重排；只有用户开启或改设置时才 enqueue（见设置页）。
 */
object SyncScheduler {
    const val UNIQUE_WORK = "shiroha_sync_engine"

    fun schedule(context: Context, store: SyncStateStore = SyncStateStore(context)) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val minutes = store.intervalMinutes()
        if (!store.isEnabled() || minutes == 0) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (store.wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/**
 * 周期同步 Worker。先做本地闸门再发网络：
 * 未开启 / 仅手动 → 直接 success；仅 Wi-Fi 但当前是流量 → retry 等下次。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = SyncStateStore(applicationContext)
        if (!store.isEnabled() || store.intervalMinutes() == 0) return Result.success()
        if (store.wifiOnly() && !isUnmetered()) return Result.retry()
        val manager = withContext(Dispatchers.Main.immediate) {
            QuizRepository.init(applicationContext)
            WebDavBackupManager.getInstance(applicationContext)
        }
        return try {
            val report = manager.syncNow()
            if (report.ok) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun isUnmetered(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
