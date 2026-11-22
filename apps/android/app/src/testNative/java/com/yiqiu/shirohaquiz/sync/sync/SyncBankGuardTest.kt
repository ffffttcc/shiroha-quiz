package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import com.yiqiu.shirohaquiz.state.QuizBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空库护栏：**未加载的库绝不许参与同步**。
 *
 * 真机事故：轻量索引的 `questions = emptyList()` 让 43 个库共用同一个「空库哈希」，
 * 云端正本被一个 132 字节的空 blob 代表；另一台设备照此同步会把题库覆盖成空。
 */
class SyncBankGuardTest {

    private fun bank(id: String, name: String, questions: List<Question>) =
        QuizBank(id = id, name = name, questions = questions)

    private fun question(stem: String) = Question(type = QuestionType.SINGLE, question = stem)

    /** 索引声明 10 题、题目体为空 = 未加载 → 必须被判定为违规。 */
    @Test
    fun unloadedBankIsFlaggedAsViolation() {
        val unloaded = bank("bank_1", "药理|第一章", emptyList())
        val declared = mapOf("bank_1" to 10)
        val violations = SyncBankGuard.violations(listOf(unloaded)) { declared[it] ?: 0 }
        assertEquals(1, violations.size)
        assertThrows(IllegalArgumentException::class.java) {
            SyncBankGuard.requireLoaded(listOf(unloaded)) { declared[it] ?: 0 }
        }
    }

    /** 真正题数为 0 的库是合法空库，必须放行（判据是索引题数，不是 questions.size）。 */
    @Test
    fun legalEmptyBankIsAllowed() {
        val legalEmpty = bank("bank_empty", "空题库", emptyList())
        assertTrue(SyncBankGuard.violations(listOf(legalEmpty)) { 0 }.isEmpty())
        SyncBankGuard.requireLoaded(listOf(legalEmpty)) { 0 }
    }

    /** 已加载的库正常通过。 */
    @Test
    fun loadedBankPasses() {
        val loaded = bank("bank_2", "药理|第二章", listOf(question("Q1"), question("Q2")))
        assertTrue(SyncBankGuard.violations(listOf(loaded)) { 2 }.isEmpty())
        SyncBankGuard.requireLoaded(listOf(loaded)) { 2 }
    }

    /** 抛错消息要点名出事的库，方便一眼定位。 */
    @Test
    fun violationMessageNamesOffendingBank() {
        val unloaded = bank("bank_3", "药理|第三章", emptyList())
        val error = assertThrows(IllegalArgumentException::class.java) {
            SyncBankGuard.requireLoaded(listOf(unloaded)) { 42 }
        }
        assertTrue("应点名库名：${error.message}", error.message.orEmpty().contains("药理|第三章"))
        assertTrue("应说明是未加载：${error.message}", error.message.orEmpty().contains("未加载"))
    }

    /** contentHashChecked：未加载库拒绝计算哈希（不许产出「合法空库哈希」）。 */
    @Test
    fun contentHashCheckedRejectsUnloadedBank() {
        val unloaded = bank("bank_4", "药理|第四章", emptyList())
        val error = assertThrows(IllegalArgumentException::class.java) {
            SyncIdentity.contentHashChecked(unloaded, declaredQuestionCount = 7)
        }
        assertTrue("消息应说明拒绝原因：${error.message}", error.message.orEmpty().contains("空库哈希"))
    }

    /** contentHashChecked：合法空库（声明 0 题）照常算出哈希。 */
    @Test
    fun contentHashCheckedAllowsLegalEmptyBank() {
        val legalEmpty = bank("bank_5", "空库", emptyList())
        val hash = SyncIdentity.contentHashChecked(legalEmpty, declaredQuestionCount = 0)
        assertEquals(64, hash.length)
        assertEquals(SyncIdentity.contentHash(legalEmpty), hash)
    }

    /** contentHashChecked：已加载库结果与普通 contentHash 完全一致（护栏不改变计算口径）。 */
    @Test
    fun contentHashCheckedMatchesPlainHashForLoadedBank() {
        val loaded = bank("bank_6", "药理|第六章", listOf(question("Q")))
        assertEquals(
            SyncIdentity.contentHash(loaded),
            SyncIdentity.contentHashChecked(loaded, declaredQuestionCount = 1)
        )
    }
}
