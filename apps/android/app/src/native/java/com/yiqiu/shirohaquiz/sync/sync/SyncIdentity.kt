package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.state.DEFAULT_BANK_GROUP_NAME
import com.yiqiu.shirohaquiz.state.QuizBank
import java.security.MessageDigest

/**
 * 同步身份与内容指纹。
 *
 * 身份键 = `groupName|name`：跨设备稳定、可读。**绝不用 bank.id** ——
 * 它是 `bank_<毫秒时间戳>`，两台设备各自导入必然不同。
 *
 * contentHash 必须是「同一份内容 → 同一串」，否则增量同步永远收敛不了。为此：
 * - 题目按 `question.id` 排序（id 随备份跨设备传递，顺序因此一致）；
 * - 用**显式字段白名单**序列化，不用反射/JSON 对象默认序，避免顺序漂移；
 * - 排除设备本地字段：`images.localPath`（本机文件路径）、`sourceFileId`（本机导入工件）；
 * - 集合语义字段（knowledgePoints / tags / warnings）排序后再哈希；
 * - `Question` 本身没有时间戳字段，updatedAt 只存在 SyncStateStore 里，**不进 hash**。
 */
object SyncIdentity {
    fun normalizeGroupName(raw: String?): String =
        raw?.trim().orEmpty().ifBlank { DEFAULT_BANK_GROUP_NAME }

    fun identityKey(groupName: String?, name: String?): String =
        "${normalizeGroupName(groupName)}|${name?.trim().orEmpty()}"

    fun keyOf(bank: QuizBank): String = identityKey(bank.groupName, bank.name)

    fun contentHash(bank: QuizBank): String = sha256Hex(canonicalJson(bank.questions))

    /**
     * 带**空库护栏**的 contentHash：索引声明题数 > 0 却拿不到题目体时直接抛，
     * 绝不算出一个「看起来合法」的空库哈希（那会让空内容参与同步）。
     * 合法空库（声明题数 == 0）照常通过。
     */
    fun contentHashChecked(bank: QuizBank, declaredQuestionCount: Int): String {
        require(!(bank.questions.isEmpty() && declaredQuestionCount > 0)) {
            "题库「${bank.name}」索引声明 $declaredQuestionCount 题，但题目体为空（未加载），拒绝计算空库哈希。"
        }
        return contentHash(bank)
    }

    /** 冲突副本命名：改名后身份键变化，会作为新库参与后续同步——这是期望行为（两个版本都保住）。 */
    fun conflictCopyName(originalName: String, timestampMillis: Long): String {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date(timestampMillis))
        return "$originalName（冲突副本 $stamp）"
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * 确定性序列化：长度前缀 + 换行分隔，不用转义也不会因拼接产生歧义。
     * 只写白名单字段，新增字段不会让历史 hash 漂移（除非显式加进这里）。
     */
    fun canonicalJson(questions: List<Question>): String {
        val builder = StringBuilder()
        builder.append("v1\u0000").append(questions.size).append('\u0000')
        questions.sortedBy { it.id }.forEach { question ->
            builder.append("Q\u0000")
            builder.str(question.id)
            builder.str(question.number)
            builder.str(question.type.name)
            builder.str(question.question)
            builder.str(question.answer.joinToString("\u0001"))
            builder.append("O[").append(question.options.size).append(']')
            question.options.forEach { option ->
                builder.str(option.key)
                builder.str(option.text)
            }
            builder.append("B[").append(question.blankAnswers.size).append(']')
            question.blankAnswers.forEach { group ->
                builder.append("b[").append(group.size).append(']')
                group.forEach { builder.str(it) }
            }
            builder.append("C[").append(question.clozeItems.size).append(']')
            question.clozeItems.forEach { item ->
                builder.append(item.number).append('\u0000')
                builder.str(item.answer)
                builder.str(item.analysis)
                builder.append("o[").append(item.options.size).append(']')
                item.options.forEach { option ->
                    builder.str(option.key)
                    builder.str(option.text)
                }
            }
            builder.str(question.analysis)
            builder.str(question.category)
            // 图片只留跨设备稳定的结构字段；localPath 是本机文件路径，绝不参与哈希。
            builder.append("I[").append(question.images.size).append(']')
            question.images.sortedWith(compareBy({ it.order }, { it.id })).forEach { image ->
                builder.str(image.id)
                builder.append(image.order).append('\u0000')
                builder.append(image.width ?: -1).append('\u0000')
                builder.append(image.height ?: -1).append('\u0000')
                builder.append(image.sizeBytes).append('\u0000')
            }
            builder.append(question.score?.toString() ?: "-").append('\u0000')
            builder.str(question.subject)
            builder.str(question.grade)
            builder.str(question.difficulty)
            builder.str(question.knowledgePoints.sorted().joinToString("\u0001"))
            builder.str(question.tags.sorted().joinToString("\u0001"))
            builder.str(question.source)
            builder.append(question.version).append('\u0000')
            builder.str(question.reviewStatus)
            builder.append(question.aiConfidence?.toString() ?: "-").append('\u0000')
            builder.str(question.warnings.sorted().joinToString("\u0001"))
            builder.append("K[").append(question.inkStrokes.size).append(']')
            question.inkStrokes.forEach { stroke ->
                builder.str(stroke.tool)
                builder.append(stroke.argb).append('\u0000')
                builder.append(stroke.widthDp).append('\u0000')
                builder.str(stroke.anchor)
                builder.append(stroke.referenceHeightDp).append('\u0000')
                builder.append(stroke.points.size).append('[')
                stroke.points.forEach { point ->
                    builder.append(point.x).append(',').append(point.yDp).append(';')
                }
                builder.append(']')
            }
            builder.str(question.materialId ?: "-")
        }
        return builder.toString()
    }

    private fun StringBuilder.str(value: String) {
        append(value.length).append(':').append(value).append('\u0000')
    }
}
