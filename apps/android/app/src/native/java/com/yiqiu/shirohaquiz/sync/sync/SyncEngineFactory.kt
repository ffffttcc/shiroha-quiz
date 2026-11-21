package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.state.QuizBank
import com.yiqiu.shirohaquiz.sync.records.RecordEventStore
import com.yiqiu.shirohaquiz.sync.webdav.WebDavClient

/**
 * `SyncEngine` 的**唯一生产构造点**。
 *
 * 抽取理由：`SyncEngine` 有四个记录类参数带默认值（空事件 / 空 tombstone 回调）。
 * 漏传的后果是「引擎看不见事件」——同步报成功、`记录片 +0`、云端永远没有分片，
 * 而如果单测也走默认值，测试照样全绿。本工程已经发生过三次同类事故：
 * `validated()` 未透传、`request()` 硬编码 isHttps、以及记录事件未注入。
 *
 * 把构造收拢到这一个函数，并配一条**反向断言**单测（不注入时 `recordUploaded` 必须为 0），
 * 谁再把接线删掉，测试就会红。
 *
 * @param recordStore 传 `null` 表示「本轮不接记录同步」，仅供反向断言测试使用；
 *                    生产调用点必须传真实 store。
 */
internal fun buildSyncEngine(
    client: WebDavClient,
    recoveryCode: String,
    state: SyncStateStore,
    recordStore: RecordEventStore?,
    localBanks: () -> List<QuizBank>,
    applyRemoteBank: (groupName: String, name: String, questions: List<Question>) -> Unit,
    renameLocalBank: (bankId: String, newName: String) -> Unit
): SyncEngine {
    if (recordStore == null) {
        return SyncEngine(
            client = client,
            recoveryCode = recoveryCode,
            state = state,
            localBanks = localBanks,
            applyRemoteBank = applyRemoteBank,
            renameLocalBank = renameLocalBank
        )
    }
    return SyncEngine(
        client = client,
        recoveryCode = recoveryCode,
        state = state,
        localBanks = localBanks,
        applyRemoteBank = applyRemoteBank,
        renameLocalBank = renameLocalBank,
        localEvents = { recordStore.loadAll() },
        applyRemoteEvents = { events -> recordStore.appendRemote(events) },
        localTombstones = { recordStore.loadTombstones() },
        applyRemoteTombstones = { tombstones -> recordStore.applyRemoteTombstones(tombstones) }
    )
}
