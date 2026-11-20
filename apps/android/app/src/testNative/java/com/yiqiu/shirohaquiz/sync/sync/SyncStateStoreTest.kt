package com.yiqiu.shirohaquiz.sync.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncStateStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        SyncStateStore(context).clearForTesting()
    }

    @Test
    fun defaultsAreOfflineFriendly() {
        val store = SyncStateStore(context)
        assertFalse("默认不自动同步", store.isEnabled())
        assertEquals(30, store.intervalMinutes())
        assertTrue("默认仅 Wi-Fi", store.wifiOnly())
        assertEquals(0L, store.lastSyncAt())
        assertEquals(null, store.lastSyncResult())
    }

    @Test
    fun unsupportedIntervalFallsBackToDefault() {
        val store = SyncStateStore(context)
        store.setIntervalMinutes(7)
        assertEquals("WorkManager 周期最小 15 分钟，非法值回落默认 30", 30, store.intervalMinutes())
        store.setIntervalMinutes(15)
        assertEquals(15, store.intervalMinutes())
        store.setIntervalMinutes(0)
        assertEquals(0, store.intervalMinutes())
    }

    @Test
    fun deviceIdIsStableAndNotReusedAcrossClears() {
        val store = SyncStateStore(context)
        val first = store.deviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, SyncStateStore(context).deviceId())
        store.clearForTesting()
        assertNotEquals(first, SyncStateStore(context).deviceId())
    }

    @Test
    fun markSyncedPersistsResultAndBaseline() {
        val store = SyncStateStore(context)
        val meta = mapOf(
            "药理|第一章" to SyncedBankMeta("a".repeat(64), 1_700_000_000_000L),
            "英语|Unit 1" to SyncedBankMeta("b".repeat(64), 1_700_000_100_000L)
        )
        store.markSynced(1_700_000_200_000L, "上传 1 / 下载 2 / 冲突 0 · 成功", meta)

        val reloaded = SyncStateStore(context)
        assertEquals(1_700_000_200_000L, reloaded.lastSyncAt())
        assertEquals("上传 1 / 下载 2 / 冲突 0 · 成功", reloaded.lastSyncResult())
        assertEquals(meta, reloaded.bankMeta())
    }

    @Test
    fun stateLivesInItsOwnPreferencesFile() {
        SyncStateStore(context).setEnabled(true)
        assertEquals("shiroha_sync_state", SyncStateStore.PREFS_NAME)
        val all = context.getSharedPreferences(SyncStateStore.PREFS_NAME, Context.MODE_PRIVATE).all
        assertTrue(all.containsKey(SyncStateStore.KEY_ENABLED))
        // 内容键必须还在 QuizRepository 自己的 prefs 里，不能混进同步状态文件。
        assertFalse(all.containsKey("banks"))
        assertFalse(all.containsKey("wrong_book"))
    }
}
