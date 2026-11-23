package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.sync.records.RecordEventStore
import com.yiqiu.shirohaquiz.sync.records.RecordKey
import com.yiqiu.shirohaquiz.sync.webdav.WebDavClient
import com.yiqiu.shirohaquiz.sync.webdav.WebDavClientTest
import com.yiqiu.shirohaquiz.sync.webdav.WebDavCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 记录同步接线的**防回归护栏**。
 *
 * 背景：SyncEngine 的四个记录参数带默认值，生产构造点漏传过一次，症状是
 * 「同步报成功、记录片 +0、云端永远没有分片」。所以这里不只验正向，还必须验反向：
 * **不注入 store 时 recordUploaded 必须为 0**——否则这条测试对「忘接线」毫无感知。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordSyncWiringTest {

    private val recoveryCode = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("wiring").toFile()

    private fun client(dav: WebDavClientTest.FakeDav): WebDavClient = WebDavClient(
        "https://dav.example.com/dav/",
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("user", "secret"),
        callFactory = dav,
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )

    private fun storeWithTwoEvents(dir: File): RecordEventStore {
        val store = RecordEventStore(dir, "device-A")
        val key = RecordKey.of("SINGLE", "大多数药物通过细胞膜的方式是( )", listOf("主动转运", "简单扩散"), listOf("B"))
        store.append(key, "药理|02 第二章 药物代谢动力学", correct = false, source = "练习", answeredAt = 1_795_233_781_191L)
        store.append(key, "药理|02 第二章 药物代谢动力学", correct = true, source = "练习", answeredAt = 1_795_233_782_191L)
        return store
    }

    /** 正向：注入了真实 store 且有 2 条事件 → 至少上传 1 个分片，且远端真落了记录分片对象。 */
    @Test
    fun injectedRecordStoreUploadsShard() {
        val dir = tempDir()
        val dav = WebDavClientTest.FakeDav()
        val store = storeWithTwoEvents(dir)
        val engine = buildSyncEngine(
            client = client(dav),
            keys = SyncKeys.fromLegacyRecoveryCode(recoveryCode),
            state = SyncStateStore(RuntimeEnvironment.getApplication()),
            declaredQuestionCount = { 0 },
            recordStore = store,
            localBanks = { emptyList() },
            applyRemoteBank = { _, _, _, _ -> true },
            renameLocalBank = { _, _ -> }
        )

        val report = engine.run()

        assertTrue("本轮应成功：${report.message}", report.ok)
        assertTrue("记录片上传数应 >= 1，实际 ${report.recordUploaded}", report.recordUploaded >= 1)
        val shardKeys = dav.files.keys.filter { it.contains("shiroha-sync-record-") }
        assertTrue("远端应落下记录分片对象，实际 keys=${dav.files.keys}", shardKeys.isNotEmpty())
        assertTrue("分片对象应有内容", dav.files.getValue(shardKeys.first()).isNotEmpty())
    }

    /** 反向：不注入 store（走默认空事件）→ 记录片上传数必须为 0，云端不得出现记录分片。 */
    @Test
    fun missingRecordStoreYieldsZeroRecordUploads() {
        val dav = WebDavClientTest.FakeDav()
        val engine = buildSyncEngine(
            client = client(dav),
            keys = SyncKeys.fromLegacyRecoveryCode(recoveryCode),
            state = SyncStateStore(RuntimeEnvironment.getApplication()),
            declaredQuestionCount = { 0 },
            recordStore = null,
            localBanks = { emptyList() },
            applyRemoteBank = { _, _, _, _ -> true },
            renameLocalBank = { _, _ -> }
        )

        val report = engine.run()

        assertEquals("未注入 store 时不得上传记录分片", 0, report.recordUploaded)
        assertTrue(
            "未注入 store 时云端不应出现记录分片，实际 keys=${dav.files.keys}",
            dav.files.keys.none { it.contains("shiroha-sync-record-") }
        )
    }
}
