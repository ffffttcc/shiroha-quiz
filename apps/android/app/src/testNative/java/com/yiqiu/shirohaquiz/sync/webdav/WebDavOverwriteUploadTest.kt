package com.yiqiu.shirohaquiz.sync.webdav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * 覆盖上传的口径：`uploadAtomic(overwrite=false)` 必须拒绝覆盖，
 * 只有迁移路径（`overwrite=true`）才允许同名覆盖。
 *
 * 真机事故：迁移用默认保守语义去覆盖旧 `.zip.enc`，MOVE `Overwrite: F` → 412 →
 * 「远端文件已存在或发生变化，未执行覆盖」，3 份旧备份迁不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDavOverwriteUploadTest {

    private val dav = WebDavClientTest.FakeDav()
    private val client = WebDavClient(
        "https://dav.example.com/dav/",
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("user", "secret"),
        callFactory = dav,
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )

    private fun hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun nameFor(payload: ByteArray, encrypted: Boolean) =
        WebDavPaths.newBackupName(1_700_000_000_000L, "0.9.16", hex(payload), encrypted = encrypted)

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("upload-test", ".bin").apply { writeBytes(bytes) }

    /** 默认（保守）：目标已存在 → 失败，且旧文件不被删除/改动，MOVE 头是 F。 */
    @Test
    fun defaultRefusesToOverwriteExistingObject() {
        val old = ByteArray(64) { 1 }
        val fresh = ByteArray(64) { 2 }
        val name = nameFor(old, encrypted = true)
        client.uploadAtomic(temp(old), name)

        assertThrows(WebDavException::class.java) { client.uploadAtomic(temp(fresh), name) }

        val moveHeaders = dav.requests.filter { it.method == "MOVE" }.map { it.header("Overwrite") }
        assertTrue("MOVE 应使用 Overwrite: F，实际 $moveHeaders", "F" in moveHeaders)
        val stored = dav.files.entries.single { it.key.endsWith(name) }.value
        assertArrayEquals("旧对象必须保持原样", old, stored)
    }

    /** 迁移（overwrite=true）：允许覆盖同名对象，MOVE 头是 T，内容被替换。 */
    @Test
    fun migrationOverwritesWithExplicitFlag() {
        val old = ByteArray(64) { 1 }
        val fresh = ByteArray(64) { 2 }
        val name = nameFor(old, encrypted = true)
        client.uploadAtomic(temp(old), name)

        client.uploadAtomic(temp(fresh), name, overwrite = true)

        val moveHeaders = dav.requests.filter { it.method == "MOVE" }.map { it.header("Overwrite") }
        assertTrue("MOVE 应使用 Overwrite: T，实际 $moveHeaders", "T" in moveHeaders)
        val stored = dav.files.entries.single { it.key.endsWith(name) }.value
        assertArrayEquals("内容应被替换", fresh, stored)
        assertEquals(1, dav.files.keys.count { it.endsWith(name) })
    }
}
