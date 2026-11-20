package com.yiqiu.shirohaquiz.sync.sync

import com.yiqiu.shirohaquiz.importer.model.Option
import com.yiqiu.shirohaquiz.importer.model.Question
import com.yiqiu.shirohaquiz.importer.model.QuestionImage
import com.yiqiu.shirohaquiz.importer.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** blob 编解码用 org.json → 走 Robolectric（纯 JVM 下 JSONObject 是桩）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncBankCodecTest {
    private fun sampleQuestion() = Question(
        id = "q1",
        number = "1",
        type = QuestionType.SINGLE,
        question = "阿司匹林的作用机制是？",
        options = listOf(Option("A", "抑制 COX"), Option("B", "阻断 H2")),
        answer = listOf("A"),
        analysis = "非甾体抗炎药。",
        category = "解热镇痛药",
        images = listOf(QuestionImage(id = "img1", localPath = "/data/a/question_assets/x.png", order = 0, width = 100, height = 50)),
        tags = listOf("COX")
    )

    @Test
    fun encodeDecodeKeepsContent() {
        val questions = listOf(sampleQuestion())
        val decoded = SyncBankCodec.decode(SyncBankCodec.encode("药理", "第一章 绪论", questions))
        assertEquals("药理", decoded.groupName)
        assertEquals("第一章 绪论", decoded.name)
        assertEquals(1, decoded.questions.size)
        val question = decoded.questions.single()
        assertEquals(questions.single().id, question.id)
        assertEquals(questions.single().question, question.question)
        assertEquals(questions.single().answer, question.answer)
        assertEquals(questions.single().options, question.options)
        assertEquals(questions.single().analysis, question.analysis)
        assertEquals(questions.single().tags, question.tags)
    }

    @Test
    fun deviceLocalImagePathIsNotTransferred() {
        val decoded = SyncBankCodec.decode(SyncBankCodec.encode("药理", "库", listOf(sampleQuestion())))
        val image = decoded.questions.single().images.single()
        assertEquals("跨设备路径不该被传输", "", image.localPath)
        assertEquals(0, image.order)
        assertEquals(100, image.width)
    }

    @Test
    fun syncRoundTripBlobStillHashesToSameContentHash() {
        val original = com.yiqiu.shirohaquiz.state.QuizBank(
            id = "bank_a", name = "库", groupName = "药理", questions = listOf(sampleQuestion())
        )
        val decoded = SyncBankCodec.decode(SyncBankCodec.encode("药理", "库", original.questions))
        val restored = original.copy(questions = decoded.questions)
        // localPath 不参与 hash，所以编解码往返后内容指纹必须一致。
        assertEquals(SyncIdentity.contentHash(original), SyncIdentity.contentHash(restored))
        assertTrue(decoded.questions.isNotEmpty())
    }
}
