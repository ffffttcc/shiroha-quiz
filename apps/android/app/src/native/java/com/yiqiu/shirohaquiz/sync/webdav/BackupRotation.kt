package com.yiqiu.shirohaquiz.sync.webdav

/**
 * 备份轮换（自动迭代）的纯逻辑：保留份数归一化、可删候选挑选、结果统计。
 *
 * 两条铁律写在这里，别处不许绕开：
 * 1. **绝不删同步对象**（`shiroha-sync-` 前缀）：那是题库与答题记录，删了等于丢数据。
 * 2. **绝不删最新一份**：哪怕保留份数被填成 0。
 */
object BackupRotation {
    val KEEP_OPTIONS = listOf(3, 5, 10)
    const val DEFAULT_KEEP = 5
    const val SYNC_OBJECT_PREFIX = "shiroha-sync-"

    /** 旧版本存过 20 / 7 等值；20 折算成 10，其它非法值回落到默认 5。 */
    fun normalizeKeep(raw: Int?): Int = when {
        raw == null -> DEFAULT_KEEP
        raw == 20 -> 10
        raw in KEEP_OPTIONS -> raw
        else -> DEFAULT_KEEP
    }

    fun isSyncObject(fileName: String): Boolean = fileName.startsWith(SYNC_OBJECT_PREFIX)

    /** 只有 Android 原生、校验信息完整、且不是同步对象的完整备份包才允许参与清理。 */
    fun isFullBackup(entry: WebDavBackupEntry): Boolean =
        entry.source == "android" && entry.integrityAvailable && !isSyncObject(entry.fileName)

    /**
     * 按 `createdAt` 倒序保留前 keepCount 份，返回应当删除的旧备份。
     * `protectedFileNames` 用于保护「本次刚上传的那份」等。
     */
    fun planCleanup(
        entries: List<WebDavBackupEntry>,
        keepCount: Int,
        protectedFileNames: Set<String> = emptySet()
    ): List<WebDavBackupEntry> {
        val full = entries.filter(::isFullBackup).sortedByDescending { it.createdAt ?: 0L }
        if (full.isEmpty()) return emptyList()
        val newestFileName = full.first().fileName
        val keep = normalizeKeep(keepCount).coerceAtLeast(1)
        return full.drop(keep).filter {
            it.fileName != newestFileName && it.fileName !in protectedFileNames
        }
    }

    /** 逐条结果折叠成（成功数, 失败数）——单条失败不影响其余。 */
    fun tally(outcomes: List<Boolean>): Pair<Int, Int> =
        outcomes.count { it } to outcomes.count { !it }

    fun summary(deleted: Int, failed: Int, remaining: Int): String = when {
        deleted == 0 && failed == 0 -> "没有需要清理的旧备份，云端仍是 $remaining 份。"
        failed == 0 -> "已删除 $deleted 份旧备份，云端剩 $remaining 份。"
        deleted == 0 -> "这次没能删掉任何备份（$failed 份失败），云端仍是 $remaining 份。"
        else -> "已删除 $deleted 份旧备份，$failed 份失败，云端剩 $remaining 份。"
    }
}
