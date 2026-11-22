package com.yiqiu.shirohaquiz.security

import android.content.Context
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 解密失败（恢复码不对 / 文件被改 / 结构损坏）。调用方必须先报错再决定是否继续，不得写入数据。 */
class BackupDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 备份包端到端加密：只在备份 ZIP 外面包一层，ZIP 内部一字节不变。
 *
 * 文件结构：`"SHQ1" | ver(1B) | saltVer(1B) | nonceLen(1B) | nonce | ciphertext‖tag(16B)`
 * - 密钥：PBKDF2WithHmacSHA256，salt = 固定常量 + saltVer，210000 轮，256-bit
 * - 加密：AES-GCM-256，随机 12 字节 nonce，GCM 自身把 16 字节 tag 附在密文尾部
 * - AAD：ver 字节 + 明文 SHA256 前 8 字节（把密文和"这是哪份内容"绑在一起）
 *
 * 线程契约：本对象**没有任何 Android 依赖、也不碰 IO**，派生与加解密都是纯 CPU 计算，
 * 调用方（WebDavBackupManager）负责放到 Dispatchers.IO。绝不在 init()/冷启动路径调用。
 */
object BackupEncryption {
    const val MAGIC = "SHQ1"
    const val VERSION: Byte = 1
    const val SALT_VERSION: Byte = 1
    const val RECOVERY_CODE_LENGTH = 32

    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val NONCE_LENGTH = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private const val AAD_SHA256_PREFIX = 8
    private const val SALT_PREFIX = "shiroha-sync-v1"
    private const val HEADER_FIXED = 4 + 1 + 1 + 1
    private const val MIN_PAYLOAD_BYTES = HEADER_FIXED + NONCE_LENGTH + TAG_BYTES

