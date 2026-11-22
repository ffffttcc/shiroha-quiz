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
 * 全量重加密迁移（核心逻辑用内存表测，不碰 HTTP 假实现的路径细节）。
 *
 * 背景：懒迁移只覆盖同步中被读到的对象，46 个对象里只有 2 个换了钥匙，
 * 没有旧码的新设备解不开其余 44 个，所以需要一次全量遍历。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncKeyMigrationTest {

    private val legacyCode = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun hash(n: Int) = "%064x".format(n)

    private fun legacyKeys() = SyncKeys.fromLegacyRecoveryCode(legacyCode)

    private fun keysWithLegacy() = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = legacyCode)

    private fun primaryOnly() = SyncKeys.fromCredentials("user", "pw", legacyRecoveryCode = null)

    private fun engineWith(keys: SyncKeys): SyncEngine = buildSyncEngine(
        client = WebDavClient(
            "https://dav.example.com/dav/",
            "/ShirohaQuiz/Backups/",
            WebDavCredentials("user", "secret"),
            callFactory = WebDavClientTest.FakeDav(),
            xmlParserFactory = { org.kxml2.io.KXmlParser() }
        ),
        keys = keys,
        state = SyncStateStore(RuntimeEnvironment.getApplication()),
        declaredQuestionCount = { 0 },
        recordStore = null,
        localBanks = { emptyList() },
        applyRemoteBank = { _, _, _ -> true },
        renameLocalBank = { _, _ -> }
    )

    /** 造一份「全部旧码加密」的云端（内存表）与引用它的 manifest。 */
    private fun seed(): Pair<MutableMap<String, ByteArray>, Pair<SyncManifest, List<String>>> {
        val bankA = hash(11)
        val bankB = hash(22)
        val shard = hash(33)
        val legacy = legacyKeys()
        val store = linkedMapOf<String, ByteArray>()
        val nameA = SyncObjects.bankName(bankA)
        val nameB = SyncObjects.bankName(bankB)
        val nameS = SyncObjects.recordName(shard)
        store[nameA] = legacy.encrypt("bankA".toByteArray(), SyncPayloadCipher.aadForContent(bankA))
        store[nameB] = legacy.encrypt("bankB".toByteArray(), SyncPayloadCipher.aadForContent(bankB))
        store[nameS] = legacy.encrypt("shard".toByteArray(), SyncPayloadCipher.aadForContent(shard))
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
        return store to (manifest to listOf(nameA, nameB, nameS))
    }

    private fun aadOf(name: String): ByteArray =
        SyncPayloadCipher.aadForContent(
            name.removePrefix("shiroha-sync-bank-").removePrefix("shiroha-sync-record-").removeSuffix(".enc")
        )

    /** 全量遍历：所有旧码对象都被换成凭据钥匙。 */
    @Test
    fun migratesEveryReferencedObject() {
        val (store, seeded) = seed()
        val (manifest, names) = seeded
        val report = engineWith(keysWithLegacy()).migrateObjects(
            manifest = manifest,
            fetch = { store[it] },
            put = { name, bytes -> store[name] = bytes }
        )

        assertNull("不应有失败对象：${report.message}", report.failedObject)
        assertEquals(3, report.total)
        assertEquals("三个对象都应被迁移", 3, report.migrated)

        // 迁移后「只有主钥匙」的实例必须能解开全部
        val only = primaryOnly()
        names.forEach { name ->
            assertEquals("$name 应已可被主钥匙解开", false, only.open(store[name]!!, aadOf(name)).usedLegacy)
        }
    }

    /** 幂等：再跑一次不再迁移任何对象。 */
    @Test
    fun secondRunMigratesNothing() {
        val (store, seeded) = seed()
        val (manifest, _) = seeded
        val engine = engineWith(keysWithLegacy())
        val put = { name: String, bytes: ByteArray -> store[name] = bytes }
        assertEquals(3, engine.migrateObjects(manifest, { store[it] }, put).migrated)
        assertEquals("已迁移对象应跳过", 0, engine.migrateObjects(manifest, { store[it] }, put).migrated)
    }

    /** 进度：最后一次回调是 (N, N)。 */
    @Test
    fun progressReachesTotal() {
        val (store, seeded) = seed()
        val (manifest, _) = seeded
        val seen = mutableListOf<Pair<Int, Int>>()
        engineWith(keysWithLegacy()).migrateObjects(
            manifest = manifest,
            fetch = { store[it] },
            put = { name, bytes -> store[name] = bytes },
            onProgress = { done, total -> seen += done to total }
        )
        assertEquals(3, seen.size)
        assertEquals(3 to 3, seen.last())
    }

    /** 失败不前进：某个对象两把钥匙都解不开时报出对象名，且不再继续后面的对象。 */
    @Test
    fun failureReportsObjectAndStops() {
        val (store, seeded) = seed()
        val (manifest, names) = seeded
        store[names[1]] = ByteArray(64) { 7 }

        val report = engineWith(keysWithLegacy()).migrateObjects(
            manifest = manifest,
            fetch = { store[it] },
            put = { name, bytes -> store[name] = bytes }
        )
        assertNotNull("应报出失败对象", report.failedObject)
        assertEquals("失败对象应是被改坏的那个", names[1], report.failedObject)
        assertTrue("失败前已迁移的应保留", report.migrated <= 1)
    }

    /** 引用对象缺失（404）不算失败：跳过，其余照常迁移。 */
    @Test
    fun missingObjectDoesNotAbortMigration() {
        val (store, seeded) = seed()
        val (manifest, names) = seeded
        store.remove(names[2])

        val report = engineWith(keysWithLegacy()).migrateObjects(
            manifest = manifest,
            fetch = { store[it] },
            put = { name, bytes -> store[name] = bytes }
        )
        assertNull("缺对象不应判失败：${report.message}", report.failedObject)
        assertEquals(2, report.migrated)
    }
}
