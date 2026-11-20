package com.yiqiu.shirohaquiz.sync.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并内核单测：每一类丢数据场景各占一条独立 @Test，不塞 multi-case。
 * 全部为纯 JVM 逻辑，不需要 Robolectric。
 */
class RecordSyncTest {

    private fun event(
        id: String,
        key: String,
        hint: String = "药理|第一章",
        at: Long,
        correct: Boolean,
        device: String = "A"
    ) = StudyEvent(
        eventId = id,
        questionKey = key,
        bankHint = hint,
        answeredAt = at,
        correct = correct,
        deviceId = device
    )

    private fun keyOf(stem: String = "肝药酶诱导剂是？", answers: List<String> = listOf("B")) =
        RecordKey.of("SINGLE", stem, listOf("苯巴比妥", "利福平", "西咪替丁"), answers)

    // ① 并集幂等：同一批事件推两次，结果完全不变
    @Test
    fun mergeIsIdempotentWhenSameBatchPushedTwice() {
        val qk = keyOf()
        val batch = listOf(
            event("e1", qk, at = 100, correct = false),
            event("e2", qk, at = 200, correct = true)
        )
        val once = RecordMerge.mergeEvents(batch, emptyList())
        val twice = RecordMerge.mergeEvents(once, batch)
        assertEquals(2, once.size)
        assertEquals(once, twice)
        assertEquals(RecordMerge.aggregateByQuestion(twice), RecordMerge.aggregateByQuestion(once))
    }

    // ② 同题两端作答：A 答 3 次（1 对 2 错）+ B 答 5 次（2 对 3 错）→ 合计 8，错 5 对 3
    @Test
    fun sameQuestionAnsweredOnBothDevicesCountsEight() {
        val qk = keyOf()
        val local = listOf(
            event("a1", qk, at = 100, correct = true, device = "A"),
            event("a2", qk, at = 110, correct = false, device = "A"),
            event("a3", qk, at = 120, correct = false, device = "A")
        )
        val remote = listOf(
            event("b1", qk, at = 130, correct = true, device = "B"),
            event("b2", qk, at = 140, correct = true, device = "B"),
            event("b3", qk, at = 150, correct = false, device = "B"),
            event("b4", qk, at = 160, correct = false, device = "B"),
            event("b5", qk, at = 170, correct = false, device = "B")
        )
        val merged = RecordMerge.mergeEvents(local, remote)
        assertEquals(8, merged.size)
        val counts = RecordMerge.aggregateByQuestion(merged)[qk]!!
        assertEquals(8, counts.answeredCount)
        assertNotEquals(3, counts.answeredCount)
        assertNotEquals(5, counts.answeredCount)
        assertEquals(5, counts.wrongCount)
        assertEquals(3, counts.rightCount)
    }

    // ③ 同题跨库各答一次：合计 2，且两库展示按 bankHint 不串
    @Test
    fun sameQuestionAcrossBanksCountsTwoAndHintSeparatesPresentation() {
        val qk = keyOf()
        val merged = RecordMerge.mergeEvents(
            listOf(event("e1", qk, hint = "药理|第一章", at = 100, correct = false, device = "A")),
            listOf(event("e2", qk, hint = "药理|第二章", at = 200, correct = true, device = "B"))
        )
        val total = RecordMerge.aggregateByQuestion(merged)[qk]!!
        assertEquals(2, total.answeredCount)

        val byHint = RecordMerge.aggregateByBankHint(merged)[qk]!!
        assertEquals(1, byHint["药理|第一章"]!!.wrongCount)
        assertEquals(0, byHint["药理|第一章"]!!.rightCount)
        assertEquals(0, byHint["药理|第二章"]!!.wrongCount)
        assertEquals(1, byHint["药理|第二章"]!!.rightCount)
    }

