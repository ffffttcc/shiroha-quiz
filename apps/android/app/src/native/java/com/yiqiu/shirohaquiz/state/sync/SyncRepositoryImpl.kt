package com.yiqiu.shirohaquiz.state.sync

import com.yiqiu.shirohaquiz.state.QuizRepository
import org.json.JSONObject

class SyncRepositoryImpl : SyncRepository {
    override val practiceSessionStartedAt: Long get() = QuizRepository.practiceSessionStartedAt
    override var practiceProgressRevision: Int
        get() = QuizRepository.practiceProgressRevision
        set(value) { QuizRepository.practiceProgressRevision = value }

    override fun exportPortablePracticeSnapshots(): JSONObject = QuizRepository.exportPortablePracticeSnapshots()
    override fun applyRemotePracticeProgress(remote: JSONObject): Boolean = QuizRepository.applyRemotePracticeProgress(remote)
}
