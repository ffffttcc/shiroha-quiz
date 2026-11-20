package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.sync.webdav.WebDavClient
import com.yiqiu.shirohaquiz.sync.webdav.WebDavException

/**
 * 单库增量同步。
 *
 * 提交顺序是硬约束：**先下载全部需要的 blob 并解密校验 → 再写本地 → 最后提交 manifest**。
 * 任何一步抛错都直接返回失败且不写本地、不更新 last_sync_at（沿用「失败零写入」）。
 *
 * 删除暂不同步：远端仍保留的库不会被删，避免一台设备的误删把别的设备清空。
 */
class SyncEngine(
    private val client: WebDavClient,
    private val recoveryCode: String,
    private val state: SyncStateStore,
    private val localBanks: () -> List<QuizBank>,
    private val applyRemoteBank: (groupName: String, name: String, questions: List<Question>) -> Unit,
    private val renameLocalBank: (bankId: String, newName: String) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    data class Report(
        val ok: Boolean,
        val uploaded: Int,
        val downloaded: Int,
        val conflicts: Int,
        val message: String
    )

    fun run(): Report {
        val at = clockMillis()
        return try {
            val baseline = state.bankMeta()
            val remoteManifest = fetchManifest() ?: SyncManifest(state.deviceId(), at, emptyMap())
            val plan = SyncPlanner.plan(localBanks(), remoteManifest.banks, baseline)

            // ① 先把所有要落地的远端版本下载 + 解密 + 解析完（此时还没动本地数据）
            val pending = mutableMapOf<String, DecodedSyncBank>()
            plan.downloads.forEach { entry ->
                pending[entry.key] = fetchBank(entry.remoteHash!!)
            }
            val conflictOutcome = mutableListOf<Pair<SyncPlanEntry, Boolean>>() // entry -> 远端赢?
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
                    // 远端那份留成本地副本，本地继续当赢家（下面会把它传上去）
                    applyRemoteBank(decoded.groupName, copyName, decoded.questions)
                    uploadsNeeded += entry
                }
                conflicts++
            }

            // ③ 上传本地嬴家（只传内容真的变了的库）
            var uploaded = 0
            uploadsNeeded.forEach { entry ->
                val bank = entry.localBank ?: return@forEach
                val hash = SyncIdentity.contentHash(bank)
                val payload = SyncBankCodec.encode(SyncIdentity.normalizeGroupName(bank.groupName), bank.name, bank.questions)
                val encrypted = SyncPayloadCipher.encrypt(payload, recoveryCode, SyncPayloadCipher.aadForContent(hash))
                client.putSyncObject(SyncObjects.bankName(hash), encrypted)
                uploaded++
            }

            // ④ 提交 manifest（此时本地已稳定）
            val merged = buildManifest(at, baseline, remoteManifest)
            client.putSyncObject(SyncObjects.MANIFEST, SyncManifestCodec.encrypt(merged, recoveryCode))

            // ⑤ 记录状态 + 回收不再被引用的 blob
            state.markSynced(at, message(uploaded, downloaded, conflicts), metaOf(merged))
            runCatching { collectGarbage(merged) }

            Report(true, uploaded, downloaded, conflicts, message(uploaded, downloaded, conflicts))
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

    private fun buildManifest(
        at: Long,
        baseline: Map<String, SyncedBankMeta>,
        remoteManifest: SyncManifest
    ): SyncManifest {
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
        // 远端还有、本地没有的库继续留在清单里（删除不同步）
        remoteManifest.banks.forEach { (key, record) ->
            banks.putIfAbsent(key, record)
        }
        return SyncManifest(
            deviceId = state.deviceId(),
            updatedAt = at,
            banks = banks,
            recordSnapshots = remoteManifest.recordSnapshots
        )
    }

    private fun metaOf(manifest: SyncManifest): Map<String, SyncedBankMeta> =
        manifest.banks.mapValues { (_, record) -> SyncedBankMeta(record.contentHash, record.updatedAt) }

    private fun collectGarbage(manifest: SyncManifest) {
        val referenced = manifest.banks.values.map { SyncObjects.bankName(it.contentHash) }.toSet()
        client.listSyncObjects()
            .map { it.fileName }
            .filter { it != SyncObjects.MANIFEST && it !in referenced }
            .forEach { runCatching { client.deleteSyncObject(it) } }
    }
}