    // ④ 删除不复活：A 已删（epoch 5），B 离线未删（epoch 3）→ 合并后仍是删除态
    @Test
    fun deleteDoesNotResurrectFromStaleSide() {
        val qk = keyOf()
        val local = listOf(Tombstone(RecordKind.WRONG, qk, epoch = 5, deviceId = "A", deleted = true))
        val remote = listOf(Tombstone(RecordKind.WRONG, qk, epoch = 3, deviceId = "B", deleted = false))
        val merged = RecordMerge.mergeTombstones(local, remote)
        assertEquals(1, merged.size)
        assertTrue(RecordMerge.isDeleted(RecordKind.WRONG, qk, merged))
    }

    // ⑤ 删除后重新作答 = 合法复活（新状态 epoch 更大）
    @Test
    fun answeringAfterDeleteRevives() {
        val qk = keyOf()
        val merged = RecordMerge.mergeTombstones(
            listOf(Tombstone(RecordKind.WRONG, qk, epoch = 5, deviceId = "A", deleted = true)),
            listOf(Tombstone(RecordKind.WRONG, qk, epoch = 7, deviceId = "B", deleted = false))
        )
        assertFalse(RecordMerge.isDeleted(RecordKind.WRONG, qk, merged))
    }

    // ⑥ 同 epoch 用 deviceId 破平：两端独立算出同一结果（与合并顺序无关）
    @Test
    fun tombstoneTieBreaksByDeviceIdDeterministically() {
        val qk = keyOf()
        val a = Tombstone(RecordKind.FAVORITE, qk, epoch = 9, deviceId = "A", deleted = true)
        val b = Tombstone(RecordKind.FAVORITE, qk, epoch = 9, deviceId = "B", deleted = false)
        val forward = RecordMerge.mergeTombstones(listOf(a), listOf(b))
        val backward = RecordMerge.mergeTombstones(listOf(b), listOf(a))
        assertEquals(forward, backward)
        assertFalse(RecordMerge.isDeleted(RecordKind.FAVORITE, qk, forward))
    }

    // ⑦ 时钟偏移：把一端时间整体 +10 分钟，合并结果与计数都不变（证明不依赖墙钟）
    @Test
    fun clockSkewDoesNotChangeMergeResult() {
        val qk = keyOf()
        val local = listOf(event("a1", qk, at = 100, correct = false, device = "A"))
        val remote = listOf(
            event("b1", qk, at = 200, correct = true, device = "B"),
            event("b2", qk, at = 210, correct = false, device = "B")
        )
        val skew = 10 * 60 * 1000L
        val shifted = remote.map { it.copy(answeredAt = it.answeredAt + skew) }

        val before = RecordMerge.mergeEvents(local, remote)
        val after = RecordMerge.mergeEvents(local, shifted)
        assertEquals(before.map { it.eventId }, after.map { it.eventId })
        assertEquals(RecordMerge.aggregateByQuestion(before), RecordMerge.aggregateByQuestion(after))
    }

    // ⑧ 题库改名：事件不丢、计数不变（证明 qk 里没掺库身份）
    @Test
    fun renamingBankKeepsEventsAndCounts() {
        val before = keyOf(stem = "肝药酶诱导剂是？")
        val after = keyOf(stem = "肝药酶诱导剂是？")
        assertEquals(before, after)  // 键只由内容决定，改名不影响

        val oldEvents = listOf(event("e1", before, hint = "药理|旧名", at = 100, correct = false))
        val renamed = oldEvents.map { it.copy(bankHint = "药理|新名") }
        val merged = RecordMerge.mergeEvents(oldEvents, renamed)
        assertEquals(1, merged.size)  // 同 eventId，不因改名翻倍
        assertEquals(
            RecordMerge.aggregateByQuestion(oldEvents),
            RecordMerge.aggregateByQuestion(renamed)
        )
    }

    // ⑨ legacyBaseline：无事件时计数等于 baseline；有事件则 baseline + 派生
    @Test
    fun legacyBaselineAddsToDerivedCounts() {
        val qk = keyOf()
        val baseline = mapOf(qk to LegacyBaseline(wrongCount = 4, rightCount = 2))
        val empty = RecordMerge.aggregateByQuestion(emptyList(), baseline)[qk]!!
        assertEquals(4, empty.wrongCount)
        assertEquals(2, empty.rightCount)

        val withEvent = RecordMerge.aggregateByQuestion(
            listOf(event("e1", qk, at = 100, correct = true)),
            baseline
        )[qk]!!
        assertEquals(4, withEvent.wrongCount)
        assertEquals(3, withEvent.rightCount)
    }

