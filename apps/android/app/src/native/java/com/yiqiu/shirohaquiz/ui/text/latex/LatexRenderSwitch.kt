package com.yiqiu.shirohaquiz.ui.text.latex

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.yiqiu.shirohaquiz.ui.components.LatexText
import com.yiqiu.shirohaquiz.ui.text.LatexDisplayFormatter

/** 全局公式渲染开关：默认开启，JLaTeXMath 同步渲染。 */
object LatexRenderSwitch {
    @Volatile
    var enabled: Boolean = true
}

/** 由 App 根提供渲染器实例；为 null 时自动回退纯文本。 */
val LocalLatexRenderer = compositionLocalOf<LatexWebViewRenderer?> { null }

/**
 * 统一文本入口：开启且渲染器可用时走 LatexText，否则保持原有纯文本行为。
 */
@Composable
fun LatexTextOrFallback(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(fontSize = 14.sp),
    heightPx: Int = 22
) {
    val renderer = LocalLatexRenderer.current
    if (LatexRenderSwitch.enabled && renderer != null) {
        LatexText(text = text, renderer = renderer, modifier = modifier, heightPx = heightPx)
    } else {
        Text(text = LatexDisplayFormatter.format(text), modifier = modifier, style = style)
    }
}