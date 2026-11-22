package com.yiqiu.shirohaquiz.ui.screens

import android.content.Context
import com.yiqiu.shirohaquiz.sync.webdav.WebDavBackupEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云端备份页的展示层逻辑（纯函数，便于单测）。
 * 原则：界面只说人话——技术异常（InvalidTag、恢复码不匹配等）一律翻译成可行动的说法。
 */
object CloudSyncUiModel {
    const val DEFAULT_BASE_URL = "https://dev.ftc27.xyz/"
    const val DEFAULT_DIRECTORY = "/ShirohaQuiz/Backups/"
    const val MIN_CUSTOM_MINUTES = 5
    const val MAX_CUSTOM_MINUTES = 1440

    /** 登录失败的翻译：把后端异常文本收敛成人话，且绝不回显技术黑话。 */
    fun signInError(rawMessage: String?, httpCode: Int? = null): String {
        val text = rawMessage.orEmpty()
        val lowered = text.lowercase()
        return when {
            httpCode == 401 || lowered.contains("401") || lowered.contains("unauthorized") ||
                lowered.contains("认证") || lowered.contains("密码") -> "账号或密码不对，请再试一次。"
            lowered.contains("invalidtag") || lowered.contains("解密") || lowered.contains("恢复码") ->
                "这些云端数据是用别的账号加密的，换回原来的账号就能打开。"
            lowered.contains("timeout") || lowered.contains("timed out") || lowered.contains("连不上") ||
                lowered.contains("unknownhost") || lowered.contains("unable to resolve") ->
                "连不上服务器，检查一下网络再试。"
            lowered.contains("404") || lowered.contains("不存在") -> "服务器上找不到这个账号的备份目录。"
            else -> "登录没成功，请稍后再试。"
        }
    }

    /** 同步/恢复失败的翻译：同样不露技术词。 */
    fun friendlyFailure(rawMessage: String?): String {
        val lowered = rawMessage.orEmpty().lowercase()
        return when {
            lowered.contains("invalidtag") || lowered.contains("解密") || lowered.contains("恢复码") ->
                "这批云端数据打不开（密钥不一致）。换回创建它的那个账号即可。"
            lowered.contains("401") || lowered.contains("unauthorized") -> "账号或密码不对，请重新填写。"
            else -> "这次没同步成功，稍后再试一次。"
        }
    }

    /** 来源设备/来源描述：完整备份包读 meta.json 落进 entry 的 source + appVersion。 */
    fun sourceLabel(entry: WebDavBackupEntry): String = when {
        entry.source.equals("android", ignoreCase = true) -> "Android 设备"
        entry.source.equals("web", ignoreCase = true) -> "网页端"
        entry.source.isBlank() || entry.source.equals("unknown", ignoreCase = true) -> "来源未知"
        else -> entry.source
    }

    fun backupTitle(entry: WebDavBackupEntry): String =
        entry.createdAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it)) } ?: "时间未知"

    /** 卡片第二行：大小 · 来源 · 版本 · 题库数/题数。缺的字段直接省略，不写占位噪音。 */
    fun backupSummary(entry: WebDavBackupEntry): String {
        val parts = mutableListOf<String>()
        parts += "%.1f MB".format(entry.sizeBytes / 1048576.0)
        parts += sourceLabel(entry)
        if (entry.appVersion.isNotBlank() && !entry.appVersion.equals("unknown", ignoreCase = true)) {
            parts += "v${entry.appVersion.removePrefix("v")}"
        }
        val banks = entry.bankCount
        val questions = entry.questionCount
        when {
            banks != null && questions != null -> parts += "$banks 个题库 · $questions 题"
            banks != null -> parts += "$banks 个题库"
            questions != null -> parts += "$questions 题"
        }
        return parts.joinToString(" · ")
    }

    /** 自定义间隔校验：只接受 5..1440 的整数分钟，其余返回 null（界面据此提示）。 */
    fun parseCustomMinutes(text: String): Int? {
        val value = text.trim().toIntOrNull() ?: return null
        return if (value in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES) value else null
    }

    fun intervalLabel(minutes: Int): String = when (minutes) {
        0 -> "仅手动"
        in 60..Int.MAX_VALUE -> if (minutes % 60 == 0) "${minutes / 60} 小时" else "$minutes 分钟"
        else -> "$minutes 分钟"
    }
}

/** 「同步应用设置」开关：默认关闭，落盘到独立 prefs（不含任何密钥）。 */
object AppSettingsSyncPrefs {
    private const val PREFS = "shiroha_cloud_ui"
    private const val KEY_SYNC_APP_SETTINGS = "sync_app_settings"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SYNC_APP_SETTINGS, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SYNC_APP_SETTINGS, enabled).apply()
    }
}
