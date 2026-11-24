package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.security.BackupDecryptException
import com.yiqiu.shirohaquiz.security.BackupEncryption
import com.yiqiu.shirohaquiz.sync.records.RecordKind
import com.yiqiu.shirohaquiz.sync.records.Tombstone
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 同步对象在云端的固定名字（扁平放在同一个 WebDAV 目录里，不建子目录）。 */
object SyncObjects {
    const val MANIFEST = "shiroha-sync-manifest.enc"
    private const val BANK_PREFIX = "shiroha-sync-bank-"
    private const val RECORD_PREFIX = "shiroha-sync-record-"

    fun bankName(contentHash: String): String = "$BANK_PREFIX$contentHash.enc"

    /** 记录事件分片：扁平命名（WebDavClient 的名字白名单不接受子目录）。 */
    fun recordName(shardHash: String): String = "$RECORD_PREFIX$shardHash.enc"

    fun isSyncObject(name: String): Boolean =
        name == MANIFEST ||
            (name.startsWith(BANK_PREFIX) && name.endsWith(".enc")) ||
            (name.startsWith(RECORD_PREFIX) && name.endsWith(".enc"))

    fun bankHashOf(name: String): String? =
        if (name.startsWith(BANK_PREFIX) && name.endsWith(".enc")) {
            name.removePrefix(BANK_PREFIX).removeSuffix(".enc").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        } else null
}

/**
 * 同步侧密文容器：与备份加密同一个 key（同一恢复码）、同样 `SHQ1` 头，只是 AAD 规则不同。
 *
 * 备份那边 AAD 需要「明文 SHA256」，而 manifest 在下载时还不知道明文哈希，
 * 所以这里把 AAD 显式传进来：
 * - 题库 blob：AAD = ver + contentHash 前 8 字节（hash 就在文件名里，双方都能算）；
 * - manifest：AAD 只放 ver（其余靠 GCM tag 自身完整性）。
 */
object SyncPayloadCipher {
    const val MAGIC = "SHQ1"
    private const val NONCE_LENGTH = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private const val AAD_HASH_PREFIX = 8
    private const val HEADER_FIXED = 7

    fun aadForManifest(): ByteArray = byteArrayOf(BackupEncryption.VERSION)

    fun aadForContent(contentHashHex: String): ByteArray =
        byteArrayOf(BackupEncryption.VERSION) +
            hexToBytes(contentHashHex).copyOf(AAD_HASH_PREFIX)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.matches(Regex("[a-f0-9A-F]*"))) { "contentHash 格式无效。" }
        return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun encrypt(plaintext: ByteArray, recoveryCode: String, aad: ByteArray): ByteArray =
        encryptWithKey(plaintext, BackupEncryption.deriveKey(recoveryCode), aad)

    /** 直接用钥匙字节加密（账号密码派生方案走这里，避免每次重跑 21 万轮 KDF）。 */
    fun encryptWithKey(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plaintext)
        return ByteArrayOutputStream(HEADER_FIXED + nonce.size + body.size).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(BackupEncryption.VERSION.toInt())
            write(BackupEncryption.SALT_VERSION.toInt())
            write(NONCE_LENGTH)
            write(nonce)
            write(body)
        }.toByteArray()
    }

    fun decrypt(payload: ByteArray, recoveryCode: String, aad: ByteArray): ByteArray =
        decryptWithKey(payload, BackupEncryption.deriveKey(recoveryCode), aad)

    /** 直接用钥匙字节解密（失败抛 BackupDecryptException）。 */
    fun decryptWithKey(payload: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        if (payload.size < HEADER_FIXED + NONCE_LENGTH + TAG_BYTES) {
            throw BackupDecryptException("同步数据长度异常，可能被截断。")
        }
        if (String(payload.copyOfRange(0, 4), Charsets.US_ASCII) != MAGIC) {
            throw BackupDecryptException("这不是加密的同步数据。")
        }
        if (payload[4] != BackupEncryption.VERSION) throw BackupDecryptException("不支持的同步加密版本。")
        val nonceLength = payload[6].toInt() and 0xFF
        if (nonceLength != NONCE_LENGTH) throw BackupDecryptException("同步数据的 nonce 长度异常。")
        val nonce = payload.copyOfRange(HEADER_FIXED, HEADER_FIXED + nonceLength)
        val body = payload.copyOfRange(HEADER_FIXED + nonceLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(body)
        } catch (error: Exception) {
            throw BackupDecryptException("同步数据解密失败：恢复码不匹配或数据已损坏。", error)
        }
    }
}

