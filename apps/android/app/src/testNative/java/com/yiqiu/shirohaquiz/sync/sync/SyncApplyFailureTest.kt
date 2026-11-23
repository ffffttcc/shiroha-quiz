package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.sync.webdav.WebDavClient
import com.yiqiu.shirohaquiz.sync.webdav.WebDavClientTest
import com.yiqiu.shirohaquiz.sync.webdav.WebDavCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 新设备首次同步死循环的钉子：**落盘失败必须整轮失败并说明原因**。
 *
 * 事故：`applyRemoteBank` 的返回值被忽略、还照样 `downloaded++`，
 * 下一轮再看本地依然没有这个库 → 又下一次同样的对象 → 每轮重复拉同一批（真机上 41 个对象循环）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncApplyFailureTest {

    private val dav = WebDavClientTest.FakeDav()
    private val keys = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = null)
    private val client = WebDavClient(
        "https://dav.example.com/dav/",
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("user", "secret"),
        callFactory = dav,
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )

    private fun engine(apply: (String, String, List<com.yiqiu.shirohaquiz.importer.model.Question>) -> Boolean) =
        buildSyncEngine(
            client = client,
            keys = keys,
            state = SyncStateStore(RuntimeEnvironment.getApplication()),
            declaredQuestionCount = { 0 },
            recordStore = null,
            localBanks = { emptyList() },
            applyRemoteBank = { g, n, q, _ -> apply(g, n, q) },
            renameLocalBank = { _, _ -> }
        )

    private fun seedOneBank(): String {
        val questions = listOf(
            com.yiqiu.shirohaquiz.importer.model.Question(
                type = com.yiqiu.shirohaquiz.importer.model.QuestionType.SINGLE,
                question = "远端题目"
            )
        )
        val payload = SyncBankCodec.encode("组", "远端库", questions)
        val hash = SyncIdentity.contentHash(
            com.yiqiu.shirohaquiz.state.QuizBank(id = "x", name = "远端库", questions = questions)
        )
        client.putSyncObject(SyncObjects.bankName(hash), keys.encrypt(payload, SyncPayloadCipher.aadForContent(hash)))
        val manifest = SyncManifest(
            deviceId = "dev-remote",
            updatedAt = 1L,
            banks = mapOf("组|远端库" to SyncBankRecord("组", "远端库", hash, 1L)),
            epoch = 2L
        )
        client.putSyncObject(SyncObjects.MANIFEST, keys.encryptManifest(manifest))
        return hash
    }

    /** 落盘失败 → 本轮 ok=false，消息点名库名，且不做上传/推进。 */
    @Test
    fun applyFailureFailsWholeRoundWithReason() {
        seedOneBank()
        val report = engine { _, _, _ -> false }.run()
        assertFalse("落盘失败不能算成功", report.ok)
        assertTrue("应点名库名：${report.message}", report.message.contains("远端库"))
        assertFalse("不应报告下载成功", report.message.contains("成功"))
    }

    /** 同一轮里失败即停，不会把同一对象重复下载多次。 */
    @Test
    fun applyFailureDoesNotReDownloadInSameRound() {
        seedOneBank()
        engine { _, _, _ -> false }.run()
        // manifest 对象名是 shiroha-sync-manifest.enc，不是 manifest.json
        val gets = dav.requests.filter { it.method == "GET" }.map { it.url.encodedPath.substringAfterLast('/') }
        val bankGets = gets.count { !it.startsWith(SyncObjects.MANIFEST) }
        println("[DIAG] all GETs=$gets bankGets=$bankGets")
        assertEquals("同一轮只应下载一次", 1, bankGets)
    }

    /** 落盘成功则正常推进。 */
    @Test
    fun applySuccessAdvances() {
        seedOneBank()
        val report = engine { _, _, _ -> true }.run()
        println("[DIAG] ok=${report.ok} message=${report.message}")
        assertTrue("落盘成功应正常完成：${report.message}", report.ok)
    }
}
