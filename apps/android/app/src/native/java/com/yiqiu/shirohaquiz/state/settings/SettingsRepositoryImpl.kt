package com.yiqiu.shirohaquiz.state.settings

import android.content.Context
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.sync.sync.SyncStateStore
import com.yiqiu.shirohaquiz.sync.webdav.WebDavSettingsStore

class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences(QuizRepository.PREFS_NAME, Context.MODE_PRIVATE)
    private val syncStore = SyncStateStore(context)
    private val webdavStore = WebDavSettingsStore(context)

    // 练习设置
    override var rememberPracticeSettingsEnabled: Boolean
        get() = QuizRepository.rememberPracticeSettingsEnabled
        set(value) { QuizRepository.rememberPracticeSettingsEnabled = value }
    override var swipeNavigationEnabled: Boolean
        get() = QuizRepository.swipeNavigationEnabled
        set(value) { QuizRepository.swipeNavigationEnabled = value }
    override var practiceAutoSubmitEnabled: Boolean
        get() = QuizRepository.practiceAutoSubmitEnabled
        set(value) { QuizRepository.practiceAutoSubmitEnabled = value }
    override var practiceAutoNextEnabled: Boolean
        get() = QuizRepository.practiceAutoNextEnabled
        set(value) { QuizRepository.practiceAutoNextEnabled = value }
    override val preferredPracticeMode: String get() = QuizRepository.preferredPracticeMode
    override var preferredPracticeMode: String
        get() = QuizRepository.preferredPracticeMode
        set(value) { QuizRepository.preferredPracticeMode = value }
    override val preferredPracticeBatchSizeMode: String get() = QuizRepository.preferredPracticeBatchSizeMode
    override var preferredPracticeBatchSizeMode: String
        get() = QuizRepository.preferredPracticeBatchSizeMode
        set(value) { QuizRepository.preferredPracticeBatchSizeMode = value }

    // 考试设置
    override var examAutoSubmitEnabled: Boolean
        get() = prefs.getBoolean("exam_auto_submit", true)
        set(value) { prefs.edit().putBoolean("exam_auto_submit", value).apply() }
    override var examShowAnswerAfterSubmit: Boolean
        get() = prefs.getBoolean("exam_show_answer", false)
        set(value) { prefs.edit().putBoolean("exam_show_answer", value).apply() }

    // 外观
    override var darkThemeEnabled: Boolean
        get() = QuizRepository.darkThemeEnabled
        set(value) { QuizRepository.darkThemeEnabled = value }
    override var fontScale: Float
        get() = prefs.getFloat("font_scale", 1.0f)
        set(value) { prefs.edit().putFloat("font_scale", value).apply() }
    override var shirohaModeEnabled: Boolean
        get() = QuizRepository.shirohaModeEnabled
        set(value) { QuizRepository.shirohaModeEnabled = value }

    // 同步/备份（只读）
    override fun isSyncEnabled(): Boolean = syncStore.isEnabled()
    override fun getSyncIntervalMinutes(): Int = syncStore.intervalMinutes()
    override fun isSyncWifiOnly(): Boolean = syncStore.wifiOnly()

    // 导入/导出
    override var lastImportFormat: String
        get() = prefs.getString("last_import_format", "") ?: ""
        set(value) { prefs.edit().putString("last_import_format", value).apply() }

    // 通用持久化
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getString(key: String, defaultValue: String): String = prefs.getString(key, defaultValue) ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun getLong(key: String, defaultValue: Long): Long = prefs.getLong(key, defaultValue)
}
