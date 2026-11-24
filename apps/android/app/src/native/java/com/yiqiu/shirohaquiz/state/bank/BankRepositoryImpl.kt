package com.yiqiu.shirohaquiz.state.bank

import android.content.Context
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import com.yiqiu.shirohaquiz.state.JsonImportPreview
import com.yiqiu.shirohaquiz.state.BackupContentPreview
import com.yiqiu.shirohaquiz.state.QuizRepository

/** 题库仓储实现：当前委托 QuizRepository，后续逐步内迁代码。 */
class BankRepositoryImpl : BankRepository {
    override val banks: List<QuizBank> get() = QuizRepository.banks
    override val activeBankId: String? get() = QuizRepository.activeBankId

    override fun activeBank(): QuizBank? = QuizRepository.activeBank()
    override fun isBankPendingLoad(bankId: String): Boolean = QuizRepository.isBankPendingLoad(bankId)
    override fun bankQuestionCount(bankId: String): Int = QuizRepository.bankQuestionCount(bankId)
    override fun bankTypeCounts(bankId: String): Map<QuestionType, Int> = QuizRepository.bankTypeCounts(bankId)
    override fun indexedQuestionCount(bankId: String): Int = QuizRepository.indexedQuestionCount(bankId)
    override fun pendingBankContentIds(): List<String> = QuizRepository.pendingBankContentIds()
    override fun ensureAllBanksLoaded(): Set<String> = QuizRepository.ensureAllBanksLoaded()
    override fun materialForQuestion(question: Question): String? = QuizRepository.materialForQuestion(question)

    override fun displayQuestionText(question: Question): String = QuizRepository.displayQuestionText(question)
    override fun editableQuestionText(question: Question): String = QuizRepository.editableQuestionText(question)
    override fun applyQuestionTextEdit(question: Question, editedText: String): Question = QuizRepository.applyQuestionTextEdit(question, editedText)

    override fun importBank(context: Context, rawText: String): String = QuizRepository.importBank(context, rawText)
    override fun appendQuestionsToBank(context: Context, bankId: String, newQuestions: List<Question>): Boolean = QuizRepository.appendQuestionsToBank(context, bankId, newQuestions)
    override fun setActiveBank(context: Context, bankId: String) = QuizRepository.setActiveBank(context, bankId)
    override fun deleteBank(context: Context, bankId: String) = QuizRepository.deleteBank(context, bankId)
    override fun renameBank(context: Context, bankId: String, newName: String): Boolean = QuizRepository.renameBank(context, bankId, newName)
    override fun updateBankInfo(context: Context, bankId: String, newGroupName: String, newName: String): Boolean = QuizRepository.updateBankInfo(context, bankId, newGroupName, newName)
    override fun updateQuestion(context: Context, bankId: String, updatedQuestion: Question): Boolean = QuizRepository.updateQuestion(context, bankId, updatedQuestion)
    override fun replaceBankQuestions(context: Context, bankId: String, questions: List<Question>): Boolean = QuizRepository.replaceBankQuestions(context, bankId, questions)
    override fun deleteQuestion(context: Context, bankId: String, questionId: String): Boolean = QuizRepository.deleteQuestion(context, bankId, questionId)
    override fun applySyncedBank(groupName: String, name: String, questions: List<Question>, bookName: String): Boolean = QuizRepository.applySyncedBank(groupName, name, questions, bookName)

    override fun parseImportJsonBank(rawText: String): QuizBank? = QuizRepository.parseImportJsonBank(rawText)
    override fun parseImportJsonPreview(rawText: String): JsonImportPreview? = QuizRepository.parseImportJsonPreview(rawText)
    override fun exportBanksBackupJson(bankIds: Set<String>): String = QuizRepository.exportBanksBackupJson(bankIds)
    override fun exportFullBackupJson(): String = QuizRepository.exportFullBackupJson()
    override fun exportBanksBackupZip(bankIds: Set<String>): ByteArray = QuizRepository.exportBanksBackupZip(bankIds)
    override fun exportFullBackupZip(): ByteArray = QuizRepository.exportFullBackupZip()
    override fun previewBackupBytes(bytes: ByteArray): BackupContentPreview = QuizRepository.previewBackupBytes(bytes)
    override fun replaceContentFromBackupBytes(bytes: ByteArray): String = QuizRepository.replaceContentFromBackupBytes(bytes)
    override fun importBackupJson(context: Context, rawText: String): String = QuizRepository.importBackupJson(context, rawText)
    override fun importBackupBytes(context: Context, fileName: String, bytes: ByteArray): String = QuizRepository.importBackupBytes(context, fileName, bytes)
    override fun clearAllLocalData(context: Context) = QuizRepository.clearAllLocalData(context)
}
