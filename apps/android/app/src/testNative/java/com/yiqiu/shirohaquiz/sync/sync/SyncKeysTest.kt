package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.security.BackupDecryptException
import com.yiqiu.shirohaquiz.security.BackupEncryption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 账号密码派生钥匙 + 旧恢复码兼容迁移。
 *
 * 背景：原来的密钥来自设备本地恢复码，新设备 `ensure()` 会自造新码 → 云端数据事实上只能在
 * 原设备解。改成「账号 + 密码」派生后，新设备填同一组凭据即可解密；旧对象用旧码兜底并就地重加密。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncKeysTest {

    private val legacyCode = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  // 32 位旧恢复码
    private val aad = SyncPayloadCipher.aadForManifest()
    private val plaintext = "hello-shiroha".toByteArray()

    /** 两台设备用同一组账号密码 → 派生结果一致，可以互相解密。 */
    @Test
    fun sameCredentialsOnTwoDevicesInteroperate() {
        val deviceA = SyncKeys.fromCredentials("test", "s3cret-pass", legacyRecoveryCode = null)
        val deviceB = SyncKeys.fromCredentials("test", "s3cret-pass", legacyRecoveryCode = null)

        val sealed = deviceA.encrypt(plaintext, aad)
        val opened = deviceB.open(sealed, aad)
        assertArrayEquals(plaintext, opened.plaintext)
        assertFalse("新钥匙加密的对象不应走旧码回退", opened.usedLegacy)
    }

    /** 密码短于 32 位也必须能派生（放宽恢复码那条长度校验）。 */
    @Test
    fun shortPasswordStillDerivesKey() {
        val keys = SyncKeys.fromCredentials("test", "123", legacyRecoveryCode = null)
        val sealed = keys.encrypt(plaintext, aad)
        assertArrayEquals(plaintext, keys.open(sealed, aad).plaintext)
    }

    /** 归一化后用 \0 分隔，避免 "ab"+"cd" 与 "abc"+"d" 碰撞。 */
    @Test
    fun credentialMaterialKeepsSeparatorSoPairsDoNotCollide() {
        val a = BackupEncryption.credentialMaterial("ab", "cd")
        val b = BackupEncryption.credentialMaterial("abc", "d")
        assertNotEquals("不同 (账号,密码) 组合不能派生出同一材料串", a, b)
        assertTrue("材料串应含 NUL 分隔符", a.contains('\u0000'))
    }

    /** 旧码加密的对象：先主钥匙失败 → 旧码兜底成功，并标记 usedLegacy。 */
    @Test
    fun legacyEncryptedObjectFallsBackToRecoveryCode() {
        val legacySealed = SyncPayloadCipher.encrypt(plaintext, legacyCode, aad)
        val keys = SyncKeys.fromCredentials("test", "pw", legacyRecoveryCode = legacyCode)

        val opened = keys.open(legacySealed, aad)
        assertTrue("应识别出走的是旧码", opened.usedLegacy)
        assertArrayEquals(plaintext, opened.plaintext)
    }

    /** 迁移幂等：用主钥匙重加密后再开两次，都不会再走旧码。 */
    @Test
    fun reEncryptionIsIdempotent() {
        val legacySealed = SyncPayloadCipher.encrypt(plaintext, legacyCode, aad)
        val keys = SyncKeys.fromCredentials("test", "pw", legacyRecoveryCode = legacyCode)

        val migrated = keys.encrypt(keys.open(legacySealed, aad).plaintext, aad)
        assertFalse(keys.open(migrated, aad).usedLegacy)
        assertFalse(keys.open(migrated, aad).usedLegacy)  // 再开一次仍然不走旧码
        assertArrayEquals(plaintext, keys.open(migrated, aad).plaintext)
    }

    /** 凭据不对且没有旧码 → 报人话，不甩 InvalidTag。 */
    @Test
    fun wrongCredentialsProduceReadableMessage() {
        val sealed = SyncKeys.fromCredentials("test", "right-pw", null).encrypt(plaintext, aad)
        val wrong = SyncKeys.fromCredentials("test", "wrong-pw", legacyRecoveryCode = null)
        val error = assertThrows(BackupDecryptException::class.java) { wrong.open(sealed, aad) }
        assertTrue("应说人话：${error.message}", error.message.orEmpty().contains("账号或密码"))
        assertFalse("不应把底层 InvalidTag 抛给用户", error.message.orEmpty().contains("InvalidTag"))
    }

    /** manifest 也走同一把钥匙：新钥匙加密的 manifest 能被另一台同凭据设备解开。 */
    @Test
    fun manifestEncryptsAndOpensWithCredentialKey() {
        val manifest = SyncManifest("dev-1", 100L, emptyMap(), epoch = 3L)
        val deviceA = SyncKeys.fromCredentials("test", "pw", null)
        val deviceB = SyncKeys.fromCredentials("test", "pw", null)

        val sealed = deviceA.encryptManifest(manifest)
        val opened = deviceB.openManifest(sealed)
        assertEquals(3L, opened.manifest.epoch)
        assertFalse(opened.usedLegacy)
    }

    /** 只有旧码、没有密码时仍能跑（老设备兼容路径）。 */
    @Test
    fun legacyOnlyKeysStillWork() {
        val sealed = SyncPayloadCipher.encrypt(plaintext, legacyCode, aad)
        val legacyOnly = SyncKeys.fromLegacyRecoveryCode(legacyCode)
        assertArrayEquals(plaintext, legacyOnly.open(sealed, aad).plaintext)
    }
}
