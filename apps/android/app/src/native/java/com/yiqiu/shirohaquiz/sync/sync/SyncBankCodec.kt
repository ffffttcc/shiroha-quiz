package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.ClozeItem
import com.yiqiu.shirohaquiz.importer.model.InkPoint
import com.yiqiu.shirohaquiz.importer.model.InkStroke
import com.yiqiu.shirohaquiz.importer.model.Option
import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionImage
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import org.json.JSONArray
import org.json.JSONObject

data class DecodedSyncBank(
    val groupName: String,
    val name: String,
    val questions: List<Question>,
    /** 书级分组；旧 blob（version=1）没有该字段 → 空串，退化回一级。 */
    val bookName: String = ""
)

/**
 * 单库传输格式：只装题库内容，不掺错题/收藏/记录。
 *
 * 刻意不复用备份 ZIP 的导入口：那条路会连状态一起映射，而同步的冲突规则是
 * 「整份新者胜」，掺进逐条状态映射会破坏这个语义。
 * 图片素材本身不随同步传输（跨设备路径无效），只带结构字段。
 */
object SyncBankCodec {
    private const val FORMAT_VERSION = 2

    fun encode(groupName: String, name: String, questions: List<Question>, bookName: String = ""): ByteArray {
        val root = JSONObject()
        root.put("syncVersion", FORMAT_VERSION)
        root.put("groupName", groupName)
        root.put("name", name)
        if (bookName.isNotBlank()) root.put("bookName", bookName)
        val array = JSONArray()
        questions.forEach { array.put(questionToJson(it)) }
        root.put("questions", array)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): DecodedSyncBank {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = root.optInt("syncVersion", FORMAT_VERSION)
        require(version <= FORMAT_VERSION) { "单库同步格式版本过新，请升级 App。" }
        val array = root.optJSONArray("questions") ?: JSONArray()
        val questions = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::questionFromJson)
        }
        return DecodedSyncBank(
            groupName = root.optString("groupName"),
            name = root.optString("name"),
            questions = questions,
            bookName = root.optString("bookName")
        )
    }

    private fun questionToJson(question: Question): JSONObject = JSONObject().apply {
        put("id", question.id)
        put("number", question.number)
        put("type", question.type.name)
        put("question", question.question)
        put("options", optionsToJson(question.options))
        put("answer", JSONArray(question.answer))
        put("blankAnswers", JSONArray().apply {
            question.blankAnswers.forEach { group -> put(JSONArray(group)) }
        })
        put("clozeItems", JSONArray().apply {
            question.clozeItems.forEach { item ->
                put(JSONObject().apply {
                    put("number", item.number)
                    put("options", optionsToJson(item.options))
                    put("answer", item.answer)
                    put("analysis", item.analysis)
                })
            }
        })
        put("analysis", question.analysis)
        put("category", question.category)
        put("images", JSONArray().apply {
            question.images.forEach { image ->
                put(JSONObject().apply {
                    put("id", image.id)
                    put("sourceName", image.sourceName)
                    put("order", image.order)
                    image.width?.let { put("width", it) }
                    image.height?.let { put("height", it) }
                    put("sizeBytes", image.sizeBytes)
                })
            }
        })
        question.score?.let { put("score", it) }
        put("subject", question.subject)
        put("grade", question.grade)
        put("difficulty", question.difficulty)
        put("knowledgePoints", JSONArray(question.knowledgePoints))
        put("tags", JSONArray(question.tags))
        put("source", question.source)
        put("version", question.version)
        put("reviewStatus", question.reviewStatus)
        question.aiConfidence?.let { put("aiConfidence", it) }
        put("warnings", JSONArray(question.warnings))
        put("inkStrokes", JSONArray().apply {
            question.inkStrokes.forEach { stroke ->
                put(JSONObject().apply {
                    put("tool", stroke.tool)
                    put("argb", stroke.argb)
                    put("widthDp", stroke.widthDp.toDouble())
                    put("anchor", stroke.anchor)
                    put("referenceHeightDp", stroke.referenceHeightDp.toDouble())
                    put("points", JSONArray().apply {
                        stroke.points.forEach { point ->
                            put(JSONArray().apply {
                                put(point.x.toDouble())
                                put(point.yDp.toDouble())
                            })
                        }
                    })
                })
            }
        })
        question.materialId?.let { put("materialId", it) }
    }

    private fun optionsToJson(options: List<Option>): JSONArray = JSONArray().apply {
        options.forEach { option ->
            put(JSONObject().apply {
                put("key", option.key)
                put("text", option.text)
            })
        }
    }

    private fun questionFromJson(json: JSONObject): Question {
        val type = runCatching { QuestionType.valueOf(json.optString("type")) }.getOrDefault(QuestionType.SINGLE)
        val options = json.optJSONArray("options").toOptions()
        val blanks = mutableListOf<List<String>>()
        json.optJSONArray("blankAnswers")?.let { outer ->
            for (index in 0 until outer.length()) {
                val inner = outer.optJSONArray(index) ?: continue
                blanks += (0 until inner.length()).map { inner.optString(it) }
            }
        }
        val cloze = mutableListOf<ClozeItem>()
        json.optJSONArray("clozeItems")?.let { outer ->
            for (index in 0 until outer.length()) {
                val item = outer.optJSONObject(index) ?: continue
                cloze += ClozeItem(
                    number = item.optInt("number", index + 1),
                    options = item.optJSONArray("options").toOptions(),
                    answer = item.optString("answer"),
                    analysis = item.optString("analysis")
                )
            }
        }
        val images = mutableListOf<QuestionImage>()
        json.optJSONArray("images")?.let { outer ->
            for (index in 0 until outer.length()) {
                val item = outer.optJSONObject(index) ?: continue
                images += QuestionImage(
                    id = item.optString("id", "img-$index"),
                    // 图片素材不随同步传输：路径留空，避免指到别的设备的文件。
                    localPath = "",
                    sourceName = item.optString("sourceName"),
                    order = item.optInt("order", index),
                    width = item.optInt("width", -1).takeIf { it >= 0 },
                    height = item.optInt("height", -1).takeIf { it >= 0 },
                    sizeBytes = item.optLong("sizeBytes", 0L)
                )
            }
        }
        val strokes = mutableListOf<InkStroke>()
        json.optJSONArray("inkStrokes")?.let { outer ->
            for (index in 0 until outer.length()) {
                val item = outer.optJSONObject(index) ?: continue
                val points = mutableListOf<InkPoint>()
                item.optJSONArray("points")?.let { array ->
                    for (pointIndex in 0 until array.length()) {
                        val pair = array.optJSONArray(pointIndex) ?: continue
                        points += InkPoint(pair.optDouble(0).toFloat(), pair.optDouble(1).toFloat())
                    }
                }
                strokes += InkStroke(
                    tool = item.optString("tool", "pen"),
                    argb = item.optLong("argb", 0xFF2855B1L),
                    widthDp = item.optDouble("widthDp", 2.6).toFloat(),
                    points = points,
                    anchor = item.optString("anchor"),
                    referenceHeightDp = item.optDouble("referenceHeightDp", 0.0).toFloat()
                )
            }
        }
        return Question(
            id = json.optString("id"),
            number = json.optString("number"),
            type = type,
            question = json.optString("question"),
            options = options,
            answer = json.optJSONArray("answer").toStringList(),
            blankAnswers = blanks,
            clozeItems = cloze,
            analysis = json.optString("analysis"),
            category = json.optString("category"),
            images = images,
            score = if (json.has("score")) json.optDouble("score") else null,
            subject = json.optString("subject"),
            grade = json.optString("grade"),
            difficulty = json.optString("difficulty"),
            knowledgePoints = json.optJSONArray("knowledgePoints").toStringList(),
            tags = json.optJSONArray("tags").toStringList(),
            source = json.optString("source"),
            version = json.optInt("version", 1),
            reviewStatus = json.optString("reviewStatus", "approved"),
            aiConfidence = if (json.has("aiConfidence")) json.optDouble("aiConfidence") else null,
            warnings = json.optJSONArray("warnings").toStringList(),
            inkStrokes = strokes,
            materialId = json.optString("materialId").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun JSONArray?.toOptions(): List<Option> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { Option(it.optString("key"), it.optString("text")) }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }
}
