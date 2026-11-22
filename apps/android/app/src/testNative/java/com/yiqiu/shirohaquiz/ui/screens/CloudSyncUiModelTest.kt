package com.yiqiu.shirohaquiz.ui.screens

import com.yiqiu.shirohaquiz.sync.webdav.WebDavBackupEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CloudSyncUiModelTest {
    private fun entry(source: String = "android", version: String = "0.9.16.1",
                      banks: Int? = 43, questions: Int? = 5358) = WebDavBackupEntry(
        fileName = "backup.zip", createdAt = 1_795_000_000_000, sizeBytes = 1_576_530,
        source = source, appVersion = version, sha256 = "a".repeat(64),
        bankCount = banks, questionCount = questions
    )

    @Test fun signInErrorTranslatesTechnicalTermsToHumanWords() {
        assertEquals("账号或密码不对，请再试一次。", CloudSyncUiModel.signInError("401 Unauthorized"))
        assertEquals("连不上服务器，检查一下网络再试。", CloudSyncUiModel.signInError("connect timed out"))
        val invalidTag = CloudSyncUiModel.signInError("InvalidTag: decryption failed")
        assertTrue("不许把 InvalidTag 这种词抖给用户", !invalidTag.contains("InvalidTag", ignoreCase = true))
        assertTrue(!invalidTag.contains("恢复码"))
        assertTrue(!CloudSyncUiModel.friendlyFailure("InvalidTag").contains("恢复码"))
    }

    @Test fun sourceLabelAndSummaryUseMetaFields() {
        assertEquals("Android 设备", CloudSyncUiModel.sourceLabel(entry()))
        val summary = CloudSyncUiModel.backupSummary(entry())
        assertTrue(summary.contains("来源") || summary.contains("Android 设备"))
        assertTrue(summary.contains("v0.9.16.1"))
        assertTrue(summary.contains("43 个题库"))
        assertTrue(summary.contains("5358 题"))
        assertTrue(summary.contains("MB"))
    }

    @Test fun summaryOmitsMissingCountsInsteadOfPrintingPlaceholders() {
        val summary = CloudSyncUiModel.backupSummary(entry(banks = null, questions = null, version = "unknown"))
        assertTrue(!summary.contains("题库"))
        assertTrue(!summary.contains("unknown"))
    }

    @Test fun customIntervalAcceptance() {
        assertEquals(20, CloudSyncUiModel.parseCustomMinutes("20"))
        assertEquals(5, CloudSyncUiModel.parseCustomMinutes(" 5 "))
        assertEquals(1440, CloudSyncUiModel.parseCustomMinutes("1440"))
        assertNull(CloudSyncUiModel.parseCustomMinutes("4"))
        assertNull(CloudSyncUiModel.parseCustomMinutes("1441"))
        assertNull(CloudSyncUiModel.parseCustomMinutes("abc"))
        assertNull(CloudSyncUiModel.parseCustomMinutes(""))
    }

    @Test fun appSettingsSyncToggleDefaultsOffAndPersists() {
        val context = RuntimeEnvironment.getApplication()
        AppSettingsSyncPrefs.setEnabled(context, false)
        assertTrue("默认必须关闭", !AppSettingsSyncPrefs.isEnabled(context))
        AppSettingsSyncPrefs.setEnabled(context, true)
        assertTrue("开启后要落盘", AppSettingsSyncPrefs.isEnabled(context))
        AppSettingsSyncPrefs.setEnabled(context, false)
        assertTrue("关闭后也要落盘", !AppSettingsSyncPrefs.isEnabled(context))
    }

    @Test fun intervalLabelReadsNaturally() {
        assertEquals("仅手动", CloudSyncUiModel.intervalLabel(0))
        assertEquals("15 分钟", CloudSyncUiModel.intervalLabel(15))
        assertEquals("1 小时", CloudSyncUiModel.intervalLabel(60))
    }
}
