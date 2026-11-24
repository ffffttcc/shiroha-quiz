package com.yiqiu.shirohaquiz.state.settings

import android.content.Context

/** 用户偏好设置仓储：练习/考试/外观/同步等开关。 */
interface SettingsRepository {
    // 练习设置
    var rememberPracticeSettingsEnabled: Boolean
    var swipeNavigationEnabled: Boolean
    var practiceAutoSubmitEnabled: Boolean
    var practiceAutoNextEnabled: Boolean
    val preferredPracticeMode: String
    var preferredPracticeMode: String
    val preferredPracticeBatchSizeMode: String
    var preferredPracticeBatchSizeMode: String

    // 考试设置
    var examAutoSubmitEnabled: Boolean
    var examShowAnswerAfterSubmit: Boolean

    // 外观
    var darkThemeEnabled: Boolean
    var fontScale: Float
    var shirohaModeEnabled: Boolean

    // 同步/备份（只读，写走 SyncStateStore/WebDavSettingsStore）
    fun isSyncEnabled(): Boolean
    fun getSyncIntervalMinutes(): Int
    fun isSyncWifiOnly(): Boolean

    // 导入/导出
    var lastImportFormat: String

    // 通用持久化
    fun putString(key: String, value: String)
    fun getString(key: String, defaultValue: String): String
    fun putBoolean(key: String, value: Boolean)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putInt(key: String, value: Int)
    fun getInt(key: String, defaultValue: Int): Int
    fun putLong(key: String, value: Long)
    fun getLong(key: String, defaultValue: Long): Long
}
