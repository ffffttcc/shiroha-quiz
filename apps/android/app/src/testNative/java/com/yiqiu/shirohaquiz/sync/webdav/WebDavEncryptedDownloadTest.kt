package com.yiqiu.shirohaquiz.sync.webdav

import com.yiqiu.shirohaquiz.sync.sync.BackupMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * 加密备份的下载完整性口径（第四个同类问题的钉子）。
 *
 * 约定（三层各司其职，别再一人一个理解）：
 * - 下载：只管**字节完整**（真实大小；非加密对象还比字节哈希）
 * - 密文完整性：GCM 认证标签
 * - 明文完整性：解密后拿文件名里的 sha256 对**明文**核对
 *
 * 事故：`.zip.enc` 文件名里的 sha256 是**明文**摘要，却被拿去比**密文**字节 → 永远不等，
 * 于是「迁移」和「恢复这份备份」两条路都必然报「SHA256 摘要或大小校验失败」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDavEncryptedDownloadTest {

    private val dav = WebDavClientTest.FakeDav()
    private val client = WebDavClient(
        "https://dav.example.com/dav/",
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("user", "secret"),
        callFactory = dav,
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )
    private val plaintext = ByteArray(1024) { (it % 251).toByte() }
    private val plaintextHex = MessageDigest.getInstance("SHA-256").digest(plaintext)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private val ciphertext = ByteArray(1200) { (it * 7 % 256).toByte() }

    private fun sha(hex: String) = hex

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("enc-test", ".bin").apply { writeBytes(bytes) }

    private fun cacheFile(): File = File(
        File(RuntimeEnvironment.getApplication().noBackupFilesDir, "cache-test").apply { mkdirs() },
        "dest-${System.nanoTime()}.bin"
    )

    /** ① `.zip.enc` 用「明文摘要」当期望值也必须能下载成功（不比密文哈希）。 */
    @Test
    fun encryptedBackupDownloadsEvenWithPlaintextHashInName() {
        val name = WebDavPaths.newBackupName(1_700_000_000_000L, "0.9.16", sha(plaintextHex), encrypted = true)
        client.uploadAtomic(tempFile(ciphertext), name)
        val entry = WebDavBackupEntry(
            fileName = name,
            createdAt = 1_700_000_000_000L,
            sizeBytes = ciphertext.size.toLong(),
            sha256 = sha(plaintextHex)
        )
        assertEquals(false, entry.verifiesCiphertextHash)
        val dest = cacheFile()
        val result = client.download(entry, dest)
        assertEquals(ciphertext.size.toLong(), result.sizeBytes)
        dest.delete()
    }

    /** ② 解密后明文摘要与文件名不符时**必须报错**（不能因为不比密文就整体放宽）。 */
    @Test
    fun plaintextHashMismatchIsRejected() {
        BackupMigration.verifyPlaintext(plaintext, sha(plaintextHex))  // 一致放行
        val error = assertThrows(WebDavIntegrityException::class.java) {
            BackupMigration.verifyPlaintext(plaintext, sha("00".repeat(32)))
        }
        assertEquals(true, error.message.orEmpty().contains("摘要不一致"))
    }

    /** ③ 非加密对象仍按字节哈希严格校验，不许放宽。 */
    @Test
    fun plainBackupStillVerifiesByteHash() {
        val name = WebDavPaths.newBackupName(1_700_000_000_000L, "0.9.16", sha(plaintextHex), encrypted = false)
        val wrongHex = "11".repeat(32)
        client.uploadAtomic(tempFile(ciphertext), name)
        val entry = WebDavBackupEntry(
            fileName = name,
            createdAt = 1_700_000_000_000L,
            sizeBytes = ciphertext.size.toLong(),
            sha256 = sha(wrongHex)
        )
        assertEquals(true, entry.verifiesCiphertextHash)
        assertThrows(WebDavIntegrityException::class.java) { client.download(entry, cacheFile()) }
    }
}
