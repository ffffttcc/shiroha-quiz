package com.yiqiu.shirohaquiz.state.record

import android.content.Context
import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.state.WrongQuestionEntry
import com.yiqiu.shirohaquiz.state.FavoriteQuestionEntry
import com.yiqiu.shirohaquiz.state.SlashedQuestionEntry
import com.yiqiu.shirohaquiz.state.StudyRecord
import com.yiqiu.shirohaquiz.state.WrongBookSmartReviewSummary
import com.yiqiu.shirohaquiz.importer.model.InkModels

/** 记录仓储：错题本、收藏、划线、学习记录、智能复习。 */
interface RecordRepository {
    // ==== 错题本 ====
    val wrongBook: List<WrongQuestionEntry>
    fun wrongBookEntriesForCurrentScope(): List<WrongQuestionEntry>
    fun currentWrongBookScopeLabel(): String
    fun reviewDueWrongEntries(): List<WrongQuestionEntry>
    fun todayWrongBookSmartReviewEntries(now: Long): List<WrongQuestionEntry>
    fun todayWrongBookSmartReviewSummary(now: Long): WrongBookSmartReviewSummary
    fun todayWrongBookSmartReviewCount(): Int
    fun wrongBookActiveCount(): Int
    fun openWrongQuestion(entry: WrongQuestionEntry)
    fun removeWrongQuestion(entry: WrongQuestionEntry)
    fun markWrongQuestionMastered(entry: WrongQuestionEntry, mastered: Boolean)
    fun clearWrongBook()
    fun clearWrongBookForCurrentScope()
    fun markWrongQuestionRight(bank: com.yiqiu.shirohaquiz.state.QuizBank?, question: Question)
    fun addWrongQuestion(bank: com.yiqiu.shirohaquiz.state.QuizBank?, question: Question, result: com.yiqiu.shirohaquiz.state.QuestionCheckResult)

    // ==== 收藏 ====
    val favoriteQuestions: List<FavoriteQuestionEntry>
    fun addFavoriteQuestion(bank: com.yiqiu.shirohaquiz.state.QuizBank?, question: Question)
    fun removeFavoriteQuestion(entry: FavoriteQuestionEntry)

    // ==== 划线题 ====
    val slashedQuestions: List<SlashedQuestionEntry>
    fun addSlashedQuestion(bank: com.yiqiu.shirohaquiz.state.QuizBank?, question: Question)
    fun removeSlashedQuestion(entry: SlashedQuestionEntry)

    // ==== 学习记录 ====
    val studyRecords: List<StudyRecord>
    fun findStudyRecord(recordId: String?): StudyRecord?
    fun deleteStudyRecord(recordId: String): Boolean
    fun recordQuestionKey(question: Question): String

    // ==== 统计派生 ====
    val wrongBookSmartReviewEnabled: Boolean
    val derivedCounts: Map<String, com.yiqiu.shirohaquiz.sync.records.DerivedCounts>
    fun derivedCountsFor(question: Question): com.yiqiu.shirohaquiz.sync.records.DerivedCounts
    fun recordConsistencyReport(): List<String>
}
