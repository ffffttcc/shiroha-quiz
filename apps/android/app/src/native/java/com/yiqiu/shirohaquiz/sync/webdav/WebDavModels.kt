package com.yiqiu.shirohaquiz.sync.webdav

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CancellationException

data class WebDavSettings(
    val baseUrl: String = "",
    val username: String = "",
    val remoteDirectory: String = DEFAULT_REMOTE_DIRECTORY,
    val rememberPassword: Boolean = false
) {
    fun validated(): WebDavSettings {
        val url = WebDavPaths.baseUrl(baseUrl)
        require(username.isNotBlank() && ':' !in username && username.none { it.isISOControl() }) {
            "WebDAV 用户名为空或格式无效。"
        }
        return copy(baseUrl = url.toString(), remoteDirectory = WebDavPaths.directory(remoteDirectory))
    }

    companion object {
        const val DEFAULT_REMOTE_DIRECTORY = "/ShirohaQuiz/Backups/"
    }
}

/** Not a data class: credentials must never appear in generated toString/state/backup output. */
class WebDavCredentials(val username: String, val password: String) {
    init {
        require(username.isNotBlank() && ':' !in username && username.none { it.isISOControl() }) {
            "WebDAV 用户名为空或格式无效。"
        }
        require(password.isNotEmpty() && password.none { it == '\r' || it == '\n' }) {
            "WebDAV 密码为空或包含换行。"
        }
    }

    override fun toString(): String = "WebDavCredentials(redacted)"
}

data class WebDavResource(
    val fileName: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAt: Long?,
    val etag: String? = null
)

data class WebDavBackupEntry(
    val fileName: String,
    val createdAt: Long?,
    val sizeBytes: Long,
    val source: String = "unknown",
    val appVersion: String = "unknown",
    val sha256: String? = null,
    val bankCount: Int? = null,
    val questionCount: Int? = null,
    val includesWrongBook: Boolean? = null,
    val includesFavorites: Boolean? = null,
    val includesStudyRecords: Boolean? = null,
    val etag: String? = null
) {
    /**
     * 是否可以安全下载。
     *
     * 口径（**加密与非加密不同，别再混用**）：
     * - 非加密（.zip/.json）：必须有大小 + 明文 sha256，下载时两者都校验；
     * - 加密（.zip.enc）：文件名里的 sha256 是**解密后明文**的摘要，不能拿来比密文，
     *   因此这里只要求大小可用；密文完整性由 GCM 标签保证，明文完整性在解密后单独核对。
     */
    val integrityAvailable: Boolean
        get() = sizeBytes > 0 && (WebDavPaths.isSha256(sha256) || WebDavPaths.isEncryptedName(fileName))

    /** 下载时是否应该比对**密文**哈希：加密对象一律不比（比的是明文摘要，永远不等）。 */
    val verifiesCiphertextHash: Boolean get() = WebDavPaths.isEncryptedName(fileName) == false

    internal fun toMetadataJson(): JSONObject = JSONObject()
        .put("app", "Shiroha Quiz")
        .put("schemaVersion", 1)
        .put("fileName", fileName)
        .put("createdAt", createdAt)
        .put("size", sizeBytes)
        .put("source", source)
        .put("appVersion", appVersion)
        .put("sha256", sha256)
        .put("bankCount", bankCount)
        .put("questionCount", questionCount)
        .put("includesWrongBook", includesWrongBook)
        .put("includesFavorites", includesFavorites)
        .put("includesStudyRecords", includesStudyRecords)

    companion object {
        internal fun fromMetadataJson(root: JSONObject, resource: WebDavResource): WebDavBackupEntry? {
            if (root.optString("fileName") != resource.fileName) return null
            val size = root.optLong("size", -1)
            if (size <= 0 || (resource.sizeBytes != null && resource.sizeBytes != size)) return null
            val sha = root.optString("sha256").lowercase(Locale.ROOT)
            if (!WebDavPaths.isSha256(sha)) return null
            return WebDavBackupEntry(
                fileName = resource.fileName,
                createdAt = root.optLong("createdAt", 0).takeIf { it > 0 } ?: resource.modifiedAt,
                sizeBytes = size,
                source = root.optString("source", "unknown").take(40),
                appVersion = root.optString("appVersion", "unknown").take(80),
                sha256 = sha,
                bankCount = root.optionalCount("bankCount"),
                questionCount = root.optionalCount("questionCount"),
                includesWrongBook = root.optionalBoolean("includesWrongBook"),
                includesFavorites = root.optionalBoolean("includesFavorites"),
                includesStudyRecords = root.optionalBoolean("includesStudyRecords"),
                etag = resource.etag
            )
        }

        private fun JSONObject.optionalCount(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key, -1).takeIf { it >= 0 } else null

        private fun JSONObject.optionalBoolean(key: String): Boolean? =
            if (has(key) && !isNull(key)) optBoolean(key) else null
    }
}

