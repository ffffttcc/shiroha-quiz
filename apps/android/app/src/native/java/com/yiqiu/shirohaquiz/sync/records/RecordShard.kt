package com.yiqiu.shirohaquiz.sync.records

import java.security.MessageDigest

/**
 * 事件分片编解码（纯逻辑，不碰网络）。
 *
 * 分片规则（首辅裁断 2）：每 512 条事件一片，片哈希 = 规范化序列化后的 sha256，
 * 加密 AAD 用**片哈希**（`SyncPayloadCipher.aadForContent(shardHash)`），
 * 于是改一条事件只重加密它所在那一片，不会引起整库重加密。
 *
 * 序列化刻意不用 `org.json`：字段用「长度前缀 + \0」逐个写，输出确定（同内容必同字节），
 * 且能在纯 JVM 单测里跑，不依赖 Android JSON 实现。
 */
object RecordShard {
    const val MAX_EVENTS_PER_SHARD = 512

    /** 按 (questionKey, eventId) 稳定排序后切片，保证两端切出同一批片。 */
    fun chunk(events: List<StudyEvent>): List<List<StudyEvent>> =
        events.sortedWith(compareBy({ it.questionKey }, { it.eventId }))
            .chunked(MAX_EVENTS_PER_SHARD)

    fun encode(events: List<StudyEvent>): ByteArray {
        val builder = StringBuilder()
        builder.field("shiroha-rec-shard-v1")
        events.sortedWith(compareBy({ it.questionKey }, { it.eventId })).forEach { event ->
            builder.append('\n')
            builder.field(event.eventId)
            builder.field(event.questionKey)
            builder.field(event.bankHint)
            builder.field(event.answeredAt.toString())
            builder.field(if (event.correct) "1" else "0")
            builder.field(event.source)
            builder.field(event.deviceId)
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<StudyEvent> {
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split('\n')
        require(lines.isNotEmpty()) { "记录分片为空。" }
        require(FieldReader(lines[0]).next() == "shiroha-rec-shard-v1") { "不是记录分片数据。" }
        return lines.drop(1).filter { it.isNotEmpty() }.map { line ->
            val reader = FieldReader(line)
            StudyEvent(
                eventId = reader.next(),
                questionKey = reader.next(),
                bankHint = reader.next(),
                answeredAt = reader.next().toLong(),
                correct = reader.next() == "1",
                source = reader.next(),
                deviceId = reader.next()
            )
        }
    }

    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** 一片对应一个云端对象名（扁平目录：WebDavClient 的白名单不接受子目录）。 */
    fun objectName(shardHash: String): String = "shiroha-sync-record-$shardHash.enc"

    fun shardHashOf(shard: String): String? =
        shard.removePrefix("shiroha-sync-record-").removeSuffix(".enc")
            .takeIf { it.length == 64 && it.matches(Regex("[a-f0-9]{64}")) }

    private fun StringBuilder.field(value: String): StringBuilder =
        append(value.length).append(':').append(value)

    private class FieldReader(private val source: String) {
        private var index = 0
        fun next(): String {
            val colon = source.indexOf(':', index)
            require(colon > index) { "记录分片字段格式无效。" }
            val length = source.substring(index, colon).toIntOrNull() ?: error("记录分片字段长度无效。")
            val start = colon + 1
            val end = start + length
            require(end <= source.length) { "记录分片字段被截断。" }
            index = end
            return source.substring(start, end)
        }
    }
}
