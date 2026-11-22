package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.sync.webdav.WebDavClient
import com.yiqiu.shirohaquiz.sync.webdav.WebDavClientTest
import com.yiqiu.shirohaquiz.sync.webdav.WebDavCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 全量重加密迁移：把 manifest 引用的**所有**对象从旧恢复码钥匙迁到凭据钥匙。
 *
 * 背景：懒迁移只覆盖同步中被读到的对象，实测 46 个对象里只有 2 个换了钥匙，
 * 没有旧码的新设备仍然解不开那 44 个。所以需要一次全量遍历。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncKeyMigrationTest {

    private val legacyCode = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun hash(n: Int) = "%064x".format(n)

    private fun client(dav: WebDavClientTest.FakeDav) = WebDavClient(
        "https://dav.example.com/dav/",
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("user", "secret"),
        callFactory = dav,
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )

    private fun keysWithLegacy() = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = legacyCode)

    /**
     * 造一份「全部旧码加密」的云端：manifest + 2 个 bank + 1 个 record 分片。
     * **全部通过 client 自己 PUT**，不硬编码 FakeDav 的路径前缀（上次就栽在这上面）。
     */
    private fun seedLegacyCloud(dav: WebDavClientTest.FakeDav): List<String> {
        val bankA = hash(11)
        val bankB = hash(22)
        val shard = hash(33)
        val legacy = SyncKeys.fromLegacyRecoveryCode(legacyCode)
        val c = client(dav)
        c.putSyncObject(SyncObjects.bankName(bankA), legacy.encrypt("bankA".toByteArray(), SyncPayloadCipher.aadForContent(bankA)))
        c.putSyncObject(SyncObjects.bankName(bankB), legacy.encrypt("bankB".toByteArray(), SyncPayloadCipher.aadForContent(bankB)))
        c.putSyncObject(SyncObjects.recordName(shard), legacy.encrypt("shard".toByteArray(), SyncPayloadCipher.aadForContent(shard)))
        val manifest = SyncManifest(
            deviceId = "dev-1",
            updatedAt = 1L,
            banks = mapOf(
                "g|a" to SyncBankRecord("g", "a", bankA, 1L),
                "g|b" to SyncBankRecord("g", "b", bankB, 1L)
            ),
            recordShards = listOf(shard),
            epoch = 5L
        )
        c.putSyncObject(SyncObjects.MANIFEST, legacy.encryptManifest(manifest))
        return listOf(SyncObjects.bankName(bankA), SyncObjects.bankName(bankB), SyncObjects.recordName(shard))
    }

    private fun engine(dav: WebDavClientTest.FakeDav, keys: SyncKeys) = buildSyncEngine(
        client = client(dav),
        keys = keys,
        state = SyncStateStore(RuntimeEnvironment.getApplication()),
        declaredQuestionCount = { 0 },
        recordStore = null,
        localBanks = { emptyList() },
        applyRemoteBank = { _, _, _ -> },
        renameLocalBank = { _, _ -> }
    )

    /** 全量遍历：所有旧码对象都被换成凭据钥匙，manifest 最后重写。 */
    @Test
    fun migratesEveryReferencedObject() {
        val dav = WebDavClientTest.FakeDav()
        val targets = seedLegacyCloud(dav)
        val report = engine(dav, keysWithLegacy()).migrateAllToPrimaryKey()

        assertNull("不应有失败对象：${report.message}", report.failedObject)
        assertEquals(3, report.total)
        assertEquals("三个对象都应被迁移", 3, report.migrated)

        // 迁移后用「只有主钥匙」的实例必须能解开全部对象
        val primaryOnly = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = null)
        val c = client(dav)
        targets.forEach { name ->
            val raw = c.getSyncObject(name)!!
            val opened = primaryOnly.open(raw, SyncPayloadCipher.aadForContent(name.hashOf()))
            assertEquals(false, opened.usedLegacy)
        }
        assertNotNull(primaryOnly.openManifest(c.getSyncObject(SyncObjects.MANIFEST)!!))
    }

    /** 幂等：再跑一次不再迁移任何对象。 */
    @Test
    fun secondRunMigratesNothing() {
        val dav = WebDavClientTest.FakeDav()
        seedLegacyCloud(dav)
        val engine = engine(dav, keysWithLegacy())
        assertEquals(3, engine.migrateAllToPrimaryKey().migrated)
        assertEquals("已迁移过的对象应全部跳过", 0, engine.migrateAllToPrimaryKey().migrated)
    }

    /** 进度：最后一次回调是 (N, N)。 */
    @Test
    fun progressReachesTotal() {
        val dav = WebDavClientTest.FakeDav()
        seedLegacyCloud(dav)
        val seen = mutableListOf<Pair<Int, Int>>()
        engine(dav, keysWithLegacy()).migrateAllToPrimaryKey { done, total -> seen += done to total }
        assertEquals(3, seen.size)
        assertEquals(3 to 3, seen.last())
    }

    /** 失败不前进：某个对象解不开时，manifest 保持原样（仍是旧码），并报出对象名。 */
    @Test
    fun failureKeepsManifestUntouched() {
        val dav = WebDavClientTest.FakeDav()
        val targets = seedLegacyCloud(dav)
        // 把第二个 bank 换成垃圾字节，两个钥匙都解不开
        client(dav).putSyncObject(targets[1], ByteArray(64) { 7 })

        val manifestBefore = client(dav).getSyncObject(SyncObjects.MANIFEST)!!.copyOf()
        val report = engine(dav, keysWithLegacy()).migrateAllToPrimaryKey()

        assertNotNull("应报出失败对象", report.failedObject)
        assertTrue("manifest 不应被重写", manifestBefore.contentEquals(client(dav).getSyncObject(SyncObjects.MANIFEST)))
    }

    /** 引用对象缺失（404）不算失败：跳过并在最后重写 manifest。 */
    @Test
    fun missingObjectDoesNotAbortMigration() {
        val dav = WebDavClientTest.FakeDav()
        val targets = seedLegacyCloud(dav)
        dav.files.keys.filter { it.endsWith(targets[2]) }.forEach { dav.files.remove(it) }  // 删掉 record 分片

        val report = engine(dav, keysWithLegacy()).migrateAllToPrimaryKey()
        assertNull("缺对象不应判失败：${report.message}", report.failedObject)
        assertEquals(2, report.migrated)
    }

    private fun String.hashOf(): String = removePrefix("shiroha-sync-bank-")
        .removePrefix("shiroha-sync-record-")
        .removeSuffix(".enc")
}
