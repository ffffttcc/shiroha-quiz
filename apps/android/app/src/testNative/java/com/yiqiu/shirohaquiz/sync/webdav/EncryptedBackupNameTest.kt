package com.yiqiu.shirohaquiz.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加密只加一个 .enc 后缀，但列表扫描与文件名解析必须照旧认得——
 * 认不出来的话，加密备份在列表里直接消失、或者失去校验值（恢复按钮变灰）。
 */
class EncryptedBackupNameTest {
    private val sha = "a".repeat(64)

    @Test
    fun encryptedNameKeepsTheZipSuffixAndIsListed() {
        val name = WebDavPaths.newBackupName(1_700_000_000_000L, "1.2.3", sha, encrypted = true)
        assertTrue(name.endsWith(".zip.enc"))
        assertTrue(WebDavPaths.isEncryptedName(name))
        assertTrue("加密备份必须仍被列表识别", WebDavPaths.isBackup(name))
    }

    @Test
    fun plainNameStillListedAndNotMarkedEncrypted() {
        val name = WebDavPaths.newBackupName(1_700_000_000_000L, "1.2.3", sha)
        assertTrue(name.endsWith(".zip"))
        assertFalse(WebDavPaths.isEncryptedName(name))
        assertTrue(WebDavPaths.isBackup(name))
    }

    @Test
    fun metadataSidecarIsStillNotABackup() {
        val name = WebDavPaths.newBackupName(1_700_000_000_000L, "1.2.3", sha, encrypted = true)
        assertFalse(WebDavPaths.isBackup("$name.meta.json"))
    }

    /** 解析不出 sha256 的备份无法恢复；加密名必须照样解析出校验值。 */
    @Test
    fun encryptedNameStillParsesMetadata() {
        val name = WebDavPaths.newBackupName(1_700_000_000_000L, "1.2.3", sha, encrypted = true)
        val entry = WebDavPaths.fallbackEntry(
            WebDavResource(fileName = name, isDirectory = false, sizeBytes = 1024, modifiedAt = 1L)
        )
        assertNotNull("加密备份必须能从文件名解析出校验值", entry.sha256)
        assertEquals(sha, entry.sha256)
        assertEquals("android", entry.source)
        assertEquals("1.2.3", entry.appVersion)
        assertTrue(entry.integrityAvailable)
    }

    @Test
    fun hexRoundTripsThroughBytes() {
        val bytes = ByteArray(32) { (it * 7 + 3).toByte() }
        val hex = WebDavPaths.digestHex(bytes)
        assertEquals(hex, WebDavPaths.digestHex(WebDavPaths.hexToBytes(hex)))
        assertTrue(WebDavPaths.hexToBytes(hex).contentEquals(bytes))
    }
}
