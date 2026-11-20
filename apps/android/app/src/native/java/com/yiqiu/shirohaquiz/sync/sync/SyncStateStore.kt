package com.yiqiu.shirohaquiz.sync.sync

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** 单库上次同步记录：拿它判断"本地是否改过"。 */
data class SyncedBankMeta(val contentHash: String, val updatedAt: Long)

/**
 * 同步状态与会话设置。
 *
 * **独立 prefs 文件 `shiroha_sync_state`**：这些键绝不能进 `contentPreferenceKeys`
 * —— `recoverPendingCloudRestore` 用 `check(root.length() == contentPreferenceKeys.size + 1)`
 * 校验历史回滚 journal，多一个键冷启动就崩。也不进备份 ZIP。
 */
class SyncStateStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) = preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** 15 / 30 / 60 分钟；0 = 仅手动。默认 30。 */
    fun intervalMinutes(): Int =
        preferences.getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
            .takeIf { it in SUPPORTED_INTERVALS } ?: DEFAULT_INTERVAL_MINUTES

    fun setIntervalMinutes(minutes: Int) =
        preferences.edit().putInt(KEY_INTERVAL_MINUTES, minutes.takeIf { it in SUPPORTED_INTERVALS } ?: DEFAULT_INTERVAL_MINUTES).apply()

    fun wifiOnly(): Boolean = preferences.getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnly(enabled: Boolean) = preferences.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()

    fun lastSyncAt(): Long = preferences.getLong(KEY_LAST_SYNC_AT, 0L)

    fun lastSyncResult(): String? = preferences.getString(KEY_LAST_SYNC_RESULT, null)

    /** 只在整个同步成功后才调用；失败不写，避免"看起来同步过"。 */
    fun markSynced(at: Long, result: String, bankMeta: Map<String, SyncedBankMeta>) {
        preferences.edit()
            .putLong(KEY_LAST_SYNC_AT, at)
            .putString(KEY_LAST_SYNC_RESULT, result)
            .putString(KEY_BANK_META, encodeMeta(bankMeta))
            .apply()
    }

    fun bankMeta(): Map<String, SyncedBankMeta> {
        val raw = preferences.getString(KEY_BANK_META, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val result = mutableMapOf<String, SyncedBankMeta>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = root.optJSONObject(key) ?: continue
                result[key] = SyncedBankMeta(
                    contentHash = item.optString("contentHash"),
                    updatedAt = item.optLong("updatedAt", 0L)
                )
            }
            result
        }.getOrDefault(emptyMap())
    }

    fun clearForTesting() {
        preferences.edit().clear().commit()
    }

    private fun encodeMeta(meta: Map<String, SyncedBankMeta>): String {
        val root = JSONObject()
        meta.toSortedMap().forEach { (key, value) ->
            root.put(key, JSONObject().apply {
                put("contentHash", value.contentHash)
                put("updatedAt", value.updatedAt)
            })
        }
        return root.toString()
    }

    companion object {
        const val PREFS_NAME = "shiroha_sync_state"
        const val KEY_DEVICE_ID = "sync_device_id"
        const val KEY_ENABLED = "sync_enabled"
        const val KEY_INTERVAL_MINUTES = "sync_interval_minutes"
        const val KEY_WIFI_ONLY = "sync_wifi_only"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SYNC_RESULT = "last_sync_result"
        const val KEY_BANK_META = "bank_meta_json"
        const val DEFAULT_INTERVAL_MINUTES = 30
        val SUPPORTED_INTERVALS = setOf(0, 15, 30, 60)
    }
}
