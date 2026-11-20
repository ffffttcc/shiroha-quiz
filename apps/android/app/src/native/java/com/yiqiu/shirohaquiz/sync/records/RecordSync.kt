package com.yiqiu.shirohaquiz.sync.records

import java.security.MessageDigest
import java.text.Normalizer

/**
 * 记录类同步的合并内核（纯逻辑：不碰网络、不碰 UI、不碰 Android API）。
 *
 * 三条硬约束（来自方案 v2 与首辅裁断）：
 * 1. `questionKey` **不含库身份**——题库改名不能让事件变孤儿；归属靠事件自带的 `bankHint` 展示。
 * 2. 事件按 `eventId` **求并集**，天然幂等；作答历史只增不减。
 * 3. 裁决**不用墙钟**：删除态用 `(epoch, deviceId)`，`epoch` 是 manifest 单调递增整数。
 */

/** 一次作答 = 一条不可变事件。 */
data class StudyEvent(
    val eventId: String,
    val questionKey: String,
    /** 出事那个库的展示线索 `groupName|name`；同名题跨库共享进度时用它把展示归位。 */
    val bankHint: String,
    val answeredAt: Long,
    val correct: Boolean,
    val source: String = "practice",
    val deviceId: String = ""
)

enum class RecordKind { WRONG, FAVORITE }

/**
 * per-question 状态寄存器（不是事件）：`deleted=true` 表示删除态，`false` 表示存在态。
 * 「删除后又重新作答/收藏」= 写一条 `deleted=false` 且 epoch 更大的记录，即合法复活。
 */
data class Tombstone(
    val kind: RecordKind,
    val targetKey: String,
    val epoch: Long,
    val deviceId: String,
    val deleted: Boolean = true
)

/** 升级前没有事件的历史累计量。只写一次，之后不再变。 */
data class LegacyBaseline(val wrongCount: Int = 0, val rightCount: Int = 0)

data class DerivedCounts(
    val wrongCount: Int,
    val rightCount: Int,
    val streakCorrect: Int,
    val answeredCount: Int
)

data class MergeResult(
    val events: List<StudyEvent>,
    val tombstones: List<Tombstone>
)

/** 内容派生条目键。规范化次序：NFKC → 去零宽 → 空白折叠 → trim（不转小写）。 */
object RecordKey {
    private const val VERSION = "shiroha-rec-v1"
    private val ZERO_WIDTH = setOf('\u200B', '\u200C', '\u200D', '\uFEFF')
    private val WHITESPACE = Regex("\\s+")

    /** 纯文本规范化，可单独单测。 */
    fun normalize(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val builder = StringBuilder(nfkc.length)
        for (ch in nfkc) if (ch !in ZERO_WIDTH) builder.append(ch)
        return builder.toString().replace(WHITESPACE, " ").trim()
    }

    /**
     * @param type        题型（如 SINGLE / JUDGE），只 trim，不改大小写
     * @param stem        题干原文
     * @param optionTexts 选项文本（**不含 Option.key**，排序后参与）
     * @param answers     正确答案（规范化后排序参与）
     */
    fun of(type: String, stem: String, optionTexts: List<String>, answers: List<String>): String {
        val builder = StringBuilder()
        // 长度前缀 + 分隔符：避免拼接歧义，且新增字段不会让历史 hash 漂移。
        builder.field(VERSION)
        builder.field(type.trim())
        builder.field(normalize(stem))
        val options = optionTexts.map(::normalize).sorted()
        builder.field(options.size.toString())
        options.forEach { builder.field(it) }
        val answersNormalized = answers.map(::normalize).sorted()
        builder.field(answersNormalized.size.toString())
        answersNormalized.forEach { builder.field(it) }
        return sha256Hex(builder.toString())
    }

    private fun StringBuilder.field(value: String): StringBuilder =
        append(value.length).append(':').append(value).append('\u0000')

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

object RecordMerge {

    /** 事件并集：按 `eventId` 去重，重复推送天然幂等。输出顺序确定（answeredAt, eventId）。 */
    fun mergeEvents(local: List<StudyEvent>, remote: List<StudyEvent>): List<StudyEvent> {
        val byId = LinkedHashMap<String, StudyEvent>()
        (local + remote).forEach { event -> byId.putIfAbsent(event.eventId, event) }
        return byId.values.sortedWith(compareBy({ it.answeredAt }, { it.eventId }))
    }

