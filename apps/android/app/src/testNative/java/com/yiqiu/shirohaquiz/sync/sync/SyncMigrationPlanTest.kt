package com.yiqiu.shirohaquiz.sync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住本次漏掉的场景：**同步对象已迁完（标记 true），但完整备份还没迁**。
 * 这种情况备份迁移必须执行——它不能被另一个标记挡住。
 */
class SyncMigrationPlanTest {

    /** 本次事故场景：objectsDone=true、backupsDone=false → 只跑备份迁移。 */
    @Test
    fun backupsStillRunWhenObjectsAlreadyMigrated() {
        val plan = SyncMigrationPlan.of(hasLegacy = true, objectsDone = true, backupsDone = false)
        assertFalse("同步对象已迁完，不该再跑", plan.migrateObjects)
        assertTrue("完整备份必须照跑——这正是之前漏掉的场景", plan.migrateBackups)
    }

    /** 都没迁过：两段都跑。 */
    @Test
    fun bothRunOnFreshInstall() {
        val plan = SyncMigrationPlan.of(hasLegacy = true, objectsDone = false, backupsDone = false)
        assertTrue(plan.migrateObjects)
        assertTrue(plan.migrateBackups)
    }

    /** 都迁过：谁都不跑（幂等）。 */
    @Test
    fun nothingRunsWhenBothDone() {
        val plan = SyncMigrationPlan.of(hasLegacy = true, objectsDone = true, backupsDone = true)
        assertFalse(plan.migrateObjects)
        assertFalse(plan.migrateBackups)
    }

    /** 没有旧码就等于没有需要迁移的旧对象。 */
    @Test
    fun noLegacyKeyMeansNoMigration() {
        val plan = SyncMigrationPlan.of(hasLegacy = false, objectsDone = false, backupsDone = false)
        assertFalse(plan.migrateObjects)
        assertFalse(plan.migrateBackups)
    }
}
