package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.security.BackupDecryptException
import com.yiqiu.shirohaquiz.security.BackupEncryption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整备份包（.zip.enc）的双钥匙：加密一律主钥匙，解密主钥匙→旧恢复码回退。
 *
 * AAD 规则**不许动**：仍是 `byteArrayOf(VERSION) + 明文 sha256 前 8 字节`，
 * 因为文件名与 .meta.json 里写的都是这个 sha256，改了旧包全废。
 */
class SyncKeysBackupTest {

    private val legacyCode = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val plaintext = ByteArray(4096) { (it % 251).toByte() }
    private val digest = java.security.MessageDigest.getInstance("SHA-256").digest(plaintext)

    private fun keysWithLegacy() = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = legacyCode)
    private fun primaryOnly() = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = null)
    private fun legacyOnly() = SyncKeys.fromLegacyRecoveryCode(legacyCode)

    /** 新备份用主钥匙加密，另一台「只有凭据」的设备能直接解开。 */
    @Test
    fun newBackupEncryptedWithPrimaryOpensAnywhere() {
        val sealed = keysWithLegacy().encryptBackup(plaintext, digest)
        val opened = primaryOnly().openBackup(sealed, digest)
        assertArrayEquals(plaintext, opened.plaintext)
        assertFalse("新备份不该走旧码回退", opened.usedLegacy)
    }

    /** 旧包（旧恢复码加密）回退旧码能解；再用主钥匙重加密后就不再需要旧码。 */
    @Test
    fun legacyBackupFallsBackThenMigratesToPrimary() {
        val legacySealed = legacyOnly().encryptBackup(plaintext, digest)

        val first = keysWithLegacy().openBackup(legacySealed, digest)
        assertTrue("应识别出走的是旧码", first.usedLegacy)
        assertArrayEquals(plaintext, first.plaintext)

        // 迁移：用主钥匙重加密后，只有凭据的设备也能解，且不再走回退
        val migrated = keysWithLegacy().encryptBackup(first.plaintext, digest)
        val second = primaryOnly().openBackup(migrated, digest)
        assertFalse(second.usedLegacy)
        assertArrayEquals(plaintext, second.plaintext)
    }

    /** 两种钥匙都失败：给「旧版本加密」人话，不许出现 InvalidTag / 恢复码不匹配。 */
    @Test
    fun bothKeysFailingGivesReadableMessage() {
        val sealed = keysWithLegacy().encryptBackup(plaintext, digest)
        // 换一组完全不同的凭据、且不带旧码
        val stranger = SyncKeys.fromCredentials("other", "other-pw", legacyRecoveryCode = null)

        val error = assertThrows(BackupDecryptException::class.java) { stranger.openBackup(sealed, digest) }
        val message = error.message.orEmpty()
        assertTrue("应说明是旧版本加密：$message", message.contains("旧版本加密"))
        assertFalse("不许出现 InvalidTag", message.contains("InvalidTag"))
        assertFalse("不许出现恢复码不匹配", message.contains("恢复码不匹配"))
    }

    /** AAD 规则未被改动：本项目用 BackupEncryption 自身的 key 重载能互解（同一 AAD 口径）。 */
    @Test
    fun aadRuleUnchangedAcrossSyncKeysAndBackupEncryption() {
        val key = BackupEncryption.deriveKey(legacyCode)
        val sealedByLegacyApi = BackupEncryption.encryptWithKey(plaintext, key, digest)
        val opened = keysWithLegacy().openBackup(sealedByLegacyApi, digest)
        assertArrayEquals(plaintext, opened.plaintext)

        val sealedBySyncKeys = keysWithLegacy().encryptBackup(plaintext, digest)
        assertArrayEquals(plaintext, BackupEncryption.decryptWithKey(sealedBySyncKeys, key, digest))
    }
}
