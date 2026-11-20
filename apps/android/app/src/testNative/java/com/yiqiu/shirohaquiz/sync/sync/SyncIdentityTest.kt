package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionImage
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import com.yiqiu.shirohaquiz.state.QuizBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 身份键与内容指纹：同一内容必须算出同一串，否则增量同步永远收敛不了。 */
class SyncIdentityTest {
    private fun q(id: String, text: String) =
        Question(id = id, type = QuestionType.SINGLE, question = text, answer = listOf("A"))

    private fun bank(name: String, group: String, questions: List<Question>) =
        QuizBank(id = "bank_${System.nanoTime()}", name = name, groupName = group, questions = questions)

    @Test
    fun identityKeyIgnoresBankIdAndNormalizesGroup() {
        val first = bank("第一章 绪论", "药理", listOf(q("q1", "甲")))
        val second = first.copy(id = "bank_other_timestamp")
        assertEquals(SyncIdentity.keyOf(first), SyncIdentity.keyOf(second))
        assertEquals("药理|第一章 绪论", SyncIdentity.keyOf(first))
        assertEquals("未分组|无分组题", SyncIdentity.identityKey("   ", " 无分组题 "))
    }

    @Test
    fun contentHashIsStableAcrossQuestionOrder() {
        val forward = bank("库", "分组", listOf(q("q2", "乙"), q("q1", "甲")))
        val reversed = forward.copy(questions = forward.questions.reversed())
        assertEquals(SyncIdentity.contentHash(forward), SyncIdentity.contentHash(reversed))
    }

    @Test
    fun contentHashIgnoresDeviceLocalFields() {
        val question = q("q1", "甲").copy(
            sourceFileId = "device-a-file",
            images = listOf(QuestionImage(id = "img1", localPath = "/data/a/question_assets/x.png", order = 0))
        )
        val otherDevice = question.copy(
            sourceFileId = "device-b-file",
            images = listOf(QuestionImage(id = "img1", localPath = "/data/b/other_dir/x.png", order = 0))
        )
        assertEquals(
            SyncIdentity.contentHash(bank("库", "分组", listOf(question))),
            SyncIdentity.contentHash(bank("库", "分组", listOf(otherDevice)))
        )
    }

    @Test
    fun contentHashChangesWithRealContentEdit() {
        val before = bank("库", "分组", listOf(q("q1", "甲")))
        val after = bank("库", "分组", listOf(q("q1", "甲（改）")))
        assertNotEquals(SyncIdentity.contentHash(before), SyncIdentity.contentHash(after))
        val added = bank("库", "分组", listOf(q("q1", "甲"), q("q2", "乙")))
        assertNotEquals(SyncIdentity.contentHash(before), SyncIdentity.contentHash(added))
    }

    @Test
    fun conflictCopyNameMarksTheLoser() {
        val name = SyncIdentity.conflictCopyName("第一章 绪论", 1_700_000_000_000L)
        assertTrue(name.startsWith("第一章 绪论（冲突副本 "))
        assertTrue(name.endsWith("）"))
        assertNotEquals(
            SyncIdentity.identityKey("药理", "第一章 绪论"),
            SyncIdentity.identityKey("药理", name)
        )
    }
}
