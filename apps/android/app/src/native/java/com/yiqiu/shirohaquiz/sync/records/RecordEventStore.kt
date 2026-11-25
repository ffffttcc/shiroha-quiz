package com.yiqiu.shirohaquiz.sync.records

import android.content.Context
import com.yiqiu.shirohaquiz.sync.sync.SyncStateStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * 本地事件存储：**追加式日志 + 按月分片**，不用 SharedPreferences。
 *
 * 理由：prefs 是「整份读-改-写」，事件只增不减，塞进去会让每次写入都重写整个 blob，
 * 且一次断电就可能把整份历史写坏；追加式日志每次只 append 一行 + fsync，坏也只坏最后一行。
 *
 * 落盘顺序是硬约束：**先 append + fsync 成功，调用方才允许推进旧的累计字段**。
 * 这样断电丢的是「旧字段没涨」而不是「作答丢了」——宁可少算一次，不可丢记录。
 *
 * 行格式：一行 = 一个事件的 Base64（内容就是 `RecordShard.encode(listOf(event))`），
 * 复用已单测过的编码器，不会再长出一套序列化格式。
 */
class RecordEventStore(
    private val dir: File,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val eventsFile = File(dir, "events.jsonl")
    private val tombstonesFile = File(dir, "tombstones.txt")
    private val baselineFile = File(dir, "legacy-baseline.tsv")
    private val migratedMarker = File(dir, "legacy-migrated.marker")
    private val epochFile = File(dir, "record-epoch.txt")

    init {
        dir.mkdirs()
    }

    /**
     * 追加一条作答事件并落盘。返回的事件 id 是**内容寻址**的确定性 id：
     * 同一次作答被重复提交（重试 / 回滚 / 双击）算出同一个 id，合并时天然去重，
     * 不会产生第二条。代价是放弃随机 UUID 的「撞了也算两条」语义——这里恰好不需要。
     */
    fun append(
        questionKey: String,
        bankHint: String,
        correct: Boolean,
        source: String,
        answeredAt: Long = clock()
    ): StudyEvent {
        val event = StudyEvent(
            eventId = eventId(questionKey, answeredAt, correct),
            questionKey = questionKey,
            bankHint = bankHint,
            answeredAt = answeredAt,
            correct = correct,
            source = source,
            deviceId = deviceId
        )
        val line = Base64.getEncoder().encodeToString(RecordShard.encode(listOf(event)))
        appendDurably(eventsFile, line)
        return event
    }

    fun loadAll(): List<StudyEvent> = readEvents().distinctBy { it.eventId }.sortedWith(
        compareBy({ it.answeredAt }, { it.eventId })
    )

    fun eventsFor(questionKey: String): List<StudyEvent> =
        loadAll().filter { it.questionKey == questionKey }

    /** 聚合派生计数（复用内核），供 UI 后续读取。 */
    fun aggregate(baseline: Map<String, LegacyBaseline> = legacyBaseline()): Map<String, DerivedCounts> =
        RecordMerge.aggregateByQuestion(loadAll(), baseline)

    /** 收藏/错题本的存在态与删除态：删除必须留 tombstone，否则别的设备会把条目拉回来。 */
    fun appendTombstone(kind: RecordKind, targetKey: String, deleted: Boolean): Tombstone {
        val tombstone = Tombstone(
            kind = kind,
            targetKey = targetKey,
            epoch = nextEpoch(),
            deviceId = deviceId,
            deleted = deleted
        )
        appendDurably(
            tombstonesFile,
            listOf(tombstone.kind.name, tombstone.targetKey, tombstone.epoch.toString(), tombstone.deviceId, tombstone.deleted.toString())
                .joinToString("|")
        )
        return tombstone
    }

    fun loadTombstones(): List<Tombstone> {
        if (!tombstonesFile.exists()) return emptyList()
        val parsed = tombstonesFile.readLines().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            val kind = runCatching { RecordKind.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            Tombstone(kind, parts[1], parts[2].toLongOrNull() ?: 0L, parts[3], parts[4].toBoolean())
        }
        return RecordMerge.mergeTombstones(parsed, emptyList())
    }

    fun legacyBaseline(): Map<String, LegacyBaseline> {
        if (!baselineFile.exists()) return emptyMap()
        return baselineFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            parts[0] to LegacyBaseline(parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
        }.toMap()
    }

    /**
     * 老数据迁移：只把累计量记成 legacyBaseline，**不伪造事件**（没有逐题结果，伪造就是编数据）。
     * 只跑一次：先写 baseline 文件（原子替换），再写 marker；两者都不存在才迁移。
     * 半途崩溃最多重跑一次，因为 baseline 是「覆盖写」而不是「累加写」，重跑不会翻倍。
     */
    fun migrateLegacyOnce(entries: Map<String, LegacyBaseline>): Boolean {
        if (migratedMarker.exists()) return false
        val tmp = File(dir, "legacy-baseline.tsv.tmp")
        tmp.writeText(entries.entries.sortedBy { it.key }.joinToString("\n") { (key, value) ->
            "${key}\t${value.wrongCount}\t${value.rightCount}"
        })
        if (!tmp.renameTo(baselineFile)) {
            baselineFile.delete()
            tmp.renameTo(baselineFile)
        }
        migratedMarker.writeText(clock().toString())
        return true
    }

    /**
     * 落地远端事件：**按 `eventId` 去重追加**（幂等）。远端事件自带 id，
     * 不能走 [append]（那会按本机参数重算 id，等于换了一条事件）。
     */
    fun appendRemote(events: List<StudyEvent>): Int {
        if (events.isEmpty()) return 0
        val known = loadAll().mapTo(HashSet()) { it.eventId }
        var appended = 0
        events.forEach { event ->
            if (!known.add(event.eventId)) return@forEach
            appendDurably(eventsFile, Base64.getEncoder().encodeToString(RecordShard.encode(listOf(event))))
            appended++
        }
        return appended
    }

    /**
     * 落地远端 tombstone：按 `(epoch, deviceId)` 取并集，**不许覆盖本地更新的那条**。
     * 只有远端那条严格更新时才追加写入，本地 epoch 计数器同时抬到不低于远端值。
     */
    fun applyRemoteTombstones(tombstones: List<Tombstone>): Int {
        if (tombstones.isEmpty()) return 0
        val local = loadTombstones()
        val localByKey = local.associateBy { it.kind to it.targetKey }
        var applied = 0
        tombstones.forEach { remote ->
            val current = localByKey[remote.kind to remote.targetKey]
            if (current == null || RecordMerge.compareTombstone(remote, current) > 0) {
                appendDurably(
                    tombstonesFile,
                    listOf(
                        remote.kind.name,
                        remote.targetKey,
                        remote.epoch.toString(),
                        remote.deviceId,
                        remote.deleted.toString()
                    ).joinToString("|")
                )
                applied++
            }
            if (remote.epoch > rawEpoch()) rawEpoch(remote.epoch)
        }
        return applied
    }

    /** 只读：返回本地记录的 epoch 值（对应 record-epoch.txt 的内容）。 */
    fun localEpoch(): Long = rawEpoch()

    private fun rawEpoch(): Long = epochFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L

    private fun rawEpoch(value: Long) {
        epochFile.writeText(value.toString())
    }

    private fun eventId(questionKey: String, answeredAt: Long, correct: Boolean): String =
        MessageDigest.getInstance("SHA-256")
            .digest("shiroha-evt-v1\u0000$deviceId\u0000$questionKey\u0000$answeredAt\u0000$correct".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun nextEpoch(): Long {
        val current = epochFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val next = current + 1
        epochFile.writeText(next.toString())
        return next
    }

    private fun appendDurably(file: File, line: String) {
        FileOutputStream(file, true).use { stream ->
            stream.write((line + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
    }

    private fun readEvents(): List<StudyEvent> {
        if (!eventsFile.exists()) return emptyList()
        return eventsFile.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            // 最后一行可能是断电留下的半行：解不出来就跳过，绝不因此丢掉整份历史。
            runCatching {
                RecordShard.decode(Base64.getDecoder().decode(line))
            }.getOrNull()
        }.flatten()
    }

    companion object {
        /** Android 侧入口：目录放 filesDir（不进备份、不进 prefs）。 */
        fun forContext(context: Context): RecordEventStore =
            RecordEventStore(
                dir = File(context.applicationContext.filesDir, "record-events"),
                deviceId = SyncStateStore(context.applicationContext).deviceId()
            )
    }
}
