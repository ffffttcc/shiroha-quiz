package com.yiqiu.shirohaquiz.state.exam

import com.yiqiu.shirohaquiz.state.Question
import com.yiqiu.shirohaquiz.state.ExamSummary
import com.yiqiu.shirohaquiz.state.StudyQuestionResult
import com.yiqiu.shirohaquiz.state.QuizRepository

class ExamRepositoryImpl : ExamRepository {
    override val examQuestions: List<Question> get() = QuizRepository.examQuestions
    override val examIndex: Int get() = QuizRepository.examIndex
    override val examDurationSeconds: Int get() = QuizRepository.examDurationSeconds
    override val examRemainingSeconds: Int get() = QuizRepository.examRemainingSeconds
    override val examAutoSubmitted: Boolean get() = QuizRepository.examAutoSubmitted
    override val examAnswerResults: Map<String, StudyQuestionResult> get() = QuizRepository.examAnswerResults
    override val examDraftAnswers: Map<String, List<String>> get() = QuizRepository.examDraftAnswers
    override val examClozeAnswers: Map<String, List<String>> get() = QuizRepository.examClozeAnswers

    override fun startExam(questionCount: Int, durationMinutes: Int): Boolean = QuizRepository.startExam(questionCount, durationMinutes)
    override fun startExam(questionCount: Int, durationMinutes: Int, bankIds: List<String>): Boolean = QuizRepository.startExam(questionCount, durationMinutes, bankIds)
    override fun startExamByTypeCounts(typeCounts: Map<com.yiqiu.shirohaquiz.state.QuestionType, Int>, durationMinutes: Int, bankIds: List<String>): Boolean = QuizRepository.startExamByTypeCounts(typeCounts, durationMinutes, bankIds)
    override fun resetExam() = QuizRepository.resetExam()
    override fun currentExamQuestion(): Question? = QuizRepository.currentExamQuestion()
    override fun nextExamQuestion() = QuizRepository.nextExamQuestion()
    override fun previousExamQuestion() = QuizRepository.previousExamQuestion()
    override fun jumpToExamQuestion(index: Int) = QuizRepository.jumpToExamQuestion(index)
    override fun toggleExamAnswer(key: String, multiple: Boolean) = QuizRepository.toggleExamAnswer(key, multiple)
    override fun updateExamTextAnswer(text: String) = QuizRepository.updateExamTextAnswer(text)
    override fun updateExamClozeAnswer(index: Int, key: String) = QuizRepository.updateExamClozeAnswer(index, key)
    override fun updateExamBlankAnswer(index: Int, text: String) = QuizRepository.updateExamBlankAnswer(index, text)
    override fun submitExam(autoSubmitted: Boolean) = QuizRepository.submitExam(autoSubmitted)
    override fun tickExam() = QuizRepository.tickExam()
    override fun isExamQuestionAnswered(question: Question): Boolean = QuizRepository.isExamQuestionAnswered(question)
    override fun examAnsweredCount(): Int = QuizRepository.examAnsweredCount()
    override fun examCorrectCount(): Int = QuizRepository.examCorrectCount()
    override fun examSummary(): ExamSummary = QuizRepository.examSummary()
    override fun examTotalScore(): Double = QuizRepository.examTotalScore()
    override fun examEarnedScore(): Double = QuizRepository.examEarnedScore()
}
