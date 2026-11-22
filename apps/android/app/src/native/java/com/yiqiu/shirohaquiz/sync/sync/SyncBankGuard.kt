package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.state.QuizBank

/**
 * 同步前的「题库是否真的加载了」护栏。
 *
 * 背景（真机事故）：`readStoredBankIndex()` 建的是轻量索引，`questions = emptyList()`，
 * 题目按需 `ensureBankLoaded()`。同步若直接读索引，未加载的库会算出一模一样的「空库哈希」，
 * 于是 43 个库共用一个 132 字节的空 blob 被当成「已同步」——另一端照这个 manifest 同步
 * 就会把本地题库覆盖成空。**这是数据安全问题，不是性能问题。**
 *
 * 判据用**索引里声明的题数**，而不是 `questions.size`：
 * - 声明 >0 且题目体为空 → 未加载，**拒绝**（不管它看起来多像一个合法空库）
 * - 声明 ==0 且题目体为空 → 合法的空库，允许
 */
object SyncBankGuard {

    fun violations(banks: List<QuizBank>, declaredQuestionCount: (String) -> Int): List<QuizBank> =
        banks.filter { bank -> bank.questions.isEmpty() && declaredQuestionCount(bank.id) > 0 }

    /** 有未加载的库就直接抛：宁可整轮同步失败，也不许把空内容传上去。 */
    fun requireLoaded(banks: List<QuizBank>, declaredQuestionCount: (String) -> Int) {
        val bad = violations(banks, declaredQuestionCount)
        if (bad.isEmpty()) return
        val names = bad.take(5).joinToString("、") { it.name }
        val suffix = if (bad.size > 5) " 等 ${bad.size} 个库" else ""
        throw IllegalArgumentException("题库未加载完整，已停止同步（否则会以空内容覆盖远端）：$names$suffix")
    }
}
