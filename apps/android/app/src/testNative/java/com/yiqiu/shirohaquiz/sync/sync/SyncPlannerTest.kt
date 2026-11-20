package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import com.yiqiu.shirohaquiz.state.QuizBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 增量判定：只传/只取真正变过的库。 */
class SyncPlannerTest {
    private fun bank(name: String, text: String, group: String = "药理") = QuizBank(
        id = "bank_$name",
        name = name,
        groupName = group,
        questions = listOf(Question(id = "q1", type = QuestionType.SINGLE, question = text))
    )

    private fun record(bank: QuizBank, updatedAt: Long) =
        SyncBankRecord(bank.groupName, bank.name, SyncIdentity.contentHash(bank), updatedAt)

    @Test
    fun onlyLocalBankIsUploaded() {
        val local = bank("第一章", "甲")
        val plan = SyncPlanner.plan(listOf(local), emptyMap(), emptyMap())
        assertEquals(1, plan.uploads.size)
        assertEquals(SyncActionKind.UPLOAD, plan.entries.single().kind)
    }

    @Test
    fun onlyRemoteBankIsDownloaded() {
        val remote = bank("第一章", "甲")
        val plan = SyncPlanner.plan(emptyList(), mapOf(SyncIdentity.keyOf(remote) to record(remote, 10L)), emptyMap())
        assertEquals(1, plan.downloads.size)
    }

    @Test
    fun identicalContentProducesNoWork() {
        val local = bank("第一章", "甲")
        val hash = SyncIdentity.contentHash(local)
        val plan = SyncPlanner.plan(
            listOf(local),
            mapOf(SyncIdentity.keyOf(local) to SyncBankRecord("药理", "第一章", hash, 10L)),
            mapOf(SyncIdentity.keyOf(local) to SyncedBankMeta(hash, 10L))
        )
        assertTrue("内容一致时不该有任何传输", plan.isEmpty)
    }

    @Test
    fun onlyChangedSideIsTransferred() {
        val local = bank("第一章", "甲")
        val remoteVersion = bank("第一章", "甲（远端改）")
        val key = SyncIdentity.keyOf(local)
        val baseHash = SyncIdentity.contentHash(local)

        // 本地没变、远端变了 → 下载
        val downloadPlan = SyncPlanner.plan(
            listOf(local), mapOf(key to record(remoteVersion, 20L)), mapOf(key to SyncedBankMeta(baseHash, 10L))
        )
        assertEquals(SyncActionKind.DOWNLOAD, downloadPlan.entries.single().kind)

        // 本地变了、远端没变 → 上传
        val localChanged = bank("第一章", "甲（本地改）")
        val uploadPlan = SyncPlanner.plan(
            listOf(localChanged), mapOf(key to record(local, 10L)), mapOf(key to SyncedBankMeta(baseHash, 10L))
        )
        assertEquals(SyncActionKind.UPLOAD, uploadPlan.entries.single().kind)
    }

    @Test
    fun bothSidesChangedIsConflict() {
        val local = bank("第一章", "甲（本地改）")
        val remote = bank("第一章", "甲（远端改）")
        val key = SyncIdentity.keyOf(local)
        val plan = SyncPlanner.plan(
            listOf(local),
            mapOf(key to record(remote, 20L)),
            mapOf(key to SyncedBankMeta(SyncIdentity.contentHash(bank("第一章", "原始")), 10L))
        )
        assertEquals(SyncActionKind.CONFLICT, plan.entries.single().kind)
    }

    @Test
    fun firstSyncWithDifferentContentIsTreatedAsConflict() {
        val local = bank("第一章", "甲")
        val remote = bank("第一章", "乙")
        val key = SyncIdentity.keyOf(local)
        val plan = SyncPlanner.plan(listOf(local), mapOf(key to record(remote, 20L)), emptyMap())
        assertEquals(SyncActionKind.CONFLICT, plan.entries.single().kind)
    }

    @Test
    fun conflictDecisionKeepsLoserAsNamedCopy() {
        val now = 1_700_000_000_000L
        val key = "药理|第一章"
        val remoteWins = SyncConflict.decide(key, remoteUpdatedAt = 20L, localConfirmedAt = 10L, now = now)
        assertTrue(remoteWins.remoteWins)
        assertTrue(remoteWins.copyName.startsWith("第一章（冲突副本 "))

        val localWins = SyncConflict.decide(key, remoteUpdatedAt = 5L, localConfirmedAt = 10L, now = now)
        assertEquals(false, localWins.remoteWins)
        // 副本名与原名不同 → 后续同步按新身份键对待，两个版本都保住。
        assertEquals("第一章（冲突副本 ".length, localWins.copyName.indexOf("（冲突副本 ") + "（冲突副本 ".length)
    }
}
