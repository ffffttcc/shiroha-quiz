package com.yiqiu.shirohaquiz.state.record

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.state.WrongQuestionEntry
import com.yiqiu.shirohaquiz.state.FavoriteQuestionEntry
import com.yiqiu.shirohaquiz.state.SlashedQuestionEntry
import com.yiqiu.shirohaquiz.state.StudyRecord
import com.yiqiu.shirohaquiz.state.WrongBookSmartReviewSummary
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.state.QuestionCheckResult
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.sync.records.DerivedCounts

class RecordRepositoryImpl : RecordRepository {
    override val wrongBook: List<WrongQuestionEntry> get() = QuizRepository.wrongBook
    override val favoriteQuestions: List<FavoriteQuestionEntry> get() = QuizRepository.favoriteQuestions
    override val slashedQuestions: List<SlashedQuestionEntry> get() = QuizRepository.slashedQuestions
    override val studyRecords: List<StudyRecord> get() = QuizRepository.studyRecords
    override val wrongBookSmartReviewEnabled: Boolean get() = QuizRepository.wrongBookSmartReviewEnabled
    override val derivedCounts: Map<String, DerivedCounts> get() = QuizRepository.derivedCounts()

    override fun wrongBookEntriesForCurrentScope(): List<WrongQuestionEntry> = QuizRepository.wrongBookEntriesForCurrentScope()
    override fun currentWrongBookScopeLabel(): String = QuizRepository.currentWrongBookScopeLabel()
    override fun reviewDueWrongEntries(): List<WrongQuestionEntry> = QuizRepository.reviewDueWrongEntries()
    override fun todayWrongBookSmartReviewEntries(now: Long): List<WrongQuestionEntry> = QuizRepository.todayWrongBookSmartReviewEntries(now)
    override fun todayWrongBookSmartReviewSummary(now: Long): WrongBookSmartReviewSummary = QuizRepository.todayWrongBookSmartReviewSummary(now)
    override fun todayWrongBookSmartReviewCount(): Int = QuizRepository.todayWrongBookSmartReviewCount()
    override fun wrongBookActiveCount(): Int = QuizRepository.wrongBookActiveCount()
    override fun openWrongQuestion(entry: WrongQuestionEntry) = QuizRepository.openWrongQuestion(entry)
    override fun removeWrongQuestion(entry: WrongQuestionEntry) = QuizRepository.removeWrongQuestion(entry)
    override fun markWrongQuestionMastered(entry: WrongQuestionEntry, mastered: Boolean) = QuizRepository.markWrongQuestionMastered(entry, mastered)
    override fun clearWrongBook() = QuizRepository.clearWrongBook()
    override fun clearWrongBookForCurrentScope() = QuizRepository.clearWrongBookForCurrentScope()
    override fun markWrongQuestionRight(bank: QuizBank?, question: Question) = QuizRepository.markWrongQuestionRight(bank, question)
    override fun addWrongQuestion(bank: QuizBank?, question: Question, result: QuestionCheckResult) = QuizRepository.addWrongQuestion(bank, question, result)

    override fun addFavoriteQuestion(bank: QuizBank?, question: Question) = QuizRepository.addFavoriteQuestion(bank, question)
    override fun removeFavoriteQuestion(entry: FavoriteQuestionEntry) = QuizRepository.removeFavoriteQuestion(entry)

    override fun addSlashedQuestion(bank: QuizBank?, question: Question) = QuizRepository.addSlashedQuestion(bank, question)
    override fun removeSlashedQuestion(entry: SlashedQuestionEntry) = QuizRepository.removeSlashedQuestion(entry)

    override fun findStudyRecord(recordId: String?): StudyRecord? = QuizRepository.findStudyRecord(recordId)
    override fun deleteStudyRecord(recordId: String): Boolean = QuizRepository.deleteStudyRecord(recordId)
    override fun recordQuestionKey(question: Question): String = QuizRepository.recordQuestionKey(question)

    override fun derivedCountsFor(question: Question): DerivedCounts = QuizRepository.derivedCountsFor(question)
    override fun recordConsistencyReport(): List<String> = QuizRepository.recordConsistencyReport()
}