/** Map the parent's BackupContentPreview into this type; never mutate content while previewing. */
data class WebDavBackupPreview(
    val bankCount: Int,
    val questionCount: Int,
    val includesWrongBook: Boolean = false,
    val includesFavorites: Boolean = false,
    val includesStudyRecords: Boolean = false,
    val source: String = "unknown",
    val appVersion: String = "unknown",
    val message: String = "",
    val canRestore: Boolean = true,
    val canReplaceContent: Boolean = true,
    val wrongCount: Int = 0,
    val favoriteCount: Int = 0,
    val recordCount: Int = 0,
    val assetCount: Int = 0,
    val slashedCount: Int = 0,
    val sourceVersion: Int? = null,
    val sourceKind: String = "unknown",
    val exportedAt: Long? = null
)

/** Both mutation callbacks run on Main; replacement MUST preserve device/application settings. */
class WebDavBackupHooks(
    val previewBackupBytes: (ByteArray) -> WebDavBackupPreview,
    val replaceContentFromBackupBytes: (ByteArray) -> String,
    val exportFullBackupZip: (() -> ByteArray)? = null,
    val importCopyFromBackupBytes: ((String, ByteArray) -> String)? = null
)

enum class WebDavRestoreMode { IMPORT_COPY, REPLACE_CONTENT_KEEP_SETTINGS }
enum class WebDavTaskKind { TEST_CONNECTION, CREATE_DIRECTORY, LIST, UPLOAD, PREVIEW, DOWNLOAD, RESTORE, DELETE, CLEAN_CACHE }
enum class WebDavTaskStage { RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }

data class WebDavTaskState(
    val id: String,
    val kind: WebDavTaskKind,
    val stage: WebDavTaskStage = WebDavTaskStage.RUNNING,
    val fileName: String? = null,
    val transferredBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val startedAt: Long = System.currentTimeMillis()
)

/** Only a token/summary is public. The verified file remains private under noBackupFilesDir. */
data class WebDavPreparedRestore(
    val token: String,
    val entry: WebDavBackupEntry,
    val preview: WebDavBackupPreview,
    val expiresAt: Long
)

data class WebDavUploadResult(val entry: WebDavBackupEntry, val metadataPublished: Boolean)
data class WebDavDeleteResult(val fileDeleted: Boolean, val metadataDeleted: Boolean)
data class WebDavTransferResult(val sizeBytes: Long, val sha256: String)

open class WebDavException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : IOException(message, cause)
class WebDavIntegrityException(message: String) : WebDavException(message)

/** One token per workflow. Cancellation stays sticky between requests and during file hashing. */
class WebDavCancellation {
    private val calls = mutableSetOf<Call>()
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled

    @Synchronized fun cancel() {
        cancelled = true
        calls.toList().forEach { it.cancel() }
    }

    fun throwIfCancelled() {
        if (cancelled) throw CancellationException("WebDAV 任务已取消。")
    }

    @Synchronized internal fun register(call: Call) {
        throwIfCancelled()
        calls.add(call)
    }

    @Synchronized internal fun unregister(call: Call) { calls.remove(call) }
}

internal object WebDavPaths {
    private val filePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")
    private val shaPattern = Regex("[a-fA-F0-9]{64}")
    private val generatedPattern = Regex(
        "shiroha-quiz-backup-(\\d{8}-\\d{9})-([a-f0-9]{32})-(android|web)-v([A-Za-z0-9._-]+)-sha256-([a-f0-9]{64})\\.(zip|json)(\\.enc)?"
    )

