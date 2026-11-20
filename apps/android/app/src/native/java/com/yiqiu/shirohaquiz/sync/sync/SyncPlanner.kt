package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.state.QuizBank

enum class SyncActionKind { UPLOAD, DOWNLOAD, CONFLICT }

data class SyncPlanEntry(
    val key: String,
    val kind: SyncActionKind,
    val localBank: QuizBank?,
    val remote: SyncBankRecord?,
    val localHash: String?,
    val remoteHash: String?
)

data class SyncPlan(val entries: List<SyncPlanEntry>) {
    val uploads: List<SyncPlanEntry> get() = entries.filter { it.kind == SyncActionKind.UPLOAD }
    val downloads: List<SyncPlanEntry> get() = entries.filter { it.kind == SyncActionKind.DOWNLOAD }
    val conflicts: List<SyncPlanEntry> get() = entries.filter { it.kind == SyncActionKind.CONFLICT }
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * 增量判定（纯逻辑，不碰网络/磁盘，便于单测）。
 *
 * 判据只用两样东西：本地当前 hash、上次同步成功时记下的 hash（baseline）。
 * - 本地 hash == 远端 hash → 双方一致，什么都不做；
 * - 只有一边相对 baseline 变了 → 单向传；
 * - 两边都变了、或首次同步就撞上同一身份键但内容不同 → 冲突（由引擎按时间判胜负并留副本）。
 */
object SyncPlanner {
    fun plan(
        localBanks: List<QuizBank>,
        remoteBanks: Map<String, SyncBankRecord>,
        baseline: Map<String, SyncedBankMeta>
    ): SyncPlan {
        val localByKey = localBanks.groupBy { SyncIdentity.keyOf(it) }
            .mapValues { (_, banks) -> banks.first() }
        val entries = mutableListOf<SyncPlanEntry>()
        (localByKey.keys + remoteBanks.keys).sorted().forEach { key ->
            val local = localByKey[key]
            val remote = remoteBanks[key]
            val localHash = local?.let(SyncIdentity::contentHash)
            val remoteHash = remote?.contentHash
            val baseHash = baseline[key]?.contentHash
            when {
                local == null && remote == null -> Unit
                local != null && remote == null -> entries += SyncPlanEntry(key, SyncActionKind.UPLOAD, local, null, localHash, null)
                local == null && remote != null -> entries += SyncPlanEntry(key, SyncActionKind.DOWNLOAD, null, remote, null, remoteHash)
                localHash == remoteHash -> Unit
                else -> {
                    val localChanged = baseHash != localHash
                    val remoteChanged = baseHash != remoteHash
                    val kind = when {
                        localChanged && remoteChanged -> SyncActionKind.CONFLICT
                        localChanged -> SyncActionKind.UPLOAD
                        else -> SyncActionKind.DOWNLOAD
                    }
                    entries += SyncPlanEntry(key, kind, local, remote, localHash, remoteHash)
                }
            }
        }
        return SyncPlan(entries)
    }
}

/** 冲突裁决结果：谁赢 + 败者副本叫什么名字。纯函数，便于单测。 */
data class SyncConflictDecision(val remoteWins: Boolean, val copyName: String)

object SyncConflict {
    /**
     * 单用户多设备：用「远端 updatedAt」对比「本机上次确认该内容的时间」判新旧。
     * 两台机器时钟大致同步时可靠；同一秒内互改会退化成"远端赢"，但败者一定留副本，不丢数据。
     */
    fun decide(key: String, remoteUpdatedAt: Long, localConfirmedAt: Long, now: Long): SyncConflictDecision {
        val remoteWins = remoteUpdatedAt > localConfirmedAt
        val originalName = key.substringAfter('|')
        return SyncConflictDecision(remoteWins, SyncIdentity.conflictCopyName(originalName, now))
    }
}
