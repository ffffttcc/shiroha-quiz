package com.yiqiu.shirohaquiz.state

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一场练习的完整快照（用于「退出/换库后回来续做」）。
 *
 * 设计要点：
 * - **题目按当时的 JSON 原样存**（不是只存 id），这样即使题库之后被编辑/裁剪，
 *   续做时看到的队列、选项顺序仍与退出时一致。
 * - 结果与草稿按 sessionKey 存，key 与 [sessionKeys] 一一对应。
 * - [startedAt] 沿用原场的值：续做后写的记录 id 仍是 `practice_${startedAt}`，
 *   同一条记录被更新而不是新增，正确率不会分裂成两条。
 */
internal data class PracticeSessionSnapshot(
    val startedAt: Long,
    val practiceMode: String,
    val sourceLabel: String,
    val scopeType: String?,
    val scopeName: String?,
    val index: Int,
    val batchGroupSize: Int,
    val batchSubmitted: Boolean,
    val batchGroupStartIndex: Int,
    val shuffleSeed: Long,
    val sessionKeys: List<String>,
    val questionsJson: String,
    val selectedAnswer: List<String>,
    val answers: Map<String, SavedAnswer>,
    val drafts: Map<String, List<String>>,
    val sequentialBankId: String?,
    val sequentialStartIndex: Int?,
    val sequentialUsesRecite: Boolean,
    val sequentialNextIndex: Int?,
    /** 最后一次落盘时刻：跨设备同步时以它为先后判据。 */
    val updatedAt: Long = 0L
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("startedAt", startedAt)
        root.put("practiceMode", practiceMode)
        root.put("sourceLabel", sourceLabel)
        root.put("scopeType", scopeType)
        root.put("scopeName", scopeName)
        root.put("index", index)
        root.put("batchGroupSize", batchGroupSize)
        root.put("batchSubmitted", batchSubmitted)
        root.put("batchGroupStartIndex", batchGroupStartIndex)
        root.put("shuffleSeed", shuffleSeed)
        root.put("sessionKeys", JSONArray(sessionKeys))
        root.put("questions", JSONArray(questionsJson))
        root.put("selectedAnswer", JSONArray(selectedAnswer))
        val answerObject = JSONObject()
        answers.forEach { (key, answer) -> answerObject.put(key, answer.toJson()) }
        root.put("answers", answerObject)
        val draftObject = JSONObject()
        drafts.forEach { (key, draft) -> draftObject.put(key, JSONArray(draft)) }
        root.put("drafts", draftObject)
        root.put("seqBankId", sequentialBankId)
        root.put("seqStart", sequentialStartIndex)
        root.put("seqRecite", sequentialUsesRecite)
        root.put("seqNext", sequentialNextIndex)
        root.put("updatedAt", updatedAt)
        return root

    /** 跨设备同步用的精简版：去掉题目正文（对方按 sessionKeys 用本地题库重建）。 */
    fun toPortableJson(): JSONObject {
        val root = toJson()
        root.remove("questions")
        return root
    }
    }

    companion object {
        const val VERSION = 1

        fun fromJson(root: JSONObject?): PracticeSessionSnapshot? {
            if (root == null) return null
            if (root.optInt("v", 0) != VERSION) return null
            val keysJson = root.optJSONArray("sessionKeys") ?: return null
            val questions = root.optJSONArray("questions") ?: return null
            if (keysJson.length() == 0 || keysJson.length() != questions.length()) return null
            val keys = buildList {
                for (i in 0 until keysJson.length()) {
                    add(keysJson.optString(i))
                }
            }
            val answersJson = root.optJSONObject("answers") ?: JSONObject()
            val answers = buildMap {
                answersJson.keys().forEach { key ->
                    answersJson.optJSONObject(key)?.let { SavedAnswer.fromJson(it) }?.let { put(key, it) }
                }
            }
            val draftsJson = root.optJSONObject("drafts") ?: JSONObject()
            val drafts = buildMap {
                draftsJson.keys().forEach { key ->
                    val array = draftsJson.optJSONArray(key) ?: return@forEach
                    put(key, buildList { for (i in 0 until array.length()) add(array.optString(i)) })
                }
            }
            val selected = root.optJSONArray("selectedAnswer")
            val selectedAnswer = buildList { if (selected != null) for (i in 0 until selected.length()) add(selected.optString(i)) }
            val startedAt = root.optLong("startedAt", 0L)
            if (startedAt <= 0L) return null
            return PracticeSessionSnapshot(
                startedAt = startedAt,
                practiceMode = root.optString("practiceMode"),
                sourceLabel = root.optString("sourceLabel"),
                scopeType = root.optString("scopeType").ifBlank { null },
                scopeName = root.optString("scopeName").ifBlank { null },
                index = root.optInt("index", 0),
                batchGroupSize = root.optInt("batchGroupSize", 1),
                batchSubmitted = root.optBoolean("batchSubmitted", false),
                batchGroupStartIndex = root.optInt("batchGroupStartIndex", 0),
                shuffleSeed = root.optLong("shuffleSeed", 0L),
                sessionKeys = keys,
                questionsJson = questions.toString(),
                selectedAnswer = selectedAnswer,
                answers = answers,
                drafts = drafts,
                sequentialBankId = root.optString("seqBankId").ifBlank { null },
                sequentialStartIndex = root.optInt("seqStart", -1).takeIf { it >= 0 },
                sequentialUsesRecite = root.optBoolean("seqRecite", false),
                sequentialNextIndex = root.optInt("seqNext", -1).takeIf { it >= 0 },
                updatedAt = root.optLong("updatedAt", startedAt)
            )
        }
    }
}

internal data class SavedAnswer(
    val userAnswer: List<String>,
    val userBlankAnswers: List<String>,
    val correct: Boolean,
    val answerText: String,
    val earnedScore: Double?,
    val maxScore: Double?,
    val autoScored: Boolean,
    val sourceBankId: String?,
    val sourceBankName: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("userAnswer", JSONArray(userAnswer))
        put("userBlankAnswers", JSONArray(userBlankAnswers))
        put("correct", correct)
        put("answerText", answerText)
        put("earnedScore", earnedScore?.let { it } ?: JSONObject.NULL)
        put("maxScore", maxScore?.let { it } ?: JSONObject.NULL)
        put("autoScored", autoScored)
        put("sourceBankId", sourceBankId)
        put("sourceBankName", sourceBankName)
    }

    companion object {
        fun fromJson(item: JSONObject): SavedAnswer {
            val answerArray = item.optJSONArray("userAnswer")
            val blanksArray = item.optJSONArray("userBlankAnswers")
            return SavedAnswer(
                userAnswer = buildList { if (answerArray != null) for (i in 0 until answerArray.length()) add(answerArray.optString(i)) },
                userBlankAnswers = buildList { if (blanksArray != null) for (i in 0 until blanksArray.length()) add(blanksArray.optString(i)) },
                correct = item.optBoolean("correct", false),
                answerText = item.optString("answerText"),
                earnedScore = if (item.isNull("earnedScore")) null else item.optDouble("earnedScore"),
                maxScore = if (item.isNull("maxScore")) null else item.optDouble("maxScore"),
                autoScored = item.optBoolean("autoScored", true),
                sourceBankId = item.optString("sourceBankId").ifBlank { null },
                sourceBankName = item.optString("sourceBankName").ifBlank { null }
            )
        }
    }
}
