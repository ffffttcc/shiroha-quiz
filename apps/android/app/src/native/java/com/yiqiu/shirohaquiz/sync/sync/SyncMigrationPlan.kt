package com.yiqiu.shirohaquiz.sync.sync

/**
 * 两段一次性迁移的**触发判据**（纯函数，便于单测）。
 *
 * 为什么单独抽出来：真机上出过一次「换了标记没换位置」——备份迁移被嵌在
 * 「同步对象迁移」的 if 里，而那个标记早已置 true，于是备份迁移一次都没跑。
 * 这里把两段做成**互相独立**的两个布尔量，谁也不再决定谁跑不跑。
 */
object SyncMigrationPlan {

    data class Plan(
        val migrateObjects: Boolean,
        val migrateBackups: Boolean
    )

    fun of(hasLegacy: Boolean, objectsDone: Boolean, backupsDone: Boolean): Plan = Plan(
        migrateObjects = hasLegacy && !objectsDone,
        migrateBackups = hasLegacy && !backupsDone
    )
}