    // ⑩ 键规范化：全角/零宽/空白折叠/选项顺序 归一，且不转小写
    @Test
    fun keyNormalizationCoversFullWidthZeroWidthAndOptionOrder() {
        val plain = RecordKey.of(
            "SINGLE",
            "肝药酶诱导剂是？",
            listOf("苯巴比妥", "利福平", "西咪替丁"),
            listOf("B")
        )
        val messy = RecordKey.of(
            "SINGLE",
            "　肝药酶诱导剂\u200B是？\uFEFF",
            listOf("西咪替丁", "苯巴比妥", "利福平"),  // 选项顺序被打乱
            listOf("B")
        )
        assertEquals(plain, messy)

        val upper = RecordKey.of("SINGLE", "CYP inducer?", listOf("A"), listOf("A"))
        val lower = RecordKey.of("SINGLE", "cyp inducer?", listOf("A"), listOf("A"))
        assertNotEquals("不转小写，大小写不同即不同题", upper, lower)
    }

    // ⑪ 答案纳入哈希：同题干同选项、答案不同 = 两道题
    @Test
    fun keyDiffersWhenAnswerDiffers() {
        val a = RecordKey.of("SINGLE", "题干", listOf("甲", "乙"), listOf("A"))
        val b = RecordKey.of("SINGLE", "题干", listOf("甲", "乙"), listOf("B"))
        assertNotEquals(a, b)
    }

    // ⑫ 边界：空集合
    @Test
    fun emptyInputsMergeToEmpty() {
        val merged = RecordMerge.merge(MergeResult(emptyList(), emptyList()), MergeResult(emptyList(), emptyList()))
        assertTrue(merged.events.isEmpty())
        assertTrue(merged.tombstones.isEmpty())
        assertTrue(RecordMerge.aggregateByQuestion(emptyList()).isEmpty())
        assertTrue(RecordMerge.orphanKeys(emptyList(), emptySet()).isEmpty())
    }

    // ⑬ 边界：单端为空
    @Test
    fun singleSidedInputSurvives() {
        val qk = keyOf()
        val local = listOf(event("e1", qk, at = 100, correct = false))
        val merged = RecordMerge.merge(MergeResult(local, emptyList()), MergeResult(emptyList(), emptyList()))
        assertEquals(1, merged.events.size)
        assertEquals(1, RecordMerge.aggregateByQuestion(merged.events)[qk]!!.wrongCount)
    }

    // ⑭ 边界：全 tombstone（只有删除记录，无事件）
    @Test
    fun allTombstonesStillReportDeleted() {
        val qk = keyOf()
        val local = listOf(
            Tombstone(RecordKind.WRONG, qk, epoch = 1, deviceId = "A"),
            Tombstone(RecordKind.FAVORITE, qk, epoch = 1, deviceId = "A")
        )
        val merged = RecordMerge.mergeTombstones(local, emptyList())
        assertTrue(RecordMerge.isDeleted(RecordKind.WRONG, qk, merged))
        assertTrue(RecordMerge.isDeleted(RecordKind.FAVORITE, qk, merged))
        assertTrue(RecordMerge.aggregateByQuestion(emptyList(), emptyMap()).isEmpty())
    }

    // ⑮ 孤儿：挂不上当前题库的键仍参与计数
    @Test
    fun orphanKeysStillCount() {
        val known = keyOf()
        val orphan = keyOf(stem = "已被改写的题干")
        val events = listOf(
            event("e1", known, at = 100, correct = true),
            event("e2", orphan, at = 200, correct = false)
        )
        val orphans = RecordMerge.orphanKeys(events, setOf(known))
        assertEquals(setOf(orphan), orphans)
        assertEquals(
            1,
            RecordMerge.aggregateByQuestion(events)[orphan]!!.wrongCount
        )
    }
}