    /** 状态寄存器裁决：`(epoch, deviceId)` 大者胜；同 epoch 用 deviceId 字典序破平（两端独立算同结果）。 */
    fun mergeTombstones(local: List<Tombstone>, remote: List<Tombstone>): List<Tombstone> {
        val byTarget = LinkedHashMap<Pair<RecordKind, String>, Tombstone>()
        (local + remote).forEach { tombstone ->
            val slot = tombstone.kind to tombstone.targetKey
            val current = byTarget[slot]
            if (current == null || compareTombstone(tombstone, current) > 0) byTarget[slot] = tombstone
        }
        return byTarget.values.sortedWith(compareBy({ it.kind.name }, { it.targetKey }))
    }

    fun compareTombstone(a: Tombstone, b: Tombstone): Int {
        val byEpoch = a.epoch.compareTo(b.epoch)
        return if (byEpoch != 0) byEpoch else a.deviceId.compareTo(b.deviceId)
    }

    fun merge(local: MergeResult, remote: MergeResult): MergeResult = MergeResult(
        events = mergeEvents(local.events, remote.events),
        tombstones = mergeTombstones(local.tombstones, remote.tombstones)
    )

    /** 当前是否处于删除态（UI 据此隐藏错题/收藏条目）。 */
    fun isDeleted(kind: RecordKind, targetKey: String, tombstones: List<Tombstone>): Boolean =
        tombstones.firstOrNull { it.kind == kind && it.targetKey == targetKey }?.deleted ?: false

    /**
     * 按题目聚合派生计数（跨设备、跨库共享——这是被接受的语义）。
     * 计数 = 事件派生值 + legacyBaseline，**不存任何累计量**。
     */
    fun aggregateByQuestion(
        events: List<StudyEvent>,
        baseline: Map<String, LegacyBaseline> = emptyMap()
    ): Map<String, DerivedCounts> {
        val grouped = events.groupBy { it.questionKey }
        val keys = grouped.keys + baseline.keys
        return keys.associateWith { key ->
            val list = grouped[key].orEmpty()
            val legacy = baseline[key] ?: LegacyBaseline()
            DerivedCounts(
                wrongCount = list.count { !it.correct } + legacy.wrongCount,
                rightCount = list.count { it.correct } + legacy.rightCount,
                streakCorrect = trailingCorrectStreak(list),
                answeredCount = list.size
            )
        }
    }

    /**
     * 按 `bankHint` 再切一层：同题跨库共享计数，但错题展示必须归到出事的那个库，不许串。
     */
    fun aggregateByBankHint(
        events: List<StudyEvent>,
        baseline: Map<String, LegacyBaseline> = emptyMap()
    ): Map<String, Map<String, DerivedCounts>> =
        events.groupBy { it.questionKey }.mapValues { (questionKey, list) ->
            val legacy = baseline[questionKey] ?: LegacyBaseline()
            // baseline 属于「这道题的历史」，无法拆库，按约定挂在空 hint 上单独列出。
            list.groupBy { it.bankHint }.mapValues { (_, perBank) ->
                DerivedCounts(
                    wrongCount = perBank.count { !it.correct },
                    rightCount = perBank.count { it.correct },
                    streakCorrect = trailingCorrectStreak(perBank),
                    answeredCount = perBank.size
                )
            } + mapOf("" to DerivedCounts(legacy.wrongCount, legacy.rightCount, 0, 0))
        }

    /** 挂不上当前题库的事件键：仍参与计数，只是不进「当前题库复习」。 */
    fun orphanKeys(events: List<StudyEvent>, knownQuestionKeys: Set<String>): Set<String> =
        events.map { it.questionKey }.filterNot { it in knownQuestionKeys }.toSet()

    private fun trailingCorrectStreak(events: List<StudyEvent>): Int =
        events.sortedWith(compareBy({ it.answeredAt }, { it.eventId }))
            .takeLastWhile { it.correct }
            .size
}
