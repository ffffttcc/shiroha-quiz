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
    keys: SyncKeys,
    state: SyncStateStore,
    /** 索引声明的题数提供者（空库护栏用）。**故意不给默认值**：逼调用方显式交出来。 */
    declaredQuestionCount: (bankId: String) -> Int,
    recordStore: RecordEventStore?,
    localBanks: () -> List<QuizBank>,
    applyRemoteBank: (groupName: String, name: String, questions: List<Question>) -> Unit,
    renameLocalBank: (bankId: String, newName: String) -> Unit
): SyncEngine {
    // 数据安全闸门：未加载的库（声明题数>0 但题目体为空）绝不许进同步。
    // 放在工厂里而不是调用方，是为了让「忘接线」直接编译不过/立刻抛错。
    SyncBankGuard.requireLoaded(localBanks(), declaredQuestionCount)
    if (recordStore == null) {
        return SyncEngine(
            client = client,
            keys = keys,
            state = state,
            localBanks = localBanks,
            applyRemoteBank = applyRemoteBank,
            renameLocalBank = renameLocalBank
        )
    }
    return SyncEngine(
        client = client,
        keys = keys,
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
