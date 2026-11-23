package com.yiqiu.shirohaquiz.ui.util

import com.yiqiu.shirohaquiz.state.DEFAULT_BANK_GROUP_NAME
import com.yiqiu.shirohaquiz.state.QuizBank

fun bankDisplayPath(groupName: String, bankName: String): String {
    val cleanGroupName = groupName.ifBlank { DEFAULT_BANK_GROUP_NAME }
    return "$cleanGroupName / $bankName"
}

fun bankDisplayPath(bank: QuizBank): String {
    return bankDisplayPath(bank.groupName, bank.name)
}

/**
 * 章节排序键：取库名中第一段数字。
 *
 * 真机库名形如 `01 第一章 药理学总论-绪言`、`28 第二十八章 抗动脉粥样硬化药`，
 * 前缀已补零，所以数值序 = 教材章序。没有数字前缀的库（如「药理学复习指南」）
 * 返回 [Int.MAX_VALUE]，排在组内末尾。
 *
 * 纯函数：只读名字，不碰数据、不碰同步、不改分组名排序。
 */
fun chapterSortKey(name: String): Int {
    var start = -1
    for (i in name.indices) {
        if (name[i].isDigit()) {
            start = i
            break
        }
    }
    if (start < 0) return Int.MAX_VALUE
    var end = start
    while (end < name.length && name[end].isDigit()) end++
    // 最多取 9 位，避免超长数字串 toIntOrNull 溢出；正常章号只有 1–2 位。
    return name.substring(start, end).take(9).toIntOrNull() ?: Int.MAX_VALUE
}

/**
 * 组内题库（章节）展示顺序：先按 [chapterSortKey]，同键再按库名保证确定性。
 *
 * 列表页、章节练习页、「上一章/下一章」三处共用这一个比较器，避免几处排序不一致。
 */
val chapterDisplayOrder: Comparator<QuizBank> =
    compareBy<QuizBank> { chapterSortKey(it.name) }.thenBy { it.name }
