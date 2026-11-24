package com.yiqiu.shirohaquiz.state.practice

import android.content.Context
import com.yiqiu.shirohaquiz.state.Question
import com.yiqiu.shirohaquiz.state.QuestionType
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.state.QuestionCheckResult
import com.yiqiu.shirohaquiz.state.StudyQuestionResult
import com.yiqiu.shirohaquiz.state.PracticeSessionSnapshot
import com.yiqiu.shirohaquiz.importer.model.InkModels
import com.yiqiu.shirohaquiz.state.QuizRepository
import org.json.JSONObject
import com.yiqiu.shirohaquiz.util.Triple

class PracticeRepositoryImpl : PracticeRepository {
    override val practiceScopeType: String get() = QuizRepository.practiceScopeType
    override val practiceScopeValue: String get() = QuizRepository.practiceScopeValue
    override val practiceQuestions: List<Question> get() = QuizRepository.practiceQuestions
    override val practiceIndex: Int get() = QuizRepository.practiceIndex
    override val selectedAnswer: List<String> get() = QuizRepository.selectedAnswer
    override val practiceLastResult: QuestionCheckResult? get() = QuizRepository.practiceLastResult
    override val practiceMode: String get() = QuizRepository.practiceMode
    override val practiceBatchSubmitted: Boolean get() = QuizRepository.practiceBatchSubmitted
    override val practiceBatchGroupSize: Int get() = QuizRepository.practiceBatchGroupSize
    override val practiceBatchGroupStartIndex: Int get() = QuizRepository.practiceBatchGroupStartIndex
    override val practiceDraftAnswers: Map<String, List<String>> get() = QuizRepository.practiceDraftAnswers
    override var practiceNextRequiresResult: Boolean
        get() = QuizRepository.practiceNextRequiresResult
        set(value) { QuizRepository.practiceNextRequiresResult = value }
    override val practiceReciteModeEnabled: Boolean get() = QuizRepository.practiceReciteModeEnabled
    override val practiceOptionShuffleSeed: Long get() = QuizRepository.practiceOptionShuffleSeed
    override val slashedQuestions: List<com.yiqiu.shirohaquiz.state.SlashedQuestionEntry> get() = QuizRepository.slashedQuestions

    override fun currentPracticeScopeKey(): String = QuizRepository.currentPracticeScopeKey()
    override fun currentPracticeScopeLabel(): String = QuizRepository.currentPracticeScopeLabel()
    override fun currentPracticeScopeSummary(): String = QuizRepository.currentPracticeScopeSummary()
    override fun currentPracticeScopeBanks(): List<QuizBank> = QuizRepository.currentPracticeScopeBanks()
    override fun activePracticePoolSources(bank: QuizBank?): List<com.yiqiu.shirohaquiz.state.PracticeQuestionSource> = QuizRepository.activePracticePoolSources(bank)
    override fun currentPracticeQuestion(): Question? = QuizRepository.currentPracticeQuestion()
    override fun currentPracticeSessionKey(): String? = QuizRepository.currentPracticeSessionKey()
    override fun practiceSessionKeyAt(index: Int): String? = QuizRepository.practiceSessionKeyAt(index)
    override fun currentPracticeSourceBank(): QuizBank? = QuizRepository.currentPracticeSourceBank()
    override fun canSlashCurrentPracticeQuestion(): Boolean = QuizRepository.canSlashCurrentPracticeQuestion()
    override fun activePracticeQuestions(): List<Question> = QuizRepository.activePracticeQuestions()
    override fun activePracticePoolQuestions(bank: QuizBank?): List<Question> = QuizRepository.activePracticePoolQuestions(bank)
    override fun practiceAvailableQuestionCount(bank: QuizBank?): Int = QuizRepository.practiceAvailableQuestionCount(bank)

    override fun startPracticeSession(
        scopeType: String, scopeValue: String, sourceLabel: String, mode: String,
        questionCount: Int, shuffleSeed: Long, bankIds: List<String>,
        sequentialStartIndex: Int?, usesRecite: Boolean
    ): Boolean = QuizRepository.startPracticeSession(scopeType, scopeValue, sourceLabel, mode, questionCount, shuffleSeed, bankIds, sequentialStartIndex, usesRecite)