/** 单库在 manifest 里的那条记录。 */
data class SyncBankRecord(
    val groupName: String,
    val name: String,
    val contentHash: String,
    val updatedAt: Long
)

/** 记录类（错题/收藏/学习记录）整份快照：只比 hash，不做逐条合并。 */
data class SyncRecordSnapshot(
    val contentHash: String,
    val updatedAt: Long
)

/**
 * 云端清单。deviceId 只用来排查「这次是谁赢的」，**不参与冲突判定**。
 *
 * v2 新增（v1 清单缺这些字段时按空/default 读，保证向下兼容）：
 * - [epoch]：单调递增整数，只在「本轮确实写了变更」时 +1，用于 tombstone 裁决，不碰墙钟；
 * - [recordShards]：记录事件分片哈希列表（云端对象见 [SyncObjects.recordName]）；
 * - [bankTombstones] / [recordTombstones]：库级与记录级删除标记。
 */
data class SyncManifest(
    val deviceId: String,
    val updatedAt: Long,
    val banks: Map<String, SyncBankRecord>,
    val recordSnapshots: Map<String, SyncRecordSnapshot> = emptyMap(),
    val version: Int = CURRENT_VERSION,
    val epoch: Long = 0L,
    val recordShards: List<String> = emptyList(),
    val bankTombstones: List<Tombstone> = emptyList(),
    val recordTombstones: List<Tombstone> = emptyList(),
    /** 跨设备练习进度：scopeKey -> 精简快照（不含题目正文）。用 JSON 字符串存，便于 data class 比较。 */
    val practiceProgressJson: String = ""
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

object SyncManifestCodec {
    fun toJson(manifest: SyncManifest): ByteArray {
        val root = JSONObject()
        root.put("version", manifest.version)
        root.put("deviceId", manifest.deviceId)
        root.put("updatedAt", manifest.updatedAt)
        val banks = JSONObject()
        manifest.banks.toSortedMap().forEach { (key, record) ->
            banks.put(key, JSONObject().apply {
                put("groupName", record.groupName)
                put("name", record.name)
                put("contentHash", record.contentHash)
                put("updatedAt", record.updatedAt)
            })
        }
        root.put("banks", banks)
        val records = JSONObject()
        manifest.recordSnapshots.toSortedMap().forEach { (key, snapshot) ->
            records.put(key, JSONObject().apply {
                put("contentHash", snapshot.contentHash)
                put("updatedAt", snapshot.updatedAt)
            })
        }
        root.put("recordSnapshots", records)
        // v2 字段。v1 客户端读到未知字段会忽略，故此处可安全写入。
        root.put("epoch", manifest.epoch)
        val shards = JSONArray()
        manifest.recordShards.sorted().forEach { shard -> shards.put(shard) }
        root.put("recordShards", shards)
        root.put("bankTombstones", tombstonesToJson(manifest.bankTombstones))
        root.put("recordTombstones", tombstonesToJson(manifest.recordTombstones))
        if (manifest.practiceProgressJson.isNotBlank()) {
            root.put("practiceProgress", JSONObject(manifest.practiceProgressJson))
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun tombstonesToJson(tombstones: List<Tombstone>): JSONArray {
        val array = JSONArray()
        tombstones
            .sortedWith(compareBy({ it.kind.name }, { it.targetKey }))
            .forEach { tombstone ->
                array.put(JSONObject().apply {
                    put("kind", tombstone.kind.name)
                    put("targetKey", tombstone.targetKey)
                    put("epoch", tombstone.epoch)
                    put("deviceId", tombstone.deviceId)
                    put("deleted", tombstone.deleted)
                })
            }
        return array
    }

    private fun tombstonesFromJson(array: JSONArray?): List<Tombstone> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val kind = runCatching { RecordKind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
                add(
                    Tombstone(
                        kind = kind,
                        targetKey = item.optString("targetKey"),
                        epoch = item.optLong("epoch", 0L),
                        deviceId = item.optString("deviceId"),
                        deleted = item.optBoolean("deleted", true)
                    )
                )
            }
        }
    }

    fun fromJson(bytes: ByteArray): SyncManifest {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = root.optInt("version", SyncManifest.CURRENT_VERSION)
        require(version <= SyncManifest.CURRENT_VERSION) { "同步清单版本过新，请升级 App 后再同步。" }
        val banksJson = root.optJSONObject("banks") ?: JSONObject()
        val banks = mutableMapOf<String, SyncBankRecord>()
        val keys = banksJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = banksJson.optJSONObject(key) ?: continue
            banks[key] = SyncBankRecord(
                groupName = item.optString("groupName"),
                name = item.optString("name"),
                contentHash = item.optString("contentHash"),
                updatedAt = item.optLong("updatedAt", 0L)
            )
        }
        val recordsJson = root.optJSONObject("recordSnapshots") ?: JSONObject()
        val snapshots = mutableMapOf<String, SyncRecordSnapshot>()
        val recordKeys = recordsJson.keys()
        while (recordKeys.hasNext()) {
            val key = recordKeys.next()
            val item = recordsJson.optJSONObject(key) ?: continue
            snapshots[key] = SyncRecordSnapshot(
                contentHash = item.optString("contentHash"),
                updatedAt = item.optLong("updatedAt", 0L)
            )
        }
        val shardsJson = root.optJSONArray("recordShards")
        val shards = buildList {
            if (shardsJson != null) {
                for (index in 0 until shardsJson.length()) {
                    val shard = shardsJson.optString(index)
                    if (shard.length == 64 && shard.matches(Regex("[a-f0-9]{64}"))) add(shard)
                }
            }
        }
        return SyncManifest(
            deviceId = root.optString("deviceId"),
            updatedAt = root.optLong("updatedAt", 0L),
            banks = banks,
            recordSnapshots = snapshots,
            version = version,
            // v1 清单没有这些字段：按 default 读入，保证向下兼容。
            epoch = root.optLong("epoch", 0L),
            recordShards = shards,
            bankTombstones = tombstonesFromJson(root.optJSONArray("bankTombstones")),
            recordTombstones = tombstonesFromJson(root.optJSONArray("recordTombstones")),
            practiceProgressJson = root.optJSONObject("practiceProgress")?.toString() ?: ""
        )
    }

    fun encrypt(manifest: SyncManifest, recoveryCode: String): ByteArray =
        SyncPayloadCipher.encrypt(toJson(manifest), recoveryCode, SyncPayloadCipher.aadForManifest())

    fun encryptWithKey(manifest: SyncManifest, key: ByteArray): ByteArray =
        SyncPayloadCipher.encryptWithKey(toJson(manifest), key, SyncPayloadCipher.aadForManifest())

    fun decrypt(payload: ByteArray, recoveryCode: String): SyncManifest =
        fromJson(SyncPayloadCipher.decrypt(payload, recoveryCode, SyncPayloadCipher.aadForManifest()))

    fun decryptWithKey(payload: ByteArray, key: ByteArray): SyncManifest =
        fromJson(SyncPayloadCipher.decryptWithKey(payload, key, SyncPayloadCipher.aadForManifest()))
}
