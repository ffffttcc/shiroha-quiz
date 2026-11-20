package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.sync.records.RecordKind
import com.yiqiu.shirohaquiz.sync.records.RecordMerge
import com.yiqiu.shirohaquiz.sync.records.RecordShard
import com.yiqiu.shirohaquiz.sync.records.StudyEvent
import com.yiqiu.shirohaquiz.sync.records.Tombstone
import com.yiqiu.shirohaquiz.sync.webdav.WebDavClient
import com.yiqiu.shirohaquiz.sync.webdav.WebDavException

/**
 * 同步 / 恢复的**单写者闸门**。
 *
 * 两者都会改本地记录与同步 baseline，并发执行会互相踩。这里用二进制信号量而不是
 * ReentrantLock：恢复是挂起函数，`withContext` 可能把续体调度到别的线程，
 * 跨线程释放 ReentrantLock 会抛 IllegalMonitorStateException；信号量允许跨线程释放。
 *
 * 引擎的 [SyncEngine.run] 是同步阻塞 API，用 [withLock]；恢复流程用 acquire/release 配对。
 */
object SyncWriteGate {
    private val semaphore = java.util.concurrent.Semaphore(1)

    fun acquire() = semaphore.acquire()

    fun release() = semaphore.release()

    fun <T> withLock(block: () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
    }
}

/**
 * 单库 + 记录类增量同步。
 *
 * 提交顺序是硬约束：**先下载并解密校验 → 写本地 → 先 PUT 全部变更的记录分片 → 最后 PUT manifest**。
 * 反过来会出现「manifest 说有、实际没有」。
 *
 * 记录分片读取失败（404 / 解密失败）**不抛死整轮**：跳过该片、保留旧 manifest、记 last_error，下轮重试。
 */
