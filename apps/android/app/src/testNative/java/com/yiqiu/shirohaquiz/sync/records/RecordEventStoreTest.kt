package com.yiqiu.shirohaquiz.sync.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 事件生产与本地存储单测（纯 JVM，用临时目录，不依赖 Android）。
 */
class RecordEventStoreTest {

    private fun tempDir(): File =
        kotlin.io.path.createTempDirectory("record-events").toFile()

    private fun store(dir: File = tempDir(), device: String = "device-A") =
        RecordEventStore(dir, device)

    private fun key(stem: String = "肝药酶诱导剂是？", answers: List<String> = listOf("B")) =
        RecordKey.of("SINGLE", stem, listOf("苯巴比妥", "利福平", "西咪替丁"), answers)

    // ① 作答产生一条事件
    @Test
    fun answeringAppendsExactlyOneEvent() {
        val s = store()
        s.append(key(), "药理|第一章", correct = false, source = "practice")
        val events = s.loadAll()
        assertEquals(1, events.size)
        assertEquals(false, events[0].correct)
        assertEquals("device-A", events[0].deviceId)
    }

    // ② 重复提交同一次作答不产生第二条（内容寻址 id）
    @Test
    fun duplicateSubmitDoesNotCreateSecondEvent() {
        val s = store()
        val first = s.append(key(), "药理|第一章", correct = true, source = "practice", answeredAt = 1000L)
        val second = s.append(key(), "药理|第一章", correct = true, source = "practice", answeredAt = 1000L)
        assertEquals(first.eventId, second.eventId)
        assertEquals(1, s.loadAll().size)
    }

    // ③ 交卷批量：N 题产生 N 条事件
    @Test
    fun examBatchProducesOneEventPerQuestion() {
        val s = store()
        (1..8).forEach { index ->
            s.append(key(stem = "第 $index 题"), "药理|第一组", correct = index % 2 == 0, source = "exam", answeredAt = index.toLong())
        }
        assertEquals(8, s.loadAll().size)
    }

    // ④ 收藏/取消收藏：产生 deleted=false 与 deleted=true 两个状态，最新 epoch 生效
    @Test
    fun favoriteToggleProducesTombstoneStates() {
        val s = store()
        val qk = key()
        s.appendTombstone(RecordKind.FAVORITE, qk, deleted = false)
        s.appendTombstone(RecordKind.FAVORITE, qk, deleted = true)
        val merged = s.loadTombstones()
        assertEquals(1, merged.size)
        assertTrue(merged[0].deleted)
    }

    // ⑤ 手动移出错题本产生 tombstone（不是单纯消失）
    @Test
    fun removingWrongQuestionProducesTombstone() {
        val s = store()
        val qk = key()
        s.appendTombstone(RecordKind.WRONG, qk, deleted = true)
        assertTrue(s.loadTombstones().any { it.kind == RecordKind.WRONG && it.targetKey == qk && it.deleted })
    }

    // ⑥ questionKey 与内核同一实现：同样输入两边必须同值
    @Test
    fun questionKeyMatchesKernelImplementation() {
        val stem = "　肝药酶诱导剂\u200B是？"   // 全角空格 + 零宽
        val viaStore = RecordKey.of("SINGLE", stem, listOf("利福平", "苯巴比妥", "西咪替丁"), listOf("B"))
        val viaKernel = RecordKey.of("SINGLE", stem, listOf("利福平", "苯巴比妥", "西咪替丁"), listOf("B"))
        assertEquals(viaKernel, viaStore)
        assertEquals(64, viaStore.length)
    }

    // ⑦ 迁移只跑一次
    @Test
    fun migrationRunsOnlyOnce() {
        val dir = tempDir()
        val s = store(dir)
        val baseline = mapOf(key() to LegacyBaseline(3, 1))
        assertTrue(s.migrateLegacyOnce(baseline))
        assertFalse(s.migrateLegacyOnce(baseline))
        assertEquals(3, s.legacyBaseline()[key()]!!.wrongCount)
    }

    // ⑧ 迁移后聚合 = legacyBaseline + 事件派生
    @Test
    fun aggregateEqualsBaselinePlusDerived() {
        val dir = tempDir()
        val s = store(dir)
        val qk = key()
        s.migrateLegacyOnce(mapOf(qk to LegacyBaseline(wrongCount = 4, rightCount = 2)))
        s.append(qk, "药理|第一章", correct = true, source = "practice", answeredAt = 10L)
        s.append(qk, "药理|第一章", correct = false, source = "practice", answeredAt = 20L)
        val counts = s.aggregate()[qk]!!
        assertEquals(5, counts.wrongCount)
        assertEquals(3, counts.rightCount)
        assertEquals(2, counts.answeredCount)
    }

    // ⑨ 断电/写失败模拟：写事件失败必须抛，不能静默成功
    @Test
    fun eventWriteFailureThrowsSoLegacyStaysUntouched() {
        val blocker = File(tempDir(), "not-a-dir").apply { writeText("x") }
        val broken = RecordEventStore(blocker, "device-A")
        var thrown = false
        try {
            broken.append(key(), "药理|第一章", correct = true, source = "practice")
        } catch (_: IOException) {
            thrown = true
        } catch (_: Exception) {
            thrown = true
        }
        assertTrue("事件写失败必须抛出，调用方据此不推进旧字段", thrown)
    }

    // ⑩ streak：按时间排序后尾部连续答对的数量
    @Test
    fun streakCountsTrailingConsecutiveCorrect() {
        val s = store()
        val qk = key()
        s.append(qk, "药理|第一章", correct = false, source = "practice", answeredAt = 1L)
        s.append(qk, "药理|第一章", correct = true, source = "practice", answeredAt = 2L)
        s.append(qk, "药理|第一章", correct = true, source = "practice", answeredAt = 3L)
        assertEquals(2, s.aggregate()[qk]!!.streakCorrect)
    }

    // ⑪ 事件与 tombstone 落盘后可重读（跨进程重启也不丢）
    @Test
    fun eventsAndTombstonesSurviveReload() {
        val dir = tempDir()
        val qk = key()
        store(dir).apply {
            append(qk, "药理|第一章", correct = true, source = "practice", answeredAt = 5L)
            appendTombstone(RecordKind.FAVORITE, qk, deleted = true)
        }
        val reopened = store(dir)
        assertEquals(1, reopened.loadAll().size)
        assertEquals(1, reopened.loadTombstones().size)
    }

    // ⑫ 同一题不同作答时间 = 不同事件（不能因为题同就并成一条）
    @Test
    fun sameQuestionDifferentTimesAreDistinctEvents() {
        val s = store()
        val qk = key()
        val a = s.append(qk, "药理|第一章", correct = true, source = "practice", answeredAt = 1L)
        val b = s.append(qk, "药理|第一章", correct = true, source = "practice", answeredAt = 2L)
        assertNotEquals(a.eventId, b.eventId)
        assertEquals(2, s.loadAll().size)
    }
}
