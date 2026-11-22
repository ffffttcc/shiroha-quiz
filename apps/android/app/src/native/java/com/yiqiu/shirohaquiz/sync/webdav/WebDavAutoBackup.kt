package com.yiqiu.shirohaquiz.sync.webdav

import android.util.Log

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.yiqiu.shirohaquiz.state.QuizRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class WebDavAutoBackupSettings(
    val intervalDays: Int = 0,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val keepCount: Int = BackupRotation.DEFAULT_KEEP,
    val deleteOldBackups: Boolean = true
)

object WebDavAutoBackup {
    private const val UNIQUE_WORK = "shiroha_webdav_auto_backup"
    private fun preferences(context: Context) = context.getSharedPreferences("shiroha_webdav_auto", Context.MODE_PRIVATE)

    fun load(context: Context): WebDavAutoBackupSettings {
        val prefs = preferences(context)
        return WebDavAutoBackupSettings(prefs.getInt("days", 0).takeIf { it in listOf(0, 1, 7) } ?: 0,
            prefs.getBoolean("wifi", true), prefs.getBoolean("charging", false),
            run {
                // 旧版本存过 20：折算成 10 并写回，避免每轮重复转换。
                val raw = prefs.getInt("keep", BackupRotation.DEFAULT_KEEP)
                val keep = BackupRotation.normalizeKeep(raw)
                if (keep != raw) preferences(context).edit().putInt("keep", keep).apply()
                keep
            }, prefs.getBoolean("delete", true))
    }

    fun save(context: Context, settings: WebDavAutoBackupSettings) {
        require(settings.intervalDays in listOf(0, 1, 7) && settings.keepCount in BackupRotation.KEEP_OPTIONS)
        if (settings.intervalDays > 0) {
            val manager = WebDavBackupManager.getInstance(context)
            require(manager.settings.value.rememberPassword && manager.hasPassword()) { "自动备份需要先加密保存 WebDAV 应用密码。" }
            manager.settings.value.validated()
        }
        check(preferences(context).edit().putInt("days", settings.intervalDays).putBoolean("wifi", settings.wifiOnly)
            .putBoolean("charging", settings.chargingOnly).putInt("keep", settings.keepCount)
            .putBoolean("delete", settings.deleteOldBackups).commit()) { "自动备份设置保存失败。" }
        schedule(context, settings)
    }

    fun schedule(context: Context, settings: WebDavAutoBackupSettings = load(context)) {
        val workManager = WorkManager.getInstance(context)
        if (settings.intervalDays == 0) { workManager.cancelUniqueWork(UNIQUE_WORK); return }
        val request = PeriodicWorkRequestBuilder<WebDavAutoBackupWorker>(settings.intervalDays.toLong(), TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly).setRequiresStorageNotLow(true).build())
            .setInitialDelay(settings.intervalDays.toLong(), TimeUnit.DAYS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class WebDavAutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = WebDavAutoBackup.load(applicationContext)
        if (settings.intervalDays == 0) return Result.success()
        if (settings.wifiOnly && !isWifi()) return Result.retry()
        val manager = withContext(Dispatchers.Main.immediate) {
            QuizRepository.init(applicationContext)
            WebDavBackupManager.getInstance(applicationContext)
        }
        try {
            if (!manager.settings.value.rememberPassword || !manager.hasPassword()) return Result.failure()
            val result = manager.uploadBackupAndAwait() ?: return Result.retry()
            if (settings.deleteOldBackups) {
                if (!awaitTask(manager, manager::refreshBackups)) return Result.success()
                // 清理只碰完整备份包；同步对象与最新一份由 planCleanup 排除。
                val stale = BackupRotation.planCleanup(
                    manager.backups.value,
                    settings.keepCount,
                    protectedFileNames = setOf(result.entry.fileName)
                )
                for (entry in stale) {
                    // 单条失败不再中断整批：剩下的照删，失败留给下一轮。
                    awaitTask(manager) { manager.deleteBackup(entry, userConfirmedTwice = true) }
                }
            }
            return Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: WebDavException) {
            return if (runAttemptCount >= 3 || error.httpCode in listOf(401, 403, 405, 507)) Result.failure() else Result.retry()
        } catch (error: Exception) {
            // 观测性：分支走向不变，只把真因落日志与 last_error。
            Log.e("WebDavTask", "WebDavAutoBackupWorker failed", error)
            runCatching {
                com.yiqiu.shirohaquiz.sync.sync.SyncStateStore(applicationContext)
                    .recordFailure("${error::class.java.simpleName}: ${error.message}")
            }
            return if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }

    private suspend fun awaitTask(manager: WebDavBackupManager, start: () -> Boolean): Boolean {
        val taskId = withContext(Dispatchers.Main.immediate) {
            if (start()) manager.task.value?.id else null
        } ?: return false
        try {
            val terminal = manager.task.first { it?.id == taskId && it.stage !in setOf(WebDavTaskStage.RUNNING, WebDavTaskStage.CANCELLING) }
            while (manager.isBusy() && manager.task.value?.id == taskId) delay(50)
            return terminal?.stage == WebDavTaskStage.SUCCEEDED
        } catch (cancelled: CancellationException) {
            manager.cancelTask(taskId)
            throw cancelled
        }
    }

    private fun isWifi(): Boolean {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