class SyncEngine(
    private val client: WebDavClient,
    private val recoveryCode: String,
    private val state: SyncStateStore,
    private val localBanks: () -> List<QuizBank>,
    private val applyRemoteBank: (groupName: String, name: String, questions: List<Question>) -> Unit,
    private val renameLocalBank: (bankId: String, newName: String) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val localEvents: () -> List<StudyEvent> = { emptyList() },
    private val applyRemoteEvents: (List<StudyEvent>) -> Unit = {},
    private val localTombstones: () -> List<Tombstone> = { emptyList() },
    private val applyRemoteTombstones: (List<Tombstone>) -> Unit = {}
) {
    data class Report(
        val ok: Boolean,
        val uploaded: Int,
        val downloaded: Int,
        val conflicts: Int,
        val message: String,
        val recordUploaded: Int = 0,
        val recordDownloaded: Int = 0
    )

    private data class RecordOutcome(
        val shards: List<String>,
        val uploaded: Int,
        val downloaded: Int,
        val failure: String?
    )

    fun run(): Report = SyncWriteGate.withLock { runLocked() }

    private fun runLocked(): Report {
        val at = clockMillis()
        return try {
            val baseline = state.bankMeta()
            val remoteManifest = fetchManifest() ?: SyncManifest(state.deviceId(), at, emptyMap())
            val forceUpload = state.forceUploadLocal()
            val plan = SyncPlanner.plan(
                localBanks = localBanks(),
                remoteBanks = remoteManifest.banks,
                baseline = baseline,
                bankTombstones = remoteManifest.bankTombstones,
                forceUploadLocal = forceUpload
            )

            // ① 先把所有要落地的远端版本下载 + 解密 + 解析完（此时还没动本地数据）
            val pending = mutableMapOf<String, DecodedSyncBank>()
            plan.downloads.forEach { entry ->
                pending[entry.key] = fetchBank(entry.remoteHash!!)
            }
            val conflictOutcome = mutableListOf<Pair<SyncPlanEntry, Boolean>>()
            val decisionByName = mutableMapOf<String, SyncConflictDecision>()
            plan.conflicts.forEach { entry ->
                val decision = SyncConflict.decide(
                    key = entry.key,
                    remoteUpdatedAt = entry.remote?.updatedAt ?: 0L,
                    localConfirmedAt = baseline[entry.key]?.updatedAt ?: 0L,
                    now = at
                )
                pending[entry.key] = fetchBank(entry.remoteHash!!)
                decisionByName[entry.key] = decision
                conflictOutcome += entry to decision.remoteWins
            }

            // ② 写本地：普通下载直接覆盖（保留本地 id）；冲突把败者留成副本
            var downloaded = 0
            plan.downloads.forEach { entry ->
                val decoded = pending.getValue(entry.key)
                applyRemoteBank(decoded.groupName, decoded.name, decoded.questions)
                downloaded++
            }
            var conflicts = 0
            val uploadsNeeded = plan.uploads.toMutableList()
            conflictOutcome.forEach { (entry, remoteWins) ->
                val decoded = pending.getValue(entry.key)
                val copyName = decisionByName.getValue(entry.key).copyName
                if (remoteWins) {
                    renameLocalBank(entry.localBank!!.id, copyName)
                    applyRemoteBank(decoded.groupName, decoded.name, decoded.questions)
                } else {
                    applyRemoteBank(decoded.groupName, copyName, decoded.questions)
                    uploadsNeeded += entry
                }
                conflicts++
            }

            // ③ 记录类：先合 tombstone，再同步事件分片（分片对象必须在 manifest 之前落地）
            val bankTombstones = mergedBankTombstones(remoteManifest, baseline, at)
            val recordTombstones = RecordMerge.mergeTombstones(
                localTombstones(), remoteManifest.recordTombstones
            )
            if (recordTombstones != remoteManifest.recordTombstones) applyRemoteTombstones(recordTombstones)
            val record = syncRecordShards(remoteManifest)

            // ④ 上传题库 blob
            var uploaded = 0
            uploadsNeeded.forEach { entry ->
                val bank = entry.localBank ?: return@forEach
                val hash = SyncIdentity.contentHash(bank)
                val payload = SyncBankCodec.encode(SyncIdentity.normalizeGroupName(bank.groupName), bank.name, bank.questions)
                val encrypted = SyncPayloadCipher.encrypt(payload, recoveryCode, SyncPayloadCipher.aadForContent(hash))
                client.putSyncObject(SyncObjects.bankName(hash), encrypted)
                uploaded++
            }

            // ⑤ manifest 必须最后写；record 分片读失败时**不推进** manifest，保留旧值等下轮
            val changed = uploaded + downloaded + conflicts > 0 ||
                record.uploaded + record.downloaded > 0 ||
                bankTombstones != remoteManifest.bankTombstones ||
                recordTombstones != remoteManifest.recordTombstones
            val nextEpoch = maxOf(state.epoch(), remoteManifest.epoch) + if (changed) 1L else 0L

            if (record.failure == null) {
                val merged = buildManifest(
                    at = at,
                    baseline = baseline,
                    remoteManifest = remoteManifest,
                    epoch = nextEpoch,
                    recordShards = record.shards,
                    bankTombstones = bankTombstones,
                    recordTombstones = recordTombstones
                )
                if (changed || remoteManifest.version < SyncManifest.CURRENT_VERSION) {
                    client.putSyncObject(SyncObjects.MANIFEST, SyncManifestCodec.encrypt(merged, recoveryCode))
                }
                state.advanceEpoch(nextEpoch)
                state.setForceUploadLocal(false)
                state.markSynced(
                    at,
                    "上传 $uploaded / 下载 $downloaded / 冲突 $conflicts / 记录片 +${record.uploaded} -${record.downloaded} · 成功",
                    metaOf(merged)
                )
                runCatching { collectGarbage(merged) }
                Report(
                    ok = true,
                    uploaded = uploaded,
                    downloaded = downloaded,
                    conflicts = conflicts,
                    message = message(uploaded, downloaded, conflicts),
                    recordUploaded = record.uploaded,
                    recordDownloaded = record.downloaded
                )
            } else {
                // 分片级「部分成功」：已上传的分片有效但 manifest 不前进，避免指向缺失对象。
                state.recordFailure("记录分片同步失败：${record.failure}", at)
                state.markSynced(
                    at,
                    "记录分片失败，manifest 未推进（已传 ${record.uploaded} 片）",
                    baseline
                )
                Report(
                    ok = false,
                    uploaded = uploaded,
                    downloaded = downloaded,
                    conflicts = conflicts,
                    message = "记录分片同步失败，已保留旧清单：${record.failure}",
                    recordUploaded = record.uploaded,
                    recordDownloaded = record.downloaded
                )
            }
        } catch (error: Exception) {
            Report(false, 0, 0, 0, error.message ?: "同步失败，本地数据未改动。")
        }
    }

    private fun message(uploaded: Int, downloaded: Int, conflicts: Int): String =
        if (uploaded + downloaded + conflicts == 0) "已是最新" else "上传 $uploaded / 下载 $downloaded / 冲突 $conflicts"

    private fun fetchManifest(): SyncManifest? =
        client.getSyncObject(SyncObjects.MANIFEST)?.let { SyncManifestCodec.decrypt(it, recoveryCode) }

    private fun fetchBank(contentHash: String): DecodedSyncBank {
        val raw = client.getSyncObject(SyncObjects.bankName(contentHash))
            ?: throw WebDavException("云端缺少题库数据（$contentHash），已放弃本次同步。")
        val plain = SyncPayloadCipher.decrypt(raw, recoveryCode, SyncPayloadCipher.aadForContent(contentHash))
        return SyncBankCodec.decode(plain)
    }

    /**
     * 本地删掉的库要写 bank tombstone 并传播，否则别的设备看到「远端还有」会把它拉回来。
     * epoch 用本轮将要写出的新 epoch，保证比旧删除/旧新增都新。
     */
    private fun mergedBankTombstones(
        remoteManifest: SyncManifest,
        baseline: Map<String, SyncedBankMeta>,
        at: Long
    ): List<Tombstone> {
        val localKeys = localBanks().map { SyncIdentity.keyOf(it) }.toSet()
        val epochForNew = maxOf(state.epoch(), remoteManifest.epoch) + 1L
        val deletedLocally = baseline.keys
            .filterNot { it in localKeys }
            .filterNot { key -> remoteManifest.bankTombstones.any { it.targetKey == key && it.deleted } }
            .map { key ->
                Tombstone(
                    kind = RecordKind.BANK,
                    targetKey = key,
                    epoch = epochForNew,
                    deviceId = remoteManifest.deviceId.ifBlank { state.deviceId() },
                    deleted = true
                )
            }
        if (deletedLocally.isEmpty()) return remoteManifest.bankTombstones
        return RecordMerge.mergeTombstones(remoteManifest.bankTombstones, deletedLocally)
    }

    private fun syncRecordShards(remote: SyncManifest): RecordOutcome {
        var uploaded = 0
        var downloaded = 0
        var failure: String? = null

        val localShards = linkedMapOf<String, ByteArray>()
        RecordShard.chunk(localEvents()).forEach { shard ->
            val payload = RecordShard.encode(shard)
            localShards[RecordShard.hash(payload)] = payload
        }

        remote.recordShards.forEach { shardHash ->
            if (shardHash in localShards) return@forEach
            try {
                val raw = client.getSyncObject(SyncObjects.recordName(shardHash))
                    ?: throw WebDavException("云端缺少记录分片（$shardHash）")
                val plain = SyncPayloadCipher.decrypt(raw, recoveryCode, SyncPayloadCipher.aadForContent(shardHash))
                applyRemoteEvents(RecordShard.decode(plain))
                downloaded++
            } catch (error: Exception) {
                // 缺片/坏片：跳过，别让整轮炸掉；manifest 稍后不推进，下轮重试。
                if (failure == null) failure = error.message ?: "记录分片读取失败：$shardHash"
            }
        }

        localShards.forEach { (shardHash, payload) ->
            if (shardHash in remote.recordShards) return@forEach
            val encrypted = SyncPayloadCipher.encrypt(payload, recoveryCode, SyncPayloadCipher.aadForContent(shardHash))
            client.putSyncObject(SyncObjects.recordName(shardHash), encrypted)
            uploaded++
        }

        val shards = (localShards.keys + remote.recordShards).distinct().sorted()
        return RecordOutcome(shards, uploaded, downloaded, failure)
    }

    private fun buildManifest(
        at: Long,
        baseline: Map<String, SyncedBankMeta>,
        remoteManifest: SyncManifest,
        epoch: Long,
        recordShards: List<String>,
        bankTombstones: List<Tombstone>,
        recordTombstones: List<Tombstone>
    ): SyncManifest {
        val tombstoned = bankTombstones.filter { it.deleted }.map { it.targetKey }.toSet()
        val banks = mutableMapOf<String, SyncBankRecord>()
        localBanks().forEach { bank ->
            val key = SyncIdentity.keyOf(bank)
            val hash = SyncIdentity.contentHash(bank)
            val previous = baseline[key]
            val updatedAt = if (previous?.contentHash == hash) previous.updatedAt else at
            banks[key] = SyncBankRecord(
                groupName = SyncIdentity.normalizeGroupName(bank.groupName),
                name = bank.name,
                contentHash = hash,
                updatedAt = updatedAt
            )
        }
        // 远端还有、本地没有的库继续留在清单里；但已 tombstone 删除的不再挂回去。
        remoteManifest.banks.forEach { (key, record) ->
            if (key !in tombstoned) banks.putIfAbsent(key, record)
        }
        return SyncManifest(
            deviceId = state.deviceId(),
            updatedAt = at,
            banks = banks,
            recordSnapshots = remoteManifest.recordSnapshots,
            version = SyncManifest.CURRENT_VERSION,
            epoch = epoch,
            recordShards = recordShards,
            bankTombstones = bankTombstones,
            recordTombstones = recordTombstones
        )
    }

    private fun metaOf(manifest: SyncManifest): Map<String, SyncedBankMeta> =
        manifest.banks.mapValues { (_, record) -> SyncedBankMeta(record.contentHash, record.updatedAt) }

    private fun collectGarbage(manifest: SyncManifest) {
        val referenced = manifest.banks.values.map { SyncObjects.bankName(it.contentHash) }.toSet() +
            manifest.recordShards.map { SyncObjects.recordName(it) }.toSet()
        client.listSyncObjects()
            .map { it.fileName }
            .filter { it != SyncObjects.MANIFEST && it !in referenced }
            .forEach { runCatching { client.deleteSyncObject(it) } }
    }
}
