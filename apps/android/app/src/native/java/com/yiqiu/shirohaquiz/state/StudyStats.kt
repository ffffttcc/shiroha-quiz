package com.yiqiu.shirohaquiz.state

/**
 * 按题库（= 章节，本应用一章一个库）聚合的正确率统计。
 *
 * 数据全部来自 [StudyRecord.questionResults]，不新增字段、不写备份、不解析文本。
 * **口径与记录页一致**：accuracy = correct * 100 / total（整数除法）；
 * correct 只计客观题判对（autoScored && correct），total 计入该库全部做题明细。
 */
data class BankAccuracyRow(
    val bankId: String,
    val bankName: String,
    val groupName: String,
    val answered: Int,
    val correct: Int,
    val lastTimestamp: Long
) {
    val accuracy: Int get() = if (answered <= 0) 0 else correct * 100 / answered
}

object StudyStats {

    /** 既没有 sourceBankId 也没有 record.bankId 的明细归到这里。 */
    const val UNASSIGNED_BANK_KEY = "__unassigned__"
    const val UNASSIGNED_GROUP_NAME = "未归属/已移除"

    fun byBank(records: List<StudyRecord>, banks: List<QuizBank>): List<BankAccuracyRow> {
        if (records.isEmpty()) return emptyList()
        val bankById = banks.associateBy { it.id }
        val accumulated = LinkedHashMap<String, Accumulator>()
        records.forEach { record ->
            val results = record.questionResults
            if (results.isEmpty()) {
                // 老记录没有逐题明细：按记录自身归属，保证与记录页的总题数一致。
                val key = record.bankId ?: UNASSIGNED_BANK_KEY
                accumulated.getOrPut(key) { Accumulator(record.bankName) }
                    .add(record.total, record.correct, record.timestamp)
            } else {
                results.forEach { result ->
                    val key = result.sourceBankId ?: record.bankId ?: UNASSIGNED_BANK_KEY
                    val name = result.sourceBankName ?: record.bankName
                    accumulated.getOrPut(key) { Accumulator(name) }
                        .add(1, if (result.autoScored && result.correct) 1 else 0, record.timestamp)
                }
            }
        }
        return accumulated
            .map { (bankId, acc) ->
                val bank = bankById[bankId]
                BankAccuracyRow(
                    bankId = bankId,
                    bankName = bank?.name?.takeIf { it.isNotBlank() }
                        ?: acc.name.ifBlank { "未命名题库" },
                    groupName = bank?.groupName?.takeIf { it.isNotBlank() }
                        ?: UNASSIGNED_GROUP_NAME,
                    answered = acc.answered,
                    correct = acc.correct,
                    lastTimestamp = acc.lastTimestamp
                )
            }
            .sortedWith(compareBy({ it.groupName }, { it.bankName }))
    }

    /** 全部题库合计（用于统计块顶部的总览行）。 */
    fun totals(rows: List<BankAccuracyRow>): Pair<Int, Int> =
        rows.sumOf { it.answered } to rows.sumOf { it.correct }

    private class Accumulator(val name: String) {
        var answered: Int = 0
        var correct: Int = 0
        var lastTimestamp: Long = 0L

        fun add(answeredDelta: Int, correctDelta: Int, timestamp: Long) {
            answered += answeredDelta
            correct += correctDelta
            if (timestamp > lastTimestamp) lastTimestamp = timestamp
        }
    }
}
