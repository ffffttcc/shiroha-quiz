package com.yiqiu.shirohaquiz.state

import android.content.Context
import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 多设备不堆重复库：同名同组就地覆盖（保留本地 id），不同名才新增。
 * 覆盖必须保留本地 bankId，否则 wrongBook / favorites / studyRecords 全部失联。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupImportDedupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun initialize() {
        QuizRepository.resetForTesting()
        context.getSharedPreferences("shiroha_quiz_native", Context.MODE_PRIVATE).edit().clear().commit()
        QuizRepository.init(context)
    }

    @After
    fun reset() {
        QuizRepository.resetForTesting()
    }

    private fun question(text: String) = Question(type = QuestionType.SINGLE, question = text, answer = listOf("A"))

    private fun bank(id: String, name: String, group: String, questionCount: Int) = QuizBank(
        id = id,
        name = name,
        groupName = group,
        questions = (1..questionCount).map { question("$name 第 $it 题") }
    )

    @Test
    fun sameNameAndGroupOverwritesInPlaceKeepingLocalId() {
        QuizRepository.banks += bank("bank_local_1", "第一章 绪论", "药理", 1)

        val (idMap, added, replaced) =
            QuizRepository.applyImportedBankMerge(listOf(bank("import_a", "第一章 绪论", "药理", 3)), now = 999L)

        assertEquals("本地只有一个库，不能变成两个", 1, QuizRepository.banks.size)
        val merged = QuizRepository.banks.first()
        assertEquals("必须保留本地 bankId（错题本/收藏/进度都按它关联）", "bank_local_1", merged.id)
        assertEquals("题目整体换成备份里的版本", 3, merged.questions.size)
        assertEquals("bank_local_1", idMap["import_a"])
        assertTrue("覆盖不应算新增", added.isEmpty())
        assertEquals(1, replaced.size)
    }

    @Test
    fun importingTheSameBackupTwiceDoesNotGrowTheBankList() {
        val incoming = bank("import_a", "第一章 绪论", "药理", 2)
        QuizRepository.applyImportedBankMerge(listOf(incoming), now = 1L)
        assertEquals(1, QuizRepository.banks.size)
        val idAfterFirst = QuizRepository.banks.first().id

        QuizRepository.applyImportedBankMerge(listOf(incoming), now = 2L)
        assertEquals("来回导入两次同一份备份，库数量必须不变", 1, QuizRepository.banks.size)
        assertEquals(idAfterFirst, QuizRepository.banks.first().id)
        assertEquals(2, QuizRepository.banks.first().questions.size)
    }

    @Test
    fun sameNameDifferentGroupIsAddedNotMerged() {
        QuizRepository.banks += bank("bank_local_1", "第一章 绪论", "药理", 5)

        val (_, added, replaced) =
            QuizRepository.applyImportedBankMerge(listOf(bank("import_b", "第一章 绪论", "英语", 2)), now = 7L)

        assertEquals("分组不同视为不同题库", 2, QuizRepository.banks.size)
        assertEquals(1, added.size)
        assertTrue(replaced.isEmpty())
        assertTrue(QuizRepository.banks.any { it.groupName == "英语" && it.questions.size == 2 })
        assertEquals("原库内容不受影响", 5, QuizRepository.banks.first { it.id == "bank_local_1" }.questions.size)
    }

    @Test
    fun newBankGetsFreshIdAndKeepsName() {
        val (idMap, added, replaced) =
            QuizRepository.applyImportedBankMerge(listOf(bank("import_c", "新章节", "药理", 4)), now = 42L)

        assertEquals(1, QuizRepository.banks.size)
        assertEquals("bank_42_0", QuizRepository.banks.first().id)
        assertEquals("新章节", QuizRepository.banks.first().name)
        assertEquals("bank_42_0", idMap["import_c"])
        assertEquals(1, added.size)
        assertTrue(replaced.isEmpty())
    }
}
