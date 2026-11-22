package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.security.BackupDecryptException

/**
 * 完整备份包迁移的**纯逻辑**（下载/上传由调用方注入），便于单测覆盖「完整跑通」。
 *
 * 真机事故（本次）：迁移进循环第一行就抛 `IllegalArgumentException: Failed requirement.`，
 * 根因是临时文件扩展名 `enc` 被 `newCacheFile` 的无声断言拒掉——排查靠读代码才找到。
 * 所以这里把「旧码解 → 主钥匙重加密 → 交给 upload」整段抽出来，由单测直接跑通。
 */
object BackupMigration {

    data class Entry(
        val fileName: String,
        val payload: ByteArray,
        val plaintextSha256: ByteArray
    )

    data class Result(
        val total: Int,
        val migrated: Int,
        val failedObject: String?,
        val message: String
    )

    /**
     * 折算「可用的期望大小」，**绝不让哨兵值参与完整性比较**。
     *
     * 真机事故：`fallbackEntry` 在列表没带 getcontentlength 时给 `sizeBytes = -1`，
     * 直接拿去下载 → `total != expectedSize` 必然成立 → 报「SHA256 摘要或大小校验失败」。
     * 规则：只有 > 0 的值才算数；列表值优先，其次 stat 值；都没有返回 null（调用方应跳过）。
     */
    fun effectiveSize(listedBytes: Long?, statBytes: Long?): Long? =
        listOfNotNull(listedBytes, statBytes).firstOrNull { it > 0L }

    /**
     * @param upload 由调用方实现（覆盖写同名对象）
     */
    fun migrate(
        entries: List<Entry>,
        keys: SyncKeys,
        upload: (fileName: String, bytes: ByteArray) -> Unit,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Result {
        var migrated = 0
        entries.forEachIndexed { index, entry ->
            try {
                val opened = keys.openBackup(entry.payload, entry.plaintextSha256)
                if (opened.usedLegacy) {
                    upload(entry.fileName, keys.encryptBackup(opened.plaintext, entry.plaintextSha256))
                    migrated++
                }
            } catch (error: BackupDecryptException) {
                // 解不开就报用户能看懂的话，且不前进、不删旧文件。
                return Result(entries.size, migrated, entry.fileName, error.message ?: "备份无法解密。")
            } catch (error: Exception) {
                return Result(entries.size, migrated, entry.fileName, "${error::class.java.simpleName}: ${error.message}")
            }
            onProgress?.invoke(index + 1, entries.size)
        }
        return Result(entries.size, migrated, null, "迁移完成 $migrated 份（共 ${entries.size} 份）")
    }
}