    override fun startWrongBookPractice(scopeLabel: String, questions: List<Question>, scopeType: String, scopeValue: String): Boolean = QuizRepository.startWrongBookPractice(scopeLabel, questions, scopeType, scopeValue)
    override fun startTodayWrongBookReview(): Boolean = QuizRepository.startTodayWrongBookReview()
    override fun startSequentialPracticeSession(bankIds: List<String>, startIndex: Int, questionCount: Int, usesRecite: Boolean): Boolean = QuizRepository.startSequentialPracticeSession(bankIds, startIndex, questionCount, usesRecite)
    override fun resetSequentialPracticeProgress() = QuizRepository.resetSequentialPracticeProgress()
    override fun nextQuestion() = QuizRepository.nextQuestion()
    override fun previousQuestion() = QuizRepository.previousQuestion()
    override fun completePracticeSession() = QuizRepository.completePracticeSession()
    override fun endPracticeSession() = QuizRepository.endPracticeSession()
    override fun canSaveSequentialProgressOnPracticeExit(): Boolean = QuizRepository.canSaveSequentialProgressOnPracticeExit()
    override fun endPracticeSessionSavingSequentialProgress() = QuizRepository.endPracticeSessionSavingSequentialProgress()
    override fun startNextPracticeBatchGroup(): Boolean = QuizRepository.startNextPracticeBatchGroup()
    override fun isAllPracticeBatchGroupsSubmitted(): Boolean = QuizRepository.isAllPracticeBatchGroupsSubmitted()
    override fun objectiveQuestionTypes(): Set<QuestionType> = QuizRepository.objectiveQuestionTypes()
    override fun questionTypeCounts(questions: List<Question>): Map<QuestionType, Int> = QuizRepository.questionTypeCounts(questions)
    override fun practiceAnsweredCount(): Int = QuizRepository.practiceAnsweredCount()
    override fun practiceAutoScoredAnsweredCount(): Int = QuizRepository.practiceAutoScoredAnsweredCount()
    override fun practiceCorrectCount(): Int = QuizRepository.practiceCorrectCount()

    override fun updateCurrentPracticeQuestion(updated: Question): Boolean = QuizRepository.updateCurrentPracticeQuestion(updated)
    override fun updateCurrentPracticeQuestionAnalysis(analysis: String): Boolean = QuizRepository.updateCurrentPracticeQuestionAnalysis(analysis)
    override fun submitPracticeQuestion(
        temporaryReferenceAnswer: List<String>?,
        temporaryReferenceAnswerText: String?
    ): QuestionCheckResult? = QuizRepository.submitPracticeQuestion(temporaryReferenceAnswer, temporaryReferenceAnswerText)
    override fun saveQuestionInkAnnotations(question: Question, strokes: List<InkModels.InkStroke>, fromExam: Boolean): Boolean = QuizRepository.saveQuestionInkAnnotations(question, strokes, fromExam)

    override fun persistPracticeSessionSnapshot(sync: Boolean = false) = QuizRepository.persistPracticeSessionSnapshot(sync)
    override fun discardSavedPracticeSession() = QuizRepository.discardSavedPracticeSession()
    override fun hasSavedPracticeSession(): Boolean = QuizRepository.hasSavedPracticeSession()
    override fun savedPracticeSessionSummary(): Triple<Int, Int, Int>? = QuizRepository.savedPracticeSessionSummary()
    override fun resumeSavedPracticeSession(): Boolean = QuizRepository.resumeSavedPracticeSession()
    override fun abandonSavedPracticeSession() = QuizRepository.abandonSavedPracticeSession()

    override fun exportPortablePracticeSnapshots(): JSONObject = QuizRepository.exportPortablePracticeSnapshots()
    override fun applyRemotePracticeProgress(remote: JSONObject): Boolean = QuizRepository.applyRemotePracticeProgress(remote)

    override var rememberPracticeSettingsEnabled: Boolean
        get() = QuizRepository.rememberPracticeSettingsEnabled
        set(value) { QuizRepository.rememberPracticeSettingsEnabled = value }
    override var swipeNavigationEnabled: Boolean
        get() = QuizRepository.swipeNavigationEnabled
        set(value) { QuizRepository.swipeNavigationEnabled = value }
    override var practiceAutoSubmitEnabled: Boolean
        get() = QuizRepository.practiceAutoSubmitEnabled
        set(value) { QuizRepository.practiceAutoSubmitEnabled = value }
    override var practiceAutoNextEnabled: Boolean
        get() = QuizRepository.practiceAutoNextEnabled
        set(value) { QuizRepository.practiceAutoNextEnabled = value }
}
