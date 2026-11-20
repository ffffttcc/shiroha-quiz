package com.yiqiu.shirohaquiz.sync.records

import com.yiqiu.shirohaquiz.sync.sync.SyncBankRecord
import com.yiqiu.shirohaquiz.sync.sync.SyncManifest
import com.yiqiu.shirohaquiz.sync.sync.SyncManifestCodec
import com.yiqiu.shirohaquiz.sync.sync.SyncObjects
import com.yiqiu.shirohaquiz.sync.sync.SyncPayloadCipher
import com.yiqiu.shirohaquiz.sync.sync.SyncPlan
import com.yiqiu.shirohaquiz.sync.sync.SyncPlanner
import com.yiqiu.shirohaquiz.sync.sync.SyncedBankMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 传输层单测：分片切分/哈希稳定/AAD 片级、planner 的 tombstone 挡复活、manifest v1→v2 兼容。
 * manifest 编解码用 org.json，故整体跑 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordTransportTest {

    private fun events(count: Int): List<StudyEvent> = (1..count).map { index ->
        StudyEvent(
            eventId = "e%05d".format(index),
            questionKey = "%064x".format(index),
            bankHint = "药理|第一章",
            answeredAt = index.toLong(),
            correct = index % 2 == 0,
            deviceId = "A"
        )
    }

    // ① 511 条 → 1 片
    @Test
    fun chunkOf511IsSingleShard() {
        assertEquals(1, RecordShard.chunk(events(511)).size)
    }

    // ② 512 条 → 1 片（刚好满片）
    @Test
    fun chunkOf512IsSingleShard() {
        val chunks = RecordShard.chunk(events(512))
        assertEquals(1, chunks.size)
        assertEquals(512, chunks[0].size)
    }

    // ③ 513 条 → 2 片（512 + 1）
    @Test
    fun chunkOf513SplitsIntoTwoShards() {
        val chunks = RecordShard.chunk(events(513))
        assertEquals(2, chunks.size)
        assertEquals(512, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    // ④ 片哈希稳定：输入顺序不同，切出的字节与哈希必须相同
    @Test
    fun shardHashIsStableUnderInputOrder() {
        val forward = RecordShard.hash(RecordShard.encode(events(20)))
        val shuffled = RecordShard.hash(RecordShard.encode(events(20).reversed()))
        assertEquals(forward, shuffled)
        assertEquals(64, forward.length)
    }

    // ⑤ 编解码往返
    @Test
    fun encodeDecodeRoundTrip() {
        val source = events(30)
        val decoded = RecordShard.decode(RecordShard.encode(source))
        assertEquals(source.sortedBy { it.eventId }, decoded.sortedBy { it.eventId })
    }

    // ⑥ AAD 绑的是片哈希（不是整库）：换个哈希就解不开
    @Test
    fun shardAadIsShardHashNotBankHash() {
        val payload = RecordShard.encode(events(5))
        val shardHash = RecordShard.hash(payload)
        val encrypted = SyncPayloadCipher.encrypt(
            payload, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789", SyncPayloadCipher.aadForContent(shardHash)
        )
        val plain = SyncPayloadCipher.decrypt(
            encrypted, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789", SyncPayloadCipher.aadForContent(shardHash)
        )
        assertEquals(payload.toList(), plain.toList())

        val otherHash = "%064x".format(999)
        assertThrows(Exception::class.java) {
            SyncPayloadCipher.decrypt(encrypted, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789", SyncPayloadCipher.aadForContent(otherHash))
        }
    }

    // ⑦ 分片对象名与 SyncObjects 白名单一致（防两处命名漂移）
    @Test
    fun shardObjectNameMatchesSyncObjectsWhitelist() {
        val hash = "%064x".format(7)
        assertEquals(SyncObjects.recordName(hash), RecordShard.objectName(hash))
        assertTrue(SyncObjects.isSyncObject(RecordShard.objectName(hash)))
        assertEquals(hash, RecordShard.shardHashOf(RecordShard.objectName(hash)))
    }

    // ⑧ planner：被 tombstone 删除的库，远端还在也不许 DOWNLOAD（修复活）
    @Test
    fun plannerSkipsDownloadForTombstonedBank() {
        val key = "药理|第一章"
        val remote = mapOf(key to SyncBankRecord("药理", "第一章", "%064x".format(1), 100L))
        val tombstone = Tombstone(RecordKind.BANK, key, epoch = 5, deviceId = "A", deleted = true)
        val plan: SyncPlan = SyncPlanner.plan(
            localBanks = emptyList(),
            remoteBanks = remote,
            baseline = emptyMap(),
            bankTombstones = listOf(tombstone)
        )
        assertTrue(plan.downloads.isEmpty())
    }

    // ⑨ planner：没有 tombstone 时行为不变，仍会 DOWNLOAD（证明修复是精准的，不是把功能关掉）
    @Test
    fun plannerStillDownloadsWithoutTombstone() {
        val key = "药理|第一章"
        val remote = mapOf(key to SyncBankRecord("药理", "第一章", "%064x".format(1), 100L))
        val plan = SyncPlanner.plan(emptyList(), remote, emptyMap(), listOf())
        assertEquals(1, plan.downloads.size)
        assertEquals(key, plan.downloads[0].key)
    }

    // ⑩ manifest v1（无 v2 字段）必须能读，缺字段按默认
    @Test
    fun manifestV1JsonIsReadableWithDefaults() {
        val legacy = """{"version":1,"deviceId":"dev-1","updatedAt":123,
            "banks":{"药理|第一章":{"groupName":"药理","name":"第一章","contentHash":"${"%064x".format(1)}","updatedAt":100}},
            "recordSnapshots":{}}""".toByteArray()
        val manifest = SyncManifestCodec.fromJson(legacy)
        assertEquals(1, manifest.version)
        assertEquals(0L, manifest.epoch)
        assertTrue(manifest.recordShards.isEmpty())
        assertTrue(manifest.bankTombstones.isEmpty())
        assertTrue(manifest.recordTombstones.isEmpty())
        assertEquals(1, manifest.banks.size)
    }

    // ⑪ manifest v2 往返：epoch / 分片 / 双向 tombstone 都能保住
    @Test
    fun manifestV2RoundTripsNewFields() {
        val shard = "%064x".format(42)
        val manifest = SyncManifest(
            deviceId = "dev-2",
            updatedAt = 200L,
            banks = emptyMap(),
            epoch = 17L,
            recordShards = listOf(shard),
            bankTombstones = listOf(Tombstone(RecordKind.BANK, "药理|旧库", 9L, "A", true)),
            recordTombstones = listOf(Tombstone(RecordKind.WRONG, "%064x".format(3), 11L, "B", false))
        )
        val decoded = SyncManifestCodec.fromJson(SyncManifestCodec.toJson(manifest))
        assertEquals(SyncManifest.CURRENT_VERSION, decoded.version)
        assertEquals(17L, decoded.epoch)
        assertEquals(listOf(shard), decoded.recordShards)
        assertEquals(1, decoded.bankTombstones.size)
        assertTrue(decoded.bankTombstones[0].deleted)
        assertEquals(1, decoded.recordTombstones.size)
        assertFalse(decoded.recordTombstones[0].deleted)
    }

    // ⑫ 加密后的 manifest 能解回（AAD=manifest 规则未被记录字段破坏）
    @Test
    fun encryptedManifestStillDecrypts() {
        val manifest = SyncManifest("dev-3", 300L, emptyMap(), epoch = 5L, recordShards = listOf("%064x".format(8)))
        val encrypted = SyncManifestCodec.encrypt(manifest, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        val decrypted = SyncManifestCodec.decrypt(encrypted, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        assertEquals(5L, decrypted.epoch)
        assertEquals(1, decrypted.recordShards.size)
    }
}
