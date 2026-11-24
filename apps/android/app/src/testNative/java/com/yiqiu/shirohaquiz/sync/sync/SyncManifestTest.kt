package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.security.BackupDecryptException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * manifest 编解码用 org.json，而纯 JVM 单测里 android.jar 的 JSONObject 是桩
 * （调用直接抛 RuntimeException("Stub!")），所以这里走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncManifestTest {
    private val code = "ABCDEFGH23456789ABCDEFGH23456789"

    private fun sample() = SyncManifest(
        deviceId = "device-a",
        updatedAt = 1_700_000_000_000L,
        banks = mapOf(
            "药理|第一章 绪论" to SyncBankRecord("药理", "第一章 绪论", "a".repeat(64), 1_700_000_000_000L),
            "英语|Unit 1" to SyncBankRecord("英语", "Unit 1", "b".repeat(64), 1_700_000_100_000L)
        ),
        recordSnapshots = mapOf("wrongBook" to SyncRecordSnapshot("c".repeat(64), 1_700_000_200_000L))
    )

    @Test
    fun jsonRoundTripKeepsEverything() {
        val decoded = SyncManifestCodec.fromJson(SyncManifestCodec.toJson(sample()))
        assertEquals(sample(), decoded)
    }

    @Test
    fun practiceProgressSurvivesRoundTrip() {
        val progress = """{"BANK:bank_1":{"updatedAt":1700000009000,"index":2,"sessionKeys":["bank_1#q1"]}}"""
        val manifest = sample().copy(practiceProgressJson = progress)
        val decoded = SyncManifestCodec.fromJson(SyncManifestCodec.toJson(manifest))
        assertEquals(progress, decoded.practiceProgressJson)
        assertEquals(manifest, decoded)
    }

    @Test
    fun encryptedManifestRoundTrips() {
        val manifest = sample()
        val payload = SyncManifestCodec.encrypt(manifest, code)
        assertEquals("SHQ1", String(payload.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(manifest, SyncManifestCodec.decrypt(payload, code))
    }

    @Test
    fun wrongRecoveryCodeCannotOpenManifest() {
        val payload = SyncManifestCodec.encrypt(sample(), code)
        try {
            SyncManifestCodec.decrypt(payload, "ZZZZZZZZ22222222ZZZZZZZZ22222222")
            fail("错误恢复码必须解不开 manifest")
        } catch (expected: BackupDecryptException) {
        }
    }

    @Test
    fun tamperedManifestIsRejected() {
        val payload = SyncManifestCodec.encrypt(sample(), code)
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        try {
            SyncManifestCodec.decrypt(payload, code)
            fail("被篡改的 manifest 必须解不开")
        } catch (expected: BackupDecryptException) {
        }
    }

    @Test
    fun plaintextIsNotAcceptedAsEncryptedManifest() {
        try {
            SyncManifestCodec.decrypt(SyncManifestCodec.toJson(sample()), code)
            fail("明文 manifest 不能被当成密文")
        } catch (expected: BackupDecryptException) {
        }
    }

    @Test
    fun syncObjectNamesArePartitioned() {
        val hash = "d".repeat(64)
        val bankName = SyncObjects.bankName(hash)
        assertEquals("shiroha-sync-bank-$hash.enc", bankName)
        assertTrue(SyncObjects.isSyncObject(bankName))
        assertTrue(SyncObjects.isSyncObject(SyncObjects.MANIFEST))
        assertEquals(hash, SyncObjects.bankHashOf(bankName))
        assertNull(SyncObjects.bankHashOf("shiroha-quiz-backup-x.zip"))
        assertFalse(SyncObjects.isSyncObject("shiroha-quiz-backup-20260101-000000000-abc-android-v1-sha256-${"e".repeat(64)}.zip"))
    }
}
