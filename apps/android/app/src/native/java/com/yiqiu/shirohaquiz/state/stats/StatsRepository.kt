package com.yiqiu.shirohaquiz.state.stats

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.state.StudyRecord
import com.yiqiu.shirohaquiz.state.RecordProgress
import com.yiqiu.shirohaquiz.state.StudyStats

/** 统计仓储：学习进度、正确率、记录聚合。 */
interface StatsRepository {
    val studyRecords: List<StudyRecord>
    fun recordProgress(): RecordProgress
    fun studyStats(): StudyStats
    fun todayPracticeCount(): Int
    fun pendingReviewCount(): Int
    fun favoriteCount(): Int
}
