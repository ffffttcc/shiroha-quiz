package com.yiqiu.shirohaquiz.state

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习会话快照：退出/换库后回来要能原样接上——队列、题号、已答结果、未提交草稿。
 * 这里钉住编解码的往返与失配时的降级（宁可作废，也不要接出一个错位的场次）。
 */
class PracticeSessionSnapshotTest {

    private fun snapshot(keys: List<String> = listOf("bank_1#q1", "bank_1#q2")) = PracticeSessionSnapshot(
        startedAt = 1_700_000_000_000L,
        practiceMode = "instant",
        sourceLabel = "药理",
        scopeType = "bank",
        scopeName = "药理学复习指南",
        index = 1,
        batchGroupSize = 10,
        batchSubmitted = false,
        batchGroupStartIndex = 0,
        shuffleSeed = 42L,
        sessionKeys = keys,
        questionsJson = """[{"id":"q1","number":1},{"id":"q2","number":2}]""",
        selectedAnswer = listOf("B"),
        answers = mapOf(
            "bank_1#q1" to SavedAnswer(
                userAnswer = listOf("B"), userBlankAnswers = emptyList(), correct = true,
                answerText = "B", earnedScore = 2.0, maxScore = 2.0, autoScored = true,
                sourceBankId = "bank_1", sourceBankName = "药理学复习指南"
            )
        ),
        drafts = mapOf("bank_1#q2" to listOf("C")),
        sequentialBankId = null,
        sequentialStartIndex = null,
        sequentialUsesRecite = false,
        sequentialNextIndex = null
    )

    @Test
    fun roundTripKeepsQueueIndexAnswersAndDrafts() {
        val original = snapshot()
        val restored = PracticeSessionSnapshot.fromJson(original.toJson())

        assertNotNull(restored)
        val copy = restored!!
        assertEquals(original.startedAt, copy.startedAt)
        assertEquals(original.index, copy.index)
        assertEquals(original.sessionKeys, copy.sessionKeys)
        assertEquals(original.questionsJson, JSONArray(original.questionsJson).toString())
        assertEquals(original.selectedAnswer, copy.selectedAnswer)
        assertEquals(listOf("B"), copy.answers.getValue("bank_1#q1").userAnswer)
        assertTrue(copy.answers.getValue("bank_1#q1").correct)
        assertEquals(listOf("C"), copy.drafts.getValue("bank_1#q2"))
    }

    @Test
    fun unknownVersionIsRejectedInsteadOfGuessed() {
        val json = snapshot().toJson()
        json.put("v", 999)
        assertNull(PracticeSessionSnapshot.fromJson(json))
    }

    @Test
    fun keyCountMustMatchQuestionCount() {
        val json = snapshot(keys = listOf("bank_1#q1")).toJson()
        assertNull(PracticeSessionSnapshot.fromJson(json))
    }

    @Test
    fun missingStartedAtIsRejected() {
        val json = snapshot().toJson()
        json.put("startedAt", 0L)
        assertNull(PracticeSessionSnapshot.fromJson(json))
    }
}