    /** 只接受 https 地址：明文 http 在解析阶段就被拒（主线语义，任何构建都一样）。 */
    fun baseUrl(text: String): HttpUrl {
        val trimmed = text.trim()
        require(trimmed.startsWith("https://", true)) { "WebDAV 仅支持 HTTPS 地址。" }
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("WebDAV 地址无效。")
        require(uri.rawUserInfo == null) { "WebDAV 地址内嵌凭据：username/password 必须为空" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "WebDAV 地址不得包含查询串或片段" }
        val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("WebDAV 地址无效。")
        require(url.isHttps) { "WebDAV 协议不合：需要 https，当前为：${url.scheme}" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "WebDAV 地址内嵌凭据：username/password 必须为空"
        }
        requireSafeEncodedPath(uri.rawPath.orEmpty())
        return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
    }

    fun directory(raw: String): String {
        require(raw.startsWith('/') && raw.length <= 1024) { "远端目录必须以 / 开头，且长度不能超过限制。"
        }
        val parts = raw.trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() && parts.size <= 32) { "远端目录层级不合法（段数 ${parts.size}）：$raw" }
        parts.forEach { part ->
            require(part != "." && part != ".." && part.length <= 120 &&
                part.none { it.isISOControl() || it in "\\%?#:" }) { "远端目录含不安全的字符或路径。"
            }
        }
        return "/${parts.joinToString("/")}/"
    }

    fun safeFileName(name: String): String {
        require(filePattern.matches(name) && !name.contains("..")) { "远端文件名含不安全的字符或路径。"
        }
        return name
    }

    /** 密文备份在正常文件名后多一个 .enc；识别时先剥掉它再判断内容类型。 */
    const val ENCRYPTED_SUFFIX = ".enc"

    fun isEncryptedName(name: String): Boolean = name.endsWith(ENCRYPTED_SUFFIX)

    fun isBackup(name: String): Boolean = runCatching {
        safeFileName(name)
        val contentName = name.removeSuffix(ENCRYPTED_SUFFIX)
        name.startsWith("shiroha-quiz-backup-") &&
            (contentName.endsWith(".zip") || contentName.endsWith(".json")) &&
            !name.endsWith(".meta.json") && !name.contains(".uploading") && !name.contains(".tmp")
    }.getOrDefault(false)

    fun isSha256(value: String?): Boolean = value != null && shaPattern.matches(value)

    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    fun requireSafeEncodedPath(path: String) {
        require(!Regex("(?i)%2f|%5c|%25|%00").containsMatchIn(path) && '\\' !in path) {
            "远端路径含不安全的转义序列：$path"
        }
        path.split('/').forEach { segment ->
            val dots = segment.replace(Regex("(?i)%2e"), ".")
            require(dots != "." && dots != "..") { "远端路径不安全，已拒绝访问。"
            }
        }
    }

    fun newBackupName(createdAt: Long, version: String, sha256: String, encrypted: Boolean = false): String {
        require(isSha256(sha256)) { "SHA256 摘要格式无效：$sha256" }
        val stamp = formatter().format(Date(createdAt))
        val safeVersion = version.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifEmpty { "unknown" }
        val base = "shiroha-quiz-backup-$stamp-${UUID.randomUUID().toString().replace("-", "")}-android-v$safeVersion-sha256-${sha256.lowercase(Locale.ROOT)}.zip"
        return safeFileName(if (encrypted) base + ENCRYPTED_SUFFIX else base)
    }

    /** Immutable names retain basic metadata even if the optional sidecar is missing/corrupt. */
    fun fallbackEntry(resource: WebDavResource): WebDavBackupEntry {
        val match = generatedPattern.matchEntire(resource.fileName)
        return WebDavBackupEntry(
            fileName = resource.fileName,
            createdAt = match?.let { runCatching { formatter().parse(it.groupValues[1])?.time }.getOrNull() }
                ?: resource.modifiedAt,
            sizeBytes = resource.sizeBytes ?: -1,
            source = match?.groupValues?.get(3) ?: when {
                resource.fileName.endsWith("-android.zip") || resource.fileName.endsWith("-android.zip.enc") -> "android"
                resource.fileName.endsWith("-web.zip") || resource.fileName.endsWith("-web.zip.enc") -> "web"
                else -> "unknown"
            },
            appVersion = match?.groupValues?.get(4) ?: "unknown",
            sha256 = match?.groupValues?.get(5),
            etag = resource.etag
        )
    }

    fun digestHex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it.toInt() and 255) }

    /** 供解密时把文件名里的 SHA256 还原成 AAD 需要的原始字节。 */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && shaPattern.matches(hex)) { "校验值格式无效。" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
    fun equalSha256(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII),
        b.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
    )

    private fun formatter() = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}
