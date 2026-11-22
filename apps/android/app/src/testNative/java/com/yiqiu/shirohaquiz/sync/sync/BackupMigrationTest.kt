package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.security.BackupEncryption
import com.yiqiu.shirohaquiz.security.BackupDecryptException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整备份包迁移的**完整跑通**用例。
 *
 * 真机事故：迁移一进循环就抛 `Failed requirement.`——临时文件扩展名 enc 被
 * newCacheFile 的无声断言拒掉。这里用旧钥匙真实加密一份 .zip.enc，跑完整迁移，
 * 断言「不抛、被重加密、覆盖上传被调用」。
 */
class BackupMigrationTest {

    private val legacyCode = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val plaintext = ByteArray(2048) { (it % 251).toByte() }
    private val digest = java.security.MessageDigest.getInstance("SHA-256").digest(plaintext)

    private fun legacyOnly() = SyncKeys.fromLegacyRecoveryCode(legacyCode)
    private fun withLegacy() = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = legacyCode)
    private fun primaryOnly() = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = null)

    private fun legacyEntry(name: String) = BackupMigration.Entry(
        fileName = name,
        payload = legacyOnly().encryptBackup(plaintext, digest),
        plaintextSha256 = digest
    )

    /** 旧钥匙包能完整迁完：不抛、计 1 份、上传内容可被主钥匙解开。 */
    @Test
    fun migratesLegacyPackageEndToEnd() {
        val uploaded = mutableMapOf<String, ByteArray>()
        val result = BackupMigration.migrate(
            entries = listOf(legacyEntry("shiroha-quiz-backup-x-sha256-a.zip.enc")),
            keys = withLegacy(),
            upload = { name, bytes -> uploaded[name] = bytes }
        )
        assertNull("不应失败：${result.message}", result.failedObject)
        assertEquals(1, result.total)
        assertEquals(1, result.migrated)
        assertEquals(1, uploaded.size)
        val reopened = primaryOnly().openBackup(uploaded.values.first(), digest)
        assertArrayEquals(plaintext, reopened.plaintext)
    }

    /** 幂等：已经是主钥匙的包直接跳过（不上传、计数 0）。 */
    @Test
    fun alreadyMigratedPackageIsSkipped() {
        val uploaded = mutableMapOf<String, ByteArray>()
        val entry = BackupMigration.Entry(
            fileName = "already.zip.enc",
            payload = primaryOnly().encryptBackup(plaintext, digest),
            plaintextSha256 = digest
        )
        val result = BackupMigration.migrate(listOf(entry), withLegacy(), { n, b -> uploaded[n] = b })
        assertEquals(0, result.migrated)
        assertTrue("不该重复上传", uploaded.isEmpty())
    }

    /** 解不开时：报出对象名与可读消息，不上传、不前进。 */
    @Test
    fun undecryptablePackageReportsWithoutUpload() {
        val uploaded = mutableMapOf<String, ByteArray>()
        val entry = BackupMigration.Entry("broken.zip.enc", ByteArray(64) { 7 }, digest)
        val result = BackupMigration.migrate(listOf(entry), withLegacy(), { n, b -> uploaded[n] = b })
        assertNotNull(result.failedObject)
        assertTrue("应给人话：${result.message}", result.message.contains("旧版本加密"))
        assertTrue(uploaded.isEmpty())
        assertEquals(0, result.migrated)
    }

    /** 进度回调按份推进到最后一份。 */
    @Test
    fun progressReportsEachEntry() {
        val seen = mutableListOf<Pair<Int, Int>>()
        BackupMigration.migrate(
            entries = listOf(legacyEntry("a.zip.enc"), legacyEntry("b.zip.enc")),
            keys = withLegacy(),
            upload = { _, _ -> },
            onProgress = { done, total -> seen += done to total }
        )
        assertEquals(2, seen.size)
        assertEquals(2 to 2, seen.last())
    }
}
