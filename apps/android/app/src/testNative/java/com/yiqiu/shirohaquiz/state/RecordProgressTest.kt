package com.yiqiu.shirohaquiz.state

import com.yiqiu.shirohaquiz.sync.records.DerivedCounts
import com.yiqiu.shirohaquiz.sync.records.LegacyBaseline
import com.yiqiu.shirohaquiz.sync.records.RecordEventStore
import com.yiqiu.shirohaquiz.sync.records.RecordKey
import com.yiqiu.shirohaquiz.sync.records.RecordMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 派生计数缓存单测（纯 JVM）：读法与内核一致、缓存按文件变化失效、baseline 参与、探针只报不改。
 */
class RecordProgressTest {

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("record-progress").toFile()

    private fun key(stem: String = "肝药酶诱导剂是？", answers: List<String> = listOf("B")) =
        RecordKey.of("SINGLE", stem, listOf("苯巴比妥", "利福平", "西咪替丁"), answers)

    private fun store(dir: File) = RecordEventStore(dir, "device-A")

    // ① 缓存读到的值与内核 aggregateByQuestion 完全一致
    @Test
    fun progressMatchesKernelAggregation() {
        val dir = tempDir()
        val s = store(dir)
        val qk = key()
        s.migrateLegacyOnce(mapOf(qk to LegacyBaseline(2, 1)))
        s.append(qk, "药理|第一章", correct = false, source = "practice", answeredAt = 1L)
        s.append(qk, "药理|第一章", correct = true, source = "practice", answeredAt = 2L)

        val viaCache = RecordProgress.forTesting(dir, "device-A").all()
        val viaKernel = RecordMerge.aggregateByQuestion(s.loadAll(), s.legacyBaseline())
        assertEquals(viaKernel, viaCache)
        assertEquals(3, viaCache[qk]!!.wrongCount)
        assertEquals(2, viaCache[qk]!!.rightCount)
    }

    // ② 缓存失效正确：追加一条事件后，聚合随之变化（不是旧值）
    @Test
    fun cacheInvalidatesAfterAppendingEvent() {
        val dir = tempDir()
        val s = store(dir)
        val qk = key()
        val progress = RecordProgress.forTesting(dir, "device-A")

        s.append(qk, "药理|第一章", correct = false, source = "practice", answeredAt = 1L)
        val before = progress.forKey(qk).wrongCount
        assertEquals(1, before)

        s.append(qk, "药理|第一章", correct = false, source = "practice", answeredAt = 2L)
        val after = progress.forKey(qk).wrongCount
        assertEquals(2, after)
        assertNotEquals(before, after)
    }

    // ③ baseline 参与：只有老数据、没有事件时，派生值也必须等于 baseline
    @Test
    fun baselineParticipatesWithoutAnyEvent() {
        val dir = tempDir()
        val s = store(dir)
        val qk = key()
        s.migrateLegacyOnce(mapOf(qk to LegacyBaseline(wrongCount = 7, rightCount = 3)))
        val counts = RecordProgress.forTesting(dir, "device-A").forKey(qk)
        assertEquals(7, counts.wrongCount)
        assertEquals(3, counts.rightCount)
        assertEquals(0, counts.answeredCount)
    }

    // ④ 未出现过的题返回全零，不抛
    @Test
    fun unknownQuestionReturnsZero() {
        val dir = tempDir()
        store(dir).append(key(), "药理|第一章", correct = true, source = "practice", answeredAt = 1L)
        val counts = RecordProgress.forTesting(dir, "device-A").forKey(key(stem = "从未作答的题"))
        assertEquals(DerivedCounts(0, 0, 0, 0), counts)
    }

    // ⑤ 一致性探针：不一致时只返回报告，不抛异常、不改数据
    @Test
    fun mismatchProbeReportsWithoutThrowingOrFixing() {
        val dir = tempDir()
        val s = store(dir)
        val qk = key()
        s.append(qk, "药理|第一章", correct = false, source = "practice", answeredAt = 1L)
        val progress = RecordProgress.forTesting(dir, "device-A")

        // 故意给一份与派生值不同的「旧字段」快照
        val stale = mapOf(qk to LegacyBaseline(wrongCount = 99, rightCount = 0))
        val issues = progress.mismatches(stale)
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("不一致"))
        // 探针不改数据：派生值仍是真实值
        assertEquals(1, progress.forKey(qk).wrongCount)
    }

    // ⑥ 一致时探针安静（空报告）
    @Test
    fun probeIsSilentWhenConsistent() {
        val dir = tempDir()
        val s = store(dir)
        val qk = key()
        s.append(qk, "药理|第一章", correct = false, source = "practice", answeredAt = 1L)
        val progress = RecordProgress.forTesting(dir, "device-A")
        val consistent = mapOf(qk to LegacyBaseline(wrongCount = 1, rightCount = 0))
        assertTrue(progress.mismatches(consistent).isEmpty())
    }

    // ⑦ baseline 文件出现（迁移）也要触发一次重建
    @Test
    fun migratingBaselineAfterFirstReadIsPickedUp() {
        val dir = tempDir()
        val s = store(dir)
        val qk = key()
        val progress = RecordProgress.forTesting(dir, "device-A")
        assertEquals(0, progress.forKey(qk).wrongCount)

        s.migrateLegacyOnce(mapOf(qk to LegacyBaseline(5, 0)))
        assertEquals(5, progress.forKey(qk).wrongCount)
    }
}
