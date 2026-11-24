package com.yiqiu.shirohaquiz.state.stats

import com.yiqiu.shirohaquiz.state.StudyRecord
import com.yiqiu.shirohaquiz.state.RecordProgress
import com.yiqiu.shirohaquiz.state.StudyStats
import com.yiqiu.shirohaquiz.state.QuizRepository

class StatsRepositoryImpl : StatsRepository {
    override val studyRecords: List<StudyRecord> get() = QuizRepository.studyRecords

    override fun recordProgress(): RecordProgress = QuizRepository.recordProgress()
    override fun studyStats(): StudyStats = QuizRepository.studyStats()
    override fun todayPracticeCount(): Int = QuizRepository.todayPracticeCount()
    override fun pendingReviewCount(): Int = QuizRepository.pendingReviewCount()
    override fun favoriteCount(): Int = QuizRepository.favoriteCount()
}
