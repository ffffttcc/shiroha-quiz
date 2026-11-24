package com.yiqiu.shirohaquiz.state.exam

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import com.yiqiu.shirohaquiz.state.ExamSummary

/** 考试模式仓储：考试会话、答题、计时、评分。 */
interface ExamRepository {
    val examQuestions: List<Question>
    val examIndex: Int
    val examDurationSeconds: Int
    val examRemainingSeconds: Int
    val examAutoSubmitted: Boolean
    val examAnswerResults: Map<String, com.yiqiu.shirohaquiz.state.StudyQuestionResult>
    val examDraftAnswers: Map<String, List<String>>
    val examClozeAnswers: Map<String, List<String>>

    fun startExam(questionCount: Int, durationMinutes: Int): Boolean
    fun startExam(questionCount: Int, durationMinutes: Int, bankIds: List<String>): Boolean
    fun startExamByTypeCounts(typeCounts: Map<QuestionType, Int>, durationMinutes: Int, bankIds: List<String>): Boolean
    fun resetExam()
    fun currentExamQuestion(): Question?
    fun nextExamQuestion()
    fun previousExamQuestion()
    fun jumpToExamQuestion(index: Int)
    fun toggleExamAnswer(key: String, multiple: Boolean)
    fun updateExamTextAnswer(text: String)
    fun updateExamClozeAnswer(index: Int, key: String)
    fun updateExamBlankAnswer(index: Int, text: String)
    fun submitExam(autoSubmitted: Boolean)
    fun tickExam()
    fun isExamQuestionAnswered(question: Question): Boolean
    fun examAnsweredCount(): Int
    fun examCorrectCount(): Int
    fun examSummary(): ExamSummary
    fun examTotalScore(): Double
    fun examEarnedScore(): Double
}
