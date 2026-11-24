package com.yiqiu.shirohaquiz.sync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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
    const val QUICK_WORK = "shiroha_sync_quick"
    const val KEY_PROGRESS_ONLY = "progressOnly"

    /**
     * 答题后的静默快推：5 秒防抖，连着答几题只推最后一次。
     * 未开启同步 / 周期为 0 时直接跳过，不产生任何网络与任务。
     */
    fun syncSoon(
        context: Context,
        progressOnly: Boolean = false,
        store: SyncStateStore = SyncStateStore(context)
    ) {
        if (!store.isEnabled() || store.intervalMinutes() == 0) return
        // 只推进度时还要过「刷题自动同步」这道独立开关：关掉就安静，不排队、不发请求。
        if (progressOnly && !store.practiceAutoSync()) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (store.wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_PROGRESS_ONLY to progressOnly))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(QUICK_WORK, ExistingWorkPolicy.REPLACE, request)
    }

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
            val progressOnly = inputData.getBoolean(SyncScheduler.KEY_PROGRESS_ONLY, false)
            val report = manager.syncNow(progressOnly = progressOnly)
            if (report.ok) return Result.success()
            // 重试有上限：连续失败达到上限就停下并留下可见错误，
            // 否则同一批对象会被反复拉取（真机表现为界面一直「正在登录」）。
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                store.recordFailure(report.message)
                return Result.success()
            }
            return Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 只补可观测性：把失败原因落进 sync 状态，重试策略保持不变。
            store.recordFailure("${error::class.java.simpleName}: ${error.message}")
            Result.retry()
        }
    }

    private companion object {
        /** 同一轮同步最多尝试次数（含首次）。到顶就停止重试，把错误留给用户看。 */
        const val MAX_ATTEMPTS = 3
    }

    private fun isUnmetered(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
