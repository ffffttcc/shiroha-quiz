package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import com.yiqiu.shirohaquiz.state.QuizBank
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
 * 「本地删掉的库被云端复活」的钉子。
 *
 * 事故（真机复现）：墓碑只在轮末计算，而计划在轮初就按**上一轮**的墓碑做，
 * 于是本轮刚删掉的库先被 DOWNLOAD 回来 —— 删除意图被自己抹掉，墓碑永远写不出去，
 * 每轮同步都把它复活一次（47 库 → 50 库 → 又回到 50 库）。
 *
 * 这里跑三轮：上传 → 本地删除 → 第三轮必须彻底安静（下载 0、上传 0），
 * 且远端清单里该 key 必须已变成墓碑、不再作为库存在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncBankDeletePropagationTest {

    private val dav = WebDavClientTest.FakeDav()
    private val keys = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = null)
    private val client = WebDavClient(
        "https://dav.example.com/dav/",
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("user", "secret"),
        callFactory = dav,
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )

    private var locals: List<QuizBank> = emptyList()

    private fun engine() = buildSyncEngine(
        client = client,
        keys = keys,
        state = SyncStateStore(RuntimeEnvironment.getApplication()),
        declaredQuestionCount = { 0 },
        recordStore = null,
        localBanks = { locals },
        applyRemoteBank = { _, _, _ -> true },
        renameLocalBank = { _, _ -> }
    )

    private fun oneBank() = QuizBank(
        id = "b1",
        name = "待删库",
        groupName = "组",
        questions = listOf(Question(type = QuestionType.SINGLE, question = "题"))
    )

    @Test
    fun deletingLocalBankDoesNotResurrectFromRemote() {
        val key = SyncIdentity.keyOf(oneBank())

        locals = listOf(oneBank())
        val first = engine().run()
        assertTrue("首轮应上传成功：${first.message}", first.ok)
        assertEquals("首轮应上传 1 个库", 1, first.uploaded)

        // 用户在本地删掉它
        locals = emptyList()
        val second = engine().run()
        assertTrue("次轮应成功：${second.message}", second.ok)
        assertEquals("本地已删的库不得被云端下回来", 0, second.downloaded)

        val manifestKey = dav.files.keys.first { it.endsWith(SyncObjects.MANIFEST) }
        val manifest = keys.openManifest(dav.files.getValue(manifestKey)).manifest
        assertTrue(
            "墓碑必须写进远端清单，实际 tombstones=${manifest.bankTombstones}",
            manifest.bankTombstones.any { it.targetKey == key && it.deleted }
        )
        assertFalse("已删的库不得继续挂在清单里", manifest.banks.containsKey(key))

        val third = engine().run()
        assertTrue("第三轮应成功：${third.message}", third.ok)
        assertEquals("第三轮不得再下载", 0, third.downloaded)
        assertEquals("第三轮不得再上传", 0, third.uploaded)
    }
}
