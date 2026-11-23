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
    private val keys: SyncKeys,
    private val state: SyncStateStore,
    private val localBanks: () -> List<QuizBank>,
    private val applyRemoteBank: (groupName: String, name: String, questions: List<Question>, bookName: String) -> Boolean,
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
        val recordDownloaded: Int = 0,
        /** 本轮用旧恢复码解开并已用账号密码钥匙重加密覆盖上传的对象数。 */
        val migrated: Int = 0
    )

    private data class RecordOutcome(
        val shards: List<String>,
        val uploaded: Int,
        val downloaded: Int,
        val failure: String?
    )

    /** 本轮重加密迁移的对象数（每个 run 重置）。 */
    private var migratedCount = 0

    fun run(): Report = SyncWriteGate.withLock {
        migratedCount = 0
        runLocked()
    }

    private fun runLocked(): Report {
        val at = clockMillis()
        return try {
            val baseline = state.bankMeta()
            val remoteManifest = fetchManifest() ?: SyncManifest(state.deviceId(), at, emptyMap())
            val forceUpload = state.forceUploadLocal()
            // 本轮墓碑必须在**做计划之前**算出来。否则本轮刚删掉的库会先被 DOWNLOAD 回来，
            // 删除意图被自己抹掉：墓碑永远写不出去，云端那份每次同步又把它复活（真机复现过）。
            val bankTombstones = mergedBankTombstones(remoteManifest, baseline, at)
            val plan = SyncPlanner.plan(
                localBanks = localBanks(),
                remoteBanks = remoteManifest.banks,
                baseline = baseline,
                bankTombstones = bankTombstones,
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
                // 落盘失败必须显式失败：以前忽略返回值还照样 downloaded++，
                // 结果下一轮又发现「本地没有这个库」→ 再下一次同样的对象（真机死循环）。
                val applied = applyRemoteBank(decoded.groupName, decoded.name, decoded.questions, decoded.bookName)
                if (!applied) {
                    return@runLocked Report(
                        ok = false,
                        uploaded = 0,
                        downloaded = downloaded,
                        conflicts = 0,  // 冲突统计在下载循环之后才累加，此处必为 0
                        message = "题库「${decoded.name}」写入本地失败，已停止本轮同步（不会反复重下）。"
                    )
                }
                downloaded++
            }
            var conflicts = 0
            val uploadsNeeded = plan.uploads.toMutableList()
            conflictOutcome.forEach { (entry, remoteWins) ->
                val decoded = pending.getValue(entry.key)
                val copyName = decisionByName.getValue(entry.key).copyName
                if (remoteWins) {
                    renameLocalBank(entry.localBank!!.id, copyName)
                    applyRemoteBank(decoded.groupName, decoded.name, decoded.questions, decoded.bookName)
                } else {
                    applyRemoteBank(decoded.groupName, copyName, decoded.questions, decoded.bookName)
                    uploadsNeeded += entry
                }
                conflicts++
            }

            // ③ 记录类：先合 tombstone，再同步事件分片（分片对象必须在 manifest 之前落地）
            // bankTombstones 已在做计划前算好，这里直接复用，避免重复计算导致 epoch 不一致。
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
                val payload = SyncBankCodec.encode(SyncIdentity.normalizeGroupName(bank.groupName), bank.name, bank.questions, bank.bookName)
                val encrypted = keys.encrypt(payload, SyncPayloadCipher.aadForContent(hash))
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
                    client.putSyncObject(SyncObjects.MANIFEST, keys.encryptManifest(merged))
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
                    recordDownloaded = record.downloaded,
                    migrated = migratedCount
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

    /** 全量重加密迁移的结果。 */
    data class MigrationReport(
        val total: Int,
        val migrated: Int,
        /** 失败对象名；非空表示迁移未完成、manifest 未重写。 */
        val failedObject: String?,
        val message: String
    )

    private data class MigrationTarget(val name: String, val aad: ByteArray)

    /**
     * **全量**把云端对象迁到凭据钥匙：遍历 manifest 引用的所有 bank / record 对象，
     * 逐个「下载 → 旧码解 → 主钥匙重加密 → 覆盖上传」，最后重写 manifest。
     *
     * 幂等：已能用主钥匙解开的对象直接跳过（[SyncKeys.Opened.usedLegacy] == false）。
     * 可中断：每个对象独立完成；中途失败时**不删旧对象、不重写 manifest**，下次从断点继续。
     */
    fun migrateAllToPrimaryKey(onProgress: ((done: Int, total: Int) -> Unit)? = null): MigrationReport {
        val manifestRaw = client.getSyncObject(SyncObjects.MANIFEST)
            ?: return MigrationReport(0, 0, null, "云端没有清单，无需迁移。")
        val manifest = keys.openManifest(manifestRaw).manifest
        return migrateObjects(
            manifest = manifest,
            fetch = { name -> client.getSyncObject(name) },
            put = { name, bytes -> client.putSyncObject(name, bytes) },
            onProgress = onProgress
        ).also { report ->
            // manifest 最后写，且总是用主钥匙（它自己也可能还是旧码）。
            if (report.failedObject == null) {
                client.putSyncObject(SyncObjects.MANIFEST, keys.encryptManifest(manifest))
            }
        }
    }

    /**
     * 迁移逻辑本体（**不碰网络**，fetch/put 由调用方注入），便于纯内存单测。
     */
    internal fun migrateObjects(
        manifest: SyncManifest,
        fetch: (String) -> ByteArray?,
        put: (String, ByteArray) -> Unit,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): MigrationReport {
        val targets = buildList {
            manifest.banks.values.sortedBy { it.contentHash }.forEach { record ->
                add(MigrationTarget(SyncObjects.bankName(record.contentHash), SyncPayloadCipher.aadForContent(record.contentHash)))
            }
            manifest.recordShards.sorted().forEach { shard ->
                add(MigrationTarget(SyncObjects.recordName(shard), SyncPayloadCipher.aadForContent(shard)))
            }
        }

        var migrated = 0
        targets.forEachIndexed { index, target ->
            try {
                val raw = fetch(target.name)
                if (raw != null) {
                    val opened = keys.open(raw, target.aad)
                    if (opened.usedLegacy) {
                        put(target.name, keys.encrypt(opened.plaintext, target.aad))
                        migrated++
                    }
                }
            } catch (error: Exception) {
                // 不删旧对象、不写 manifest；把对象名与原文带回去。
                return MigrationReport(
                    total = targets.size,
                    migrated = migrated,
                    failedObject = target.name,
                    message = "全量重加密中断于 ${target.name}：${error::class.java.simpleName}: ${error.message}"
                )
            }
            onProgress?.invoke(index + 1, targets.size)
        }

        return MigrationReport(targets.size, migrated, null, "迁移完成 $migrated 个（共 ${targets.size} 个对象）")
    }

    private fun fetchManifest(): SyncManifest? {
        val raw = client.getSyncObject(SyncObjects.MANIFEST) ?: return null
        val opened = keys.openManifest(raw)
        if (opened.usedLegacy) {
            // 迁移：这个 manifest 还是旧恢复码加密的，立刻用新钥匙覆盖。
            runCatching { client.putSyncObject(SyncObjects.MANIFEST, keys.encryptManifest(opened.manifest)) }
                .onSuccess { migratedCount++ }
        }
        return opened.manifest
    }

    private fun fetchBank(contentHash: String): DecodedSyncBank {
        val raw = client.getSyncObject(SyncObjects.bankName(contentHash))
            ?: throw WebDavException("云端缺少题库数据（$contentHash），已放弃本次同步。")
        val aad = SyncPayloadCipher.aadForContent(contentHash)
        val opened = keys.open(raw, aad)
        if (opened.usedLegacy) {
            runCatching { client.putSyncObject(SyncObjects.bankName(contentHash), keys.encrypt(opened.plaintext, aad)) }
                .onSuccess { migratedCount++ }
        }
        return SyncBankCodec.decode(opened.plaintext)
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
                val aad = SyncPayloadCipher.aadForContent(shardHash)
                val opened = keys.open(raw, aad)
                if (opened.usedLegacy) {
                    runCatching { client.putSyncObject(SyncObjects.recordName(shardHash), keys.encrypt(opened.plaintext, aad)) }
                        .onSuccess { migratedCount++ }
                }
                applyRemoteEvents(RecordShard.decode(opened.plaintext))
                downloaded++
            } catch (error: Exception) {
                // 缺片/坏片：跳过，别让整轮炸掉；manifest 稍后不推进，下轮重试。
                if (failure == null) failure = error.message ?: "记录分片读取失败：$shardHash"
            }
        }

        localShards.forEach { (shardHash, payload) ->
            if (shardHash in remote.recordShards) return@forEach
            val encrypted = keys.encrypt(payload, SyncPayloadCipher.aadForContent(shardHash))
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
