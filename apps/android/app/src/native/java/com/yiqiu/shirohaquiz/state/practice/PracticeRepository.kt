package com.yiqiu.shirohaquiz.state.practice

import android.content.Context
import com.yiqiu.shirohaquiz.state.Question
import com.yiqiu.shirohaquiz.state.QuestionType
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.state.QuestionCheckResult
import com.yiqiu.shirohaquiz.state.StudyQuestionResult
import com.yiqiu.shirohaquiz.state.PracticeSessionSnapshot
import org.json.JSONObject

/** 练习会话仓储接口：会话生命周期、快照持久化、同步导出/吸收。 */
interface PracticeRepository {
    // ==== 当前会话状态 ====
    val practiceScopeType: String
    val practiceScopeValue: String
    val practiceQuestions: List<Question>
    val practiceIndex: Int
    val selectedAnswer: List<String>
    val practiceLastResult: QuestionCheckResult?
    val practiceMode: String
    val practiceBatchSubmitted: Boolean
    val practiceBatchGroupSize: Int
    val practiceBatchGroupStartIndex: Int
    val practiceDraftAnswers: Map<String, List<String>>
    var practiceNextRequiresResult: Boolean
    val practiceReciteModeEnabled: Boolean
    val practiceOptionShuffleSeed: Long
    val slashedQuestions: List<com.yiqiu.shirohaquiz.state.SlashedQuestionEntry>

    // ==== 练习范围 ====
    fun currentPracticeScopeKey(): String
    fun currentPracticeScopeLabel(): String
    fun currentPracticeScopeSummary(): String
    fun currentPracticeScopeBanks(): List<QuizBank>
    fun activePracticePoolSources(bank: QuizBank?): List<com.yiqiu.shirohaquiz.state.PracticeQuestionSource>
    fun currentPracticeQuestion(): Question?
    fun currentPracticeSessionKey(): String?
    fun practiceSessionKeyAt(index: Int): String?
    fun currentPracticeSourceBank(): QuizBank?
    fun canSlashCurrentPracticeQuestion(): Boolean
    fun activePracticeQuestions(): List<Question>
    fun activePracticePoolQuestions(bank: QuizBank?): List<Question>
    fun practiceAvailableQuestionCount(bank: QuizBank?): Int

    // ==== 会话控制 ====
    fun startPracticeSession(
        scopeType: String,
        scopeValue: String,
        sourceLabel: String,
        mode: String,
        questionCount: Int,
        shuffleSeed: Long,
        bankIds: List<String>,
        sequentialStartIndex: Int? = null,
        usesRecite: Boolean = false
    ): Boolean
    fun startWrongBookPractice(scopeLabel: String, questions: List<Question>, scopeType: String, scopeValue: String): Boolean
    fun startTodayWrongBookReview(): Boolean
    fun startSequentialPracticeSession(
        bankIds: List<String>,
        startIndex: Int,
        questionCount: Int,
        usesRecite: Boolean
    ): Boolean
    fun resetSequentialPracticeProgress()
    fun nextQuestion()
    fun previousQuestion()
    fun completePracticeSession()
    fun endPracticeSession()
    fun canSaveSequentialProgressOnPracticeExit(): Boolean
    fun endPracticeSessionSavingSequentialProgress()
    fun startNextPracticeBatchGroup(): Boolean
    fun isAllPracticeBatchGroupsSubmitted(): Boolean
    fun objectiveQuestionTypes(): Set<QuestionType>
    fun questionTypeCounts(questions: List<Question>): Map<QuestionType, Int>
    fun practiceAnsweredCount(): Int
    fun practiceAutoScoredAnsweredCount(): Int
    fun practiceCorrectCount(): Int

    // ==== 答案/草稿/标记 ====
    fun updateCurrentPracticeQuestion(updated: Question): Boolean
    fun updateCurrentPracticeQuestionAnalysis(analysis: String): Boolean
    fun submitPracticeQuestion(
        temporaryReferenceAnswer: List<String>?,
        temporaryReferenceAnswerText: String?
    ): QuestionCheckResult?
    fun saveQuestionInkAnnotations(question: Question, strokes: List<com.yiqiu.shirohaquiz.importer.model.InkModels.InkStroke>, fromExam: Boolean): Boolean

    // ==== 快照持久化 ====
    fun persistPracticeSessionSnapshot(sync: Boolean = false)
    fun discardSavedPracticeSession()
    fun hasSavedPracticeSession(): Boolean
    fun savedPracticeSessionSummary(): com.yiqiu.shirohaquiz.util.Triple<Int, Int, Int>?
    fun resumeSavedPracticeSession(): Boolean
    fun abandonSavedPracticeSession()

    // ==== 同步导出/吸收 ====
    fun exportPortablePracticeSnapshots(): JSONObject
    fun applyRemotePracticeProgress(remote: JSONObject): Boolean

    // ==== 设置 ====
    var rememberPracticeSettingsEnabled: Boolean
    var swipeNavigationEnabled: Boolean
    var practiceAutoSubmitEnabled: Boolean
    var practiceAutoNextEnabled: Boolean
}
