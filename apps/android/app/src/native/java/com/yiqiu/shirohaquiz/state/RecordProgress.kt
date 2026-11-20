package com.yiqiu.shirohaquiz.state

import android.content.Context
import com.yiqiu.shirohaquiz.sync.records.DerivedCounts
import com.yiqiu.shirohaquiz.sync.records.LegacyBaseline
import com.yiqiu.shirohaquiz.sync.records.RecordEventStore
import com.yiqiu.shirohaquiz.sync.sync.SyncStateStore
import java.io.File

/**
 * 派生计数的**只读缓存**。UI 只调这里，绝不直接读 `events.jsonl`。
 *
 * 缓存失效策略：比对 `events.jsonl` 的 `(size, mtimeMs)` 与 `legacy-baseline.tsv` 的 `size`。
 * 选它而不选「事件计数」的理由：
 * - 事件日志是**追加式**的，每次 append 必改 size 与 mtime，二者任一变化即失效，不会漏；
 * - 数事件条数本身就要把文件读一遍并解析，等于没省下 IO，缓存就失去意义；
 * - baseline 迁移是覆盖写且只发生一次，比对 size 足以发现。
 * 失效重建时调 `RecordEventStore.aggregate()`（复用内核），进程内保留一份 Map，
 * 错题本页排序/渲染会按条目多次取同一个 key，命中缓存是 O(1)。
 */
class RecordProgress private constructor(
    private val store: RecordEventStore,
    private val eventsFile: File,
    private val baselineFile: File
) {
    private data class Stamp(val eventsSize: Long, val eventsMtime: Long, val baselineSize: Long)

    @Volatile
    private var stamp: Stamp? = null

    @Volatile
    private var cache: Map<String, DerivedCounts> = emptyMap()

    fun all(): Map<String, DerivedCounts> {
        val current = stampOf()
        if (current != stamp) {
            cache = store.aggregate()
            stamp = current
        }
        return cache
    }

    fun forKey(questionKey: String): DerivedCounts = all()[questionKey] ?: ZERO

    /**
     * 迁移期一致性探针：比对「旧累计字段」与「派生值」，**只报告不改正**。
     * 迁移或双写出 bug 时，这里会先于用户发现。
     */
    fun mismatches(legacyFields: Map<String, LegacyBaseline>): List<String> {
        val derived = all()
        val keys = derived.keys + legacyFields.keys
        return keys.mapNotNull { key ->
            val expected = derived[key] ?: ZERO
            val actual = legacyFields[key] ?: LegacyBaseline()
            if (expected.wrongCount == actual.wrongCount && expected.rightCount == actual.rightCount) {
                null
            } else {
                "记录计数不一致 key=${key.take(12)}… 派生(wrong=${expected.wrongCount},right=${expected.rightCount}) " +
                    "旧字段(wrong=${actual.wrongCount},right=${actual.rightCount})"
            }
        }.sorted()
    }

    private fun stampOf(): Stamp = Stamp(
        eventsSize = eventsFile.length(),
        eventsMtime = eventsFile.lastModified(),
        baselineSize = baselineFile.length()
    )

    companion object {
        private val ZERO = DerivedCounts(0, 0, 0, 0)

        @Volatile
        private var instance: RecordProgress? = null

        fun forContext(context: Context): RecordProgress =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): RecordProgress {
            val dir = File(context.filesDir, "record-events")
            return RecordProgress(
                store = RecordEventStore.forContext(context),
                eventsFile = File(dir, "events.jsonl"),
                baselineFile = File(dir, "legacy-baseline.tsv")
            )
        }

        /** 测试与独立实例用（同一个目录，自带自己的缓存）。 */
        internal fun forTesting(dir: File, deviceId: String): RecordProgress = RecordProgress(
            store = RecordEventStore(dir, deviceId),
            eventsFile = File(dir, "events.jsonl"),
            baselineFile = File(dir, "legacy-baseline.tsv")
        )

        /** deviceId 透出，供上层构造事件时保持一致（不新增第二个设备标识）。 */
        fun deviceIdOf(context: Context): String = SyncStateStore(context.applicationContext).deviceId()
    }
}
