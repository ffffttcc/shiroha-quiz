package com.yiqiu.shirohaquiz.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRotationTest {
    private fun backup(name: String, ageHours: Long, source: String = "android", integrity: Boolean = true) =
        WebDavBackupEntry(
            fileName = name,
            createdAt = 1_795_000_000_000 - ageHours * 3_600_000,
            sizeBytes = 1_000,
            source = source,
            appVersion = "0.9.16.1",
            sha256 = if (integrity) "a".repeat(64) else null
        )

    @Test fun keepCountOptionsAreThreeFiveTenWithDefaultFive() {
        assertEquals(listOf(3, 5, 10), BackupRotation.KEEP_OPTIONS)
        assertEquals(5, BackupRotation.DEFAULT_KEEP)
        assertEquals(3, BackupRotation.normalizeKeep(3))
        assertEquals(10, BackupRotation.normalizeKeep(10))
    }

    @Test fun legacyTwentyIsFoldedIntoTen() {
        assertEquals(10, BackupRotation.normalizeKeep(20))
        assertEquals(5, BackupRotation.normalizeKeep(7))
        assertEquals(5, BackupRotation.normalizeKeep(null))
    }

    @Test fun planDropsOldestBeyondKeepCount() {
        val entries = listOf(
            backup("a.enc", 0), backup("b.enc", 1), backup("c.enc", 2),
            backup("d.enc", 3), backup("e.enc", 4)
        )
        val plan = BackupRotation.planCleanup(entries, 3)
        assertEquals(listOf("d.enc", "e.enc"), plan.map { it.fileName })
    }

    @Test fun newestIsNeverPlannedEvenWhenKeepIsZero() {
        val entries = listOf(backup("new.enc", 0), backup("old.enc", 5))
        assertEquals(listOf("old.enc"), BackupRotation.planCleanup(entries, 0).map { it.fileName })
    }

    @Test fun syncObjectsAreNeverPlanned() {
        val entries = listOf(
            backup("new.enc", 0),
            backup("shiroha-sync-manifest.enc", 1),
            backup("shiroha-sync-record-abc.enc", 2)
        )
        assertTrue(BackupRotation.planCleanup(entries, 1).isEmpty())
        assertTrue(BackupRotation.isSyncObject("shiroha-sync-bank-x.enc"))
        assertFalse(BackupRotation.isSyncObject("shiroha-quiz-backup-x.zip.enc"))
    }

    @Test fun nonAndroidAndBrokenEntriesAreSkipped() {
        val entries = listOf(
            backup("new.enc", 0),
            backup("web.enc", 1, source = "web"),
            backup("broken.enc", 2, integrity = false)
        )
        assertTrue(BackupRotation.planCleanup(entries, 1).isEmpty())
    }

    @Test fun protectedEntrySurvivesEvenIfOldest() {
        val entries = listOf(backup("new.enc", 0), backup("just-uploaded.enc", 9), backup("old2.enc", 3))
        val plan = BackupRotation.planCleanup(entries, 1, protectedFileNames = setOf("just-uploaded.enc"))
        assertEquals(listOf("old2.enc"), plan.map { it.fileName })
    }

    @Test fun singleFailureDoesNotStopTheOthers() {
        val (ok, failed) = BackupRotation.tally(listOf(true, false, true))
        assertEquals(2, ok)
        assertEquals(1, failed)
    }

    @Test fun summarySpeaksPlainly() {
        assertEquals("已删除 2 份旧备份，云端剩 3 份。", BackupRotation.summary(2, 0, 3))
        assertEquals("没有需要清理的旧备份，云端仍是 5 份。", BackupRotation.summary(0, 0, 5))
        assertTrue(BackupRotation.summary(1, 1, 4).contains("1 份失败"))
    }
}
