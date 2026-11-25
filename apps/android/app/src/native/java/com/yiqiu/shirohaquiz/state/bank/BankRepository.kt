package com.yiqiu.shirohaquiz.state.bank

import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import com.yiqiu.shirohaquiz.state.QuizRepository.JsonImportPreview
import com.yiqiu.shirohaquiz.state.BackupContentPreview

/** 题库仓储接口：仅对外暴露题库增删改查、导入导出、统计。不含练习/记录逻辑。 */
interface BankRepository {
    // ==== 查询 ====
    val banks: List<QuizBank>
    val activeBankId: String?
    fun activeBank(): QuizBank?
    fun isBankPendingLoad(bankId: String): Boolean
    fun bankQuestionCount(bankId: String): Int
    fun bankTypeCounts(bankId: String): Map<QuestionType, Int>
    fun indexedQuestionCount(bankId: String): Int
    fun pendingBankContentIds(): List<String>
    fun ensureAllBanksLoaded(): Set<String>
    fun materialForQuestion(question: Question): String?

    // ==== 派生/显示 ====
    fun displayQuestionText(question: Question): String
    fun editableQuestionText(question: Question): String
    fun applyQuestionTextEdit(question: Question, editedText: String): Question

    // ==== 修改 ====
    fun importBank(context: android.content.Context, rawText: String): String
    fun appendQuestionsToBank(context: android.content.Context, bankId: String, newQuestions: List<Question>): Boolean
    fun setActiveBank(context: android.content.Context, bankId: String)
    fun deleteBank(context: android.content.Context, bankId: String)
    fun renameBank(context: android.content.Context, bankId: String, newName: String): Boolean
    fun updateBankInfo(context: android.content.Context, bankId: String, newGroupName: String, newName: String): Boolean
    fun updateQuestion(context: android.content.Context, bankId: String, updatedQuestion: Question): Boolean
    fun replaceBankQuestions(context: android.content.Context, bankId: String, questions: List<Question>): Boolean
    fun deleteQuestion(context: android.content.Context, bankId: String, questionId: String): Boolean
    fun applySyncedBank(groupName: String, name: String, questions: List<Question>, bookName: String): Boolean

    // ==== 导入导出/备份 ====
    fun parseImportJsonBank(rawText: String): QuizBank?
    fun parseImportJsonPreview(rawText: String): JsonImportPreview?
    fun exportBanksBackupJson(bankIds: Set<String>): String
    fun exportFullBackupJson(): String
    fun exportBanksBackupZip(bankIds: Set<String>): ByteArray
    fun exportFullBackupZip(): ByteArray
    fun previewBackupBytes(bytes: ByteArray): BackupContentPreview
    fun replaceContentFromBackupBytes(bytes: ByteArray): String
    fun importBackupJson(context: android.content.Context, rawText: String): String
    fun importBackupBytes(context: android.content.Context, fileName: String, bytes: ByteArray): String
    fun clearAllLocalData(context: android.content.Context)
}
