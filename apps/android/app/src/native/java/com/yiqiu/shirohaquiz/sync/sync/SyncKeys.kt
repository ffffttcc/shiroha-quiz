package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.security.BackupDecryptException
import com.yiqiu.shirohaquiz.security.BackupEncryption

/**
 * 同步层的钥匙集合。
 *
 * 方案变更（首辅拍板）：密钥材料由「设备本地恢复码」改成 **WebDAV 账号 + 密码**，
 * 这样新设备只要填同一组地址/账号/密码就能解开云端数据，不必再搬一串码过来。
 *
 * 兼容：解密**先用账号密码派生的主钥匙**；失败再用本地仍存着的旧恢复码兜底。
 * 命中旧码时返回 [Opened.usedLegacy]，由调用方**立刻用主钥匙重加密覆盖上传**——
 * 按对象粒度、幂等（已经是主钥匙的对象不会再走这条路），因此可中断重入。
 */
class SyncKeys(
    private val primary: ByteArray,
    private val legacy: ByteArray? = null
) {
    data class Opened(val plaintext: ByteArray, val usedLegacy: Boolean)

    val hasLegacy: Boolean get() = legacy != null

    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray =
        SyncPayloadCipher.encryptWithKey(plaintext, primary, aad)

    fun open(payload: ByteArray, aad: ByteArray): Opened {
        try {
            return Opened(SyncPayloadCipher.decryptWithKey(payload, primary, aad), usedLegacy = false)
        } catch (primaryFailure: BackupDecryptException) {
            val legacyKey = legacy ?: throw credentialMismatch(primaryFailure)
            try {
                return Opened(SyncPayloadCipher.decryptWithKey(payload, legacyKey, aad), usedLegacy = true)
            } catch (legacyFailure: BackupDecryptException) {
                throw credentialMismatch(legacyFailure)
            }
        }
    }

    fun encryptManifest(manifest: SyncManifest): ByteArray =
        SyncManifestCodec.encryptWithKey(manifest, primary)

    data class OpenedManifest(val manifest: SyncManifest, val usedLegacy: Boolean)

    fun openManifest(payload: ByteArray): OpenedManifest {
        try {
            return OpenedManifest(SyncManifestCodec.decryptWithKey(payload, primary), usedLegacy = false)
        } catch (primaryFailure: BackupDecryptException) {
            val legacyKey = legacy ?: throw credentialMismatch(primaryFailure)
            try {
                return OpenedManifest(SyncManifestCodec.decryptWithKey(payload, legacyKey), usedLegacy = true)
            } catch (legacyFailure: BackupDecryptException) {
                throw credentialMismatch(legacyFailure)
            }
        }
    }

    /** 给用户看的话必须是人话：不要甩 InvalidTag。 */
    private fun credentialMismatch(cause: Throwable): BackupDecryptException = BackupDecryptException(
        "账号或密码与云端数据不匹配：请确认填的是当初加密这份数据时使用的 WebDAV 账号与密码。" +
            "（改密码 = 换钥匙，需先在本机完成重加密再改服务器密码。）",
        cause
    )

    companion object {
        fun fromCredentials(username: String, password: String, legacyRecoveryCode: String?): SyncKeys {
            val material = BackupEncryption.credentialMaterial(username, password)
            return SyncKeys(
                primary = BackupEncryption.deriveKeyFromMaterial(material),
                legacy = legacyRecoveryCode?.takeIf { it.isNotBlank() }?.let { code ->
                    runCatching { BackupEncryption.deriveKey(code) }.getOrNull()
                }
            )
        }

        /** 还没有密码（或读不到密码）时退回旧码单钥匙，保证老设备仍能用。 */
        fun fromLegacyRecoveryCode(recoveryCode: String): SyncKeys =
            SyncKeys(primary = BackupEncryption.deriveKey(recoveryCode))
    }
}
