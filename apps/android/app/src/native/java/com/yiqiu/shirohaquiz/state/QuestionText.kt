package com.yiqiu.shirohaquiz.state

import com.yiqiu.shirohaquiz.importer.model.Question
import java.security.MessageDigest
import java.util.Locale

/**
 * 英语阅读/完形两级结构的文本访问器。
 *
 * 单一事实来源：**任何需要展示或比较题干文本的地方都必须走这里**，
 * 不要直接读 `question.question`（迁移后那里只剩「残余」）。
 * 门禁：`grep -rn "question\.question" --include=*.kt . | grep -v "/importer/"` 必须为 0。
 */
object QuestionText {

    /** 材料内容寻址 id：sha1(原文) 前 12 位，幂等。 */
    fun materialId(material: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(12)
    }

    /** 展示文本：有材料则「材料 + 残余」，否则原样返回。逐字不改，不做任何归一化。 */
    fun display(question: Question, material: String?): String {
        return if (!question.materialId.isNullOrBlank() && material != null) material + question.question else question.question
    }

    /** 编辑框文本：与展示一致；未设置材料时就是原题干。 */
    fun editable(question: Question, material: String?): String = display(question, material)

    /**
     * 把编辑结果写回：若仍以材料开头，只保存残余并保留 materialId（避免材料重复拼接）；
     * 否则视为整段替换，清掉 materialId，题干恢复自包含。
     */
    fun applyEdit(question: Question, material: String?, editedText: String): Question {
        if (question.materialId.isNullOrBlank() || material == null) {
            return question.copy(question = editedText, materialId = null)
        }
        return if (editedText.startsWith(material)) {
            question.copy(question = editedText.substring(material.length))
        } else {
            question.copy(question = editedText, materialId = null)
        }
    }

    /**
     * 错题本/收藏是 Question 副本，必须自包含：写入前把材料拼平进题干并清掉 materialId，
     * 这样删库之后副本仍然有完整文章。
     */
    fun flatten(question: Question, material: String?): Question {
        if (question.materialId.isNullOrBlank() || material == null) return question
        return question.copy(question = material + question.question, materialId = null)
    }
}

/**
 * 迁移用的拆分器：把「文章 + 小题」形态的题干拆成 材料（按内容 id 去库）+ 残余。
 *
 * 铁律：残余逐字原样，`material + tail === 原 question` 必须恒等成立（切点只决定材料长度）。
 */
object QuestionMaterialSplitter {

    /** 完形：题干以「第N空」结尾。 */
    private val clozeTailRegex = Regex("""第\d+空$""")
    private val clozeMarkRegex = Regex("""\[\[(\d+)\]\]""")

    /** 阅读：同一篇文章的小题共享足够长的公共前缀；共有的「Read the following text…」指令只有 ~86 字符。 */
    private const val READING_PREFIX_MIN_LENGTH = 300

    data class SplitOutcome(val questions: List<Question>, val materials: Map<String, String>)

    fun split(questions: List<Question>): SplitOutcome {
        val materials = LinkedHashMap<String, String>()
        val result = ArrayList<Question>(questions.size)
        var index = 0
        while (index < questions.size) {
            val question = questions[index]
            val clozeStart = clozeTailStart(question.question)
            if (clozeStart >= 0) {
                val article = question.question.substring(0, clozeStart)
                var end = index
                while (end < questions.size) {
                    val candidate = questions[end]
                    val start = clozeTailStart(candidate.question)
                    if (start < 0 || candidate.question.substring(0, start) != article) break
                    end++
                }
                val material = backOffToWhitespace(article)
                val markerCount = clozeMarkRegex.findAll(material).map { it.groupValues[1] }.toSet().size
                val groupSize = end - index
                // 脏数据（[[n]] 个数与小题数不符）或拆不出材料：整组退化不拆。
                if (groupSize >= 2 && material.isNotEmpty() && markerCount == groupSize) {
                    val id = QuestionText.materialId(material)
                    materials[id] = material
                    for (k in index until end) {
                        result.add(splitOne(questions[k], id, material))
                    }
                    index = end
                    continue
                }
                result.add(question)
                index++
                continue
            }

            // 阅读：按公共前缀门槛增量归组。
            var end = index + 1
            var prefixLength = question.question.length
            while (end < questions.size) {
                val candidate = questions[end]
                if (clozeTailStart(candidate.question) >= 0) break
                val candidatePrefix = minOf(prefixLength, commonPrefixLength(question.question, candidate.question))
                if (candidatePrefix < READING_PREFIX_MIN_LENGTH) break
                prefixLength = candidatePrefix
                end++
            }
            if (end - index >= 2 && prefixLength >= READING_PREFIX_MIN_LENGTH) {
                val safePrefix = safeCodePointBoundary(question.question, prefixLength)
                val material = question.question.substring(0, safePrefix)
                val id = QuestionText.materialId(material)
                materials[id] = material
                for (k in index until end) {
                    result.add(splitOne(questions[k], id, material))
                }
                index = end
                continue
            }
            result.add(question)
            index++
        }
        return SplitOutcome(result, materials)
    }

    private fun splitOne(question: Question, materialId: String, material: String): Question {
        return question.copy(materialId = materialId, question = question.question.substring(material.length))
    }

    /** 「第N空」在题干中的起点；没有则 -1。 */
    private fun clozeTailStart(text: String): Int = clozeTailRegex.find(text)?.range?.first ?: -1

    /** 切点回退到最近的空白，避免把「第」字切进材料。 */
    private fun backOffToWhitespace(text: String): String {
        var cut = text.length
        while (cut > 0 && !text[cut - 1].isWhitespace()) cut--
        return if (cut > 0) text.substring(0, cut) else ""
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    /** 不要在 UTF-16 代理对中间切断。 */
    private fun safeCodePointBoundary(text: String, length: Int): Int {
        if (length <= 0 || length >= text.length) return length.coerceAtMost(text.length)
        return if (Character.isHighSurrogate(text[length - 1])) length - 1 else length
    }
}
