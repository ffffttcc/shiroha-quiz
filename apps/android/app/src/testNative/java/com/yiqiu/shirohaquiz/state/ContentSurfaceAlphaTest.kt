package com.yiqiu.shirohaquiz.state

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 界面不透明度（content_surface_alpha）的生效语义。
 *
 * 这几个行为 83 个老测试一个都覆盖不到：滑杆是新功能，alpha 又只影响容器底色，
 * 出错时不会让任何既有测试变红，所以单独锁住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContentSurfaceAlphaTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences
        get() = context.getSharedPreferences("shiroha_quiz_native", Context.MODE_PRIVATE)

    @Before
    fun initialize() {
        QuizRepository.resetForTesting()
        preferences.edit().clear().commit()
        QuizRepository.init(context)
    }

    @After
    fun reset() {
        QuizRepository.resetForTesting()
    }

    /** 从未设置过（-1f）时按 1f 处理：老用户观感不变。 */
    @Test
    fun unsetAlphaDefaultsToFullyOpaque() {
        QuizRepository.setCustomBackgroundEnabled(context, true)
        assertEquals(1f, QuizRepository.contentSurfaceAlphaFactor(), 0.0001f)
    }

    /** 设过的值原样生效。 */
    @Test
    fun configuredAlphaIsUsed() {
        QuizRepository.setCustomBackgroundEnabled(context, true)
        QuizRepository.updateContentSurfaceAlphaPreview(0.55f)
        assertEquals(0.55f, QuizRepository.contentSurfaceAlphaFactor(), 0.0001f)
    }

    /** 背景图关闭时一律不生效，且不能把用户设过的值清掉。 */
    @Test
    fun disabledBackgroundIgnoresAlphaButKeepsStoredValue() {
        QuizRepository.updateContentSurfaceAlphaPreview(0.55f)
        QuizRepository.setCustomBackgroundEnabled(context, false)
        assertEquals(1f, QuizRepository.contentSurfaceAlphaFactor(), 0.0001f)
        assertEquals(0.55f, QuizRepository.contentSurfaceAlpha, 0.0001f)
    }

    /** 边界：0f 是「全透明」，不能被 `>= 0f` 判断误当成未设置。 */
    @Test
    fun fullyTransparentIsNotTreatedAsUnset() {
        QuizRepository.setCustomBackgroundEnabled(context, true)
        QuizRepository.updateContentSurfaceAlphaPreview(0f)
        assertEquals(0f, QuizRepository.contentSurfaceAlphaFactor(), 0.0001f)
    }

    /** 越界输入夹紧，不至于让容器色算出非法 alpha。 */
    @Test
    fun outOfRangePreviewIsClamped() {
        QuizRepository.setCustomBackgroundEnabled(context, true)
        QuizRepository.updateContentSurfaceAlphaPreview(2.5f)
        assertEquals(1f, QuizRepository.contentSurfaceAlphaFactor(), 0.0001f)
        QuizRepository.updateContentSurfaceAlphaPreview(-1.5f)
        assertEquals(0f, QuizRepository.contentSurfaceAlphaFactor(), 0.0001f)
    }

    /** 落盘往返：key 从 Int 改成 Float 后仍能读回来（commitContentSurfaceAlpha 才 persist）。 */
    @Test
    fun committedAlphaSurvivesReload() {
        QuizRepository.setCustomBackgroundEnabled(context, true)
        QuizRepository.updateContentSurfaceAlphaPreview(0.42f)
        // 拖动阶段只改内存：commit 之前 prefs 里不该有值。
        assertFalse(preferences.contains("content_surface_alpha"))

        QuizRepository.commitContentSurfaceAlpha(context)
        QuizRepository.resetForTesting()
        QuizRepository.init(context)

        assertEquals(0.42f, QuizRepository.contentSurfaceAlphaFactor(), 0.0001f)
        assertEquals(0.42f, QuizRepository.contentSurfaceAlpha, 0.0001f)
    }
}
