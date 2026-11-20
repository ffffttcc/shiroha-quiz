package com.yiqiu.shirohaquiz.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 端到端加密的纯 JVM 用例：不碰 Android、不碰网络，只锁密码学行为。
 * （真机 WebDAV 上传/下载由 lead 装机验证。）
 */
class BackupEncryptionTest {
    private val code = "ABCDEFGH23456789ABCDEFGH23456789"

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun sampleZip(): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(2000) { (it % 251).toByte() }

    @Test
    fun roundTripRestoresOriginalBytes() {
        val plaintext = sampleZip()
        val payload = BackupEncryption.encrypt(plaintext, code, sha256(plaintext))
        val decrypted = BackupEncryption.decrypt(payload, code, sha256(plaintext))
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun payloadStartsWithVersionedHeader() {
        val plaintext = sampleZip()
        val payload = BackupEncryption.encrypt(plaintext, code, sha256(plaintext))
        assertEquals("SHQ1", String(payload.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(BackupEncryption.VERSION, payload[4])
        assertEquals(BackupEncryption.SALT_VERSION, payload[5])
        assertEquals(12, payload[6].toInt())
        // 12 字节 nonce + 16 字节 tag 之外，其余都是密文：长度必然大于明文。
        assertTrue(payload.size > plaintext.size)
    }

    @Test
    fun wrongRecoveryCodeFailsLoudly() {
        val plaintext = sampleZip()
        val payload = BackupEncryption.encrypt(plaintext, code, sha256(plaintext))
        val otherCode = "ZZZZZZZZ22222222ZZZZZZZZ22222222"
        try {
            BackupEncryption.decrypt(payload, otherCode, sha256(plaintext))
            fail("错误恢复码必须解密失败")
        } catch (expected: BackupDecryptException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun tamperedPayloadFailsLoudly() {
        val plaintext = sampleZip()
        val payload = BackupEncryption.encrypt(plaintext, code, sha256(plaintext))
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        try {
            BackupEncryption.decrypt(payload, code, sha256(plaintext))
            fail("被篡改的密文必须解密失败")
        } catch (expected: BackupDecryptException) {
        }
    }

    @Test
    fun mismatchedPlaintextDigestFailsBecauseItIsBoundAsAad() {
        val plaintext = sampleZip()
        val payload = BackupEncryption.encrypt(plaintext, code, sha256(plaintext))
        val wrongDigest = sha256("another content".toByteArray())
        try {
            BackupEncryption.decrypt(payload, code, wrongDigest)
            fail("AAD 不匹配时必须失败")
        } catch (expected: BackupDecryptException) {
        }
    }

    @Test
    fun truncatedPayloadFailsLoudly() {
        val plaintext = sampleZip()
        val payload = BackupEncryption.encrypt(plaintext, code, sha256(plaintext))
        try {
            BackupEncryption.decrypt(payload.copyOfRange(0, 10), code, sha256(plaintext))
            fail("截断的密文必须解密失败")
        } catch (expected: BackupDecryptException) {
        }
    }

    /** 关键兼容性：老的不加密 ZIP 不能被误判成密文。 */
    @Test
    fun legacyPlainZipIsNotTreatedAsEncrypted() {
        assertFalse(BackupEncryption.isEncrypted(sampleZip()))
        assertTrue(BackupEncryption.isEncrypted(BackupEncryption.encrypt(sampleZip(), code, sha256(sampleZip()))))
    }

    @Test
    fun recoveryCodeIsNormalizedAndFormatted() {
        val generated = BackupEncryption.generateRecoveryCode(SecureRandom())
        assertEquals(BackupEncryption.RECOVERY_CODE_LENGTH, generated.length)
        val formatted = BackupEncryption.formatRecoveryCode(generated)
        assertTrue(formatted.contains("-"))
        // 带分隔符/小写写法也要能派生出同一把钥匙。
        assertEquals(BackupEncryption.formatRecoveryCode(generated.lowercase()), formatted)
        assertArrayEquals(BackupEncryption.deriveKey(generated), BackupEncryption.deriveKey(formatted))
        assertArrayEquals(BackupEncryption.deriveKey(generated), BackupEncryption.deriveKey(generated.lowercase()))
        assertEquals(32, BackupEncryption.deriveKey(generated).size)
        assertNotEquals(
            BackupEncryption.deriveKey(generated).toList(),
            BackupEncryption.deriveKey(BackupEncryption.generateRecoveryCode(SecureRandom())).toList()
        )
    }
}