    /** 去掉了 I/L/O/0/1 这些易混字符的 32 字符表，方便用户手抄。 */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 派生结果驻内存：同一次进程内同一个恢复码只付一次 21 万轮的开销。key 是码的 SHA256。 */
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    /** 识别密文包（旧明文 ZIP 永远返回 false，从而保持可恢复）。 */
    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_PAYLOAD_BYTES) return false
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        for (index in magic.indices) {
            if (bytes[index] != magic[index]) return false
        }
        return true
    }

    fun generateRecoveryCode(random: SecureRandom = SecureRandom()): String {
        val raw = ByteArray(RECOVERY_CODE_LENGTH).also(random::nextBytes)
        return raw.joinToString("") { byte ->
            ALPHABET[((byte.toInt() and 0xFF) % ALPHABET.length)].toString()
        }
    }

    /** 展示用：8 个一组，方便抄写；参与派生前会被 [normalizeRecoveryCode] 还原。 */
    fun formatRecoveryCode(code: String): String =
        normalizeRecoveryCode(code).chunked(8).joinToString("-")

    fun normalizeRecoveryCode(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }

    /** 旧恢复码派生（长度必须 >= 32，保持原有语义不变）。 */
    fun deriveKey(recoveryCode: String): ByteArray {
        val normalized = normalizeRecoveryCode(recoveryCode)
        require(normalized.length >= RECOVERY_CODE_LENGTH) { "恢复码长度不足，无法派生密钥。" }
        return deriveKeyFromMaterial(normalized)
    }

    /**
     * 用**任意长度**的密钥材料派生（不归一、不做长度校验）。
     *
     * 这是「账号 + 密码」方案要用的入口：密码可能只有 6~20 位，绝不能被恢复码那条
     * `>= 32` 的规则拒掉。材料串由调用方保证已归一化（见 [credentialMaterial]）。
     */
    fun deriveKeyFromMaterial(material: String): ByteArray {
        require(material.isNotEmpty()) { "密钥材料为空，无法派生密钥。" }
        val cacheKey = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        keyCache[cacheKey]?.let { return it.copyOf() }
        val salt = SALT_PREFIX.toByteArray(Charsets.UTF_8) + byteArrayOf(SALT_VERSION)
        val spec = PBEKeySpec(material.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val derived = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        keyCache[cacheKey] = derived
        return derived.copyOf()
    }

    /**
     * 凭据 → 密钥材料串：`normalize(username) + "\u0000" + normalize(password)`。
     *
     * 为什么先各自归一再加分隔符：`normalizeRecoveryCode` 只保留字母数字，
     * 若先拼接再归一，分隔符会被吃掉，`("ab","cd")` 与 `("abc","d")` 就会派生出同一把钥匙。
     * 字母数字被归一成大写后不可能含 `\u0000`，分隔符因此恒定有效。
     */
    fun credentialMaterial(username: String, password: String): String =
        normalizeRecoveryCode(username) + "\u0000" + normalizeRecoveryCode(password)

    /**
     * @param plaintextSha256 明文（原始 ZIP）的 SHA256，用于 AAD 与恢复后核对。
     */
    fun encrypt(
        plaintext: ByteArray,
        recoveryCode: String,
        plaintextSha256: ByteArray,
        random: SecureRandom = SecureRandom()
    ): ByteArray {
        require(plaintextSha256.size == 32) { "明文摘要必须是 SHA256。" }
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(recoveryCode), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(plaintextSha256))
        val body = cipher.doFinal(plaintext)
        return ByteArrayOutputStream(MIN_PAYLOAD_BYTES + body.size).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(VERSION.toInt())
            write(SALT_VERSION.toInt())
            write(NONCE_LENGTH)
            write(nonce)
            write(body)
        }.toByteArray()
    }

    /**
     * 用恢复码解出原始 ZIP 字节。任何结构/认证失败都抛 [BackupDecryptException]，
     * 不返回半成品，绝不静默降级。
     */
    fun decrypt(payload: ByteArray, recoveryCode: String, plaintextSha256: ByteArray): ByteArray {
        require(plaintextSha256.size == 32) { "明文摘要必须是 SHA256。" }
        if (!isEncrypted(payload)) throw BackupDecryptException("这不是加密备份文件。")
        if (payload.size < MIN_PAYLOAD_BYTES) throw BackupDecryptException("加密备份长度异常，文件可能被截断。")
        val version = payload[4]
        if (version != VERSION) throw BackupDecryptException("不支持的备份加密版本（$version）。")
        val saltVersion = payload[5]
        if (saltVersion != SALT_VERSION) throw BackupDecryptException("不支持的密钥派生版本（$saltVersion）。")
        val nonceLength = payload[6].toInt() and 0xFF
        if (nonceLength != NONCE_LENGTH) throw BackupDecryptException("加密备份的 nonce 长度异常。")
        if (payload.size < HEADER_FIXED + nonceLength + TAG_BYTES) {
            throw BackupDecryptException("加密备份长度异常，文件可能被截断。")
        }
        val nonce = payload.copyOfRange(HEADER_FIXED, HEADER_FIXED + nonceLength)
        val body = payload.copyOfRange(HEADER_FIXED + nonceLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(recoveryCode), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(plaintextSha256))
        val plaintext = try {
            cipher.doFinal(body)
        } catch (error: Exception) {
            throw BackupDecryptException("恢复码不正确，或备份文件已损坏/被篡改。", error)
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(plaintext)
        if (!MessageDigest.isEqual(actual, plaintextSha256)) {
            throw BackupDecryptException("解密结果与备份记录的校验值不一致，已放弃写入。")
        }
        return plaintext
    }

    private fun aad(plaintextSha256: ByteArray): ByteArray =
        byteArrayOf(VERSION) + plaintextSha256.copyOf(AAD_SHA256_PREFIX)
}

/**
 * 恢复码存 [SecureSecretStore]（与 WebDAV 密码同一处），不进备份、不进普通 prefs。
 * 恢复码一旦丢失，服务端上的加密备份永久无法解开——这一点由 UI 明示。
 */
class BackupRecoveryCodeStore(
    context: Context,
    private val secrets: SecureSecretStore = SecureSecretStore(context)
) {
    fun existing(): String? = secrets.get(KEY)?.let(BackupEncryption::normalizeRecoveryCode)?.takeIf { it.isNotEmpty() }

    /** 惰性生成：只有真正要加密上传/查看恢复码时才会创建。 */
    fun ensure(): String = existing() ?: BackupEncryption.generateRecoveryCode().also { secrets.put(KEY, it) }

    fun clear() = secrets.remove(KEY)

    companion object {
        const val KEY = "webdav_backup_recovery_code"
    }
}
