package com.yiqiu.shirohaquiz.state.sync

import org.json.JSONObject

/** 同步相关仓储：练习进度导出/吸收、会话开始时刻。 */
interface SyncRepository {
    /** 当前练习场次的开始时刻（0 表示未在练习），供 UI 作 key 判断“换没换场”。 */
    val practiceSessionStartedAt: Long

    /** 远端进度落地后 +1，触发 PracticeScreen 重读快照。 */
    var practiceProgressRevision: Int

    /** 导出可跨设备同步的便携版快照（不含题干，仅进度）。 */
    fun exportPortablePracticeSnapshots(): JSONObject

    /** 吸收远端进度（按 scopeKey、updatedAt 胜）。 */
    fun applyRemotePracticeProgress(remote: JSONObject): Boolean
}
