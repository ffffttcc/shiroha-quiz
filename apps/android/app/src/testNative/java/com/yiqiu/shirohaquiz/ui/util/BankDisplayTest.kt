package com.yiqiu.shirohaquiz.ui.util

import com.yiqiu.shirohaquiz.state.QuizBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 章节顺序回归测试：用真机 42 个药理库名（2026-11-23 导出，47 库 / 7655 题）打乱后过
 * [chapterDisplayOrder]，断言输出严格等于教材章序，且「药理学复习指南」在末尾。
 *
 * 数据来源：/sdcard/Download/dsh/测试工作区/调研/20261123-shiroha排序根因.md §2
 */
class BankDisplayTest {

    /** 真机药理组 42 个库名，正确章序。 */
    private val expectedOrder = listOf(
        "01 第一章 药理学总论-绪言",
        "02 第二章 药物代谢动力学",
        "03 第三章 药物效应动力学",
        "05 第五章 传出神经系统药理概论",
        "06 第六章 胆碱受体激动药（合并 6–9 章）",
        "10 第十章 肾上腺素受体激动药（合并 10–11 章）",
        "12 第十二章 中枢神经系统药理学概论",
        "13 第十三章 全身麻醉药",
        "14 第十四章 局部麻醉药",
        "15 第十五章 镇静催眠药和促觉醒药",
        "16 第十六章 抗癫痫药和抗惊厥药",
        "17 第十七章 治疗中枢神经系统退行性疾病药",
        "18 第十八章 抗精神失常药",
        "19 第十九章 中枢镇痛药",
        "20 第二十章 解热镇痛抗炎药和抗痛风药",
        "22 第二十二章 抗心律失常药",
        "24 第二十四章 利尿药",
        "25 第二十五章 抗高血压药",
        "26 第二十六章 治疗心力衰竭的药物",
        "27 第二十七章 抗心绞痛药",
        "28 第二十八章 抗动脉粥样硬化药",
        "29 第二十九章 作用于血液及造血系统的药物",
        "30 第三十章 影响自体活性物质的药物",
        "31 第三十一章 作用于呼吸系统的药物",
        "32 第三十二章 作用于消化系统的药物",
        "33 第三十三章 子宫平滑肌兴奋药和抑制药",
        "34 第三十四章 性激素类药及避孕药",
        "35 第三十五章 肾上腺皮质激素类药物",
        "36 第三十六章 甲状腺激素及抗甲状腺药",
        "37 第三十七章 治疗糖尿病的药物",
        "38 第三十八章 抗骨质疏松药",
        "39 第三十九章 抗菌药物概论",
        "40 第四十章 β-内酰胺类抗生素",
        "41 第四十一章 大环内酯类林可霉素类及多肽类抗生素",
        "42 第四十二章 氨基糖苷类抗生素",
        "44 第四十四章 人工合成抗菌药",
        "45 第四十五章 抗病毒药（合并 45–46 章）",
        "47 第四十七章 抗结核药及抗麻风病药",
        "48 第四十八章 抗寄生虫药",
        "49 第四十九章 抗恶性肿瘤药",
        "50 第五十章 影响免疫功能的药物",
        "药理学复习指南"
    )

    private fun bank(name: String) = QuizBank(
        id = "bank_test_" + name.hashCode(),
        name = name,
        questions = emptyList(),
        groupName = "药理"
    )

    @Test
    fun `chapterSortKey 取首段数字，无数字排末尾`() {
        assertEquals(1, chapterSortKey("01 第一章 药理学总论-绪言"))
        assertEquals(28, chapterSortKey("28 第二十八章 抗动脉粥样硬化药"))
        assertEquals(50, chapterSortKey("50 第五十章 影响免疫功能的药物"))
        assertEquals(Int.MAX_VALUE, chapterSortKey("药理学复习指南"))
        assertEquals(Int.MAX_VALUE, chapterSortKey(""))
        // 兼容「药理学 第07章 绪论」这类前缀不在最前方的写法
        assertEquals(7, chapterSortKey("药理学 第07章 绪论"))
    }

    @Test
    fun `42 个真机库名打乱后排序等于教材章序`() {
        assertEquals("样例应为 42 个真实药理库名", 42, expectedOrder.size)

        val shuffled = expectedOrder.shuffled(Random(20261123))
        assertTrue("打乱后不应恰好等于原序，否则测试无意义", shuffled != expectedOrder)

        val sorted = shuffled.map { bank(it) }.sortedWith(chapterDisplayOrder).map { it.name }
        assertEquals(expectedOrder, sorted)
    }

    @Test
    fun `药理学复习指南排末尾 且 24 章在 28 章之前`() {
        val sorted = expectedOrder.reversed().map { bank(it) }.sortedWith(chapterDisplayOrder).map { it.name }

        assertEquals("药理学复习指南", sorted.last())

        val index24 = sorted.indexOfFirst { it.startsWith("24 ") }
        val index28 = sorted.indexOfFirst { it.startsWith("28 ") }
        assertTrue("24 章与 28 章都必须存在", index24 >= 0 && index28 >= 0)
        assertTrue("24 章索引 $index24 必须小于 28 章索引 $index28", index24 < index28)
    }

    @Test
    fun `组内排序严格递增，允许缺章，0 逆序`() {
        val sorted = expectedOrder.shuffled(Random(7)).map { bank(it) }.sortedWith(chapterDisplayOrder)
        val keys = sorted.map { chapterSortKey(it.name) }
        val numbered = keys.filter { it != Int.MAX_VALUE }
        for (i in 1 until numbered.size) {
            assertTrue("第 $i 位逆序：${numbered[i - 1]} > ${numbered[i]}", numbered[i - 1] < numbered[i])
        }
    }
}