package com.yiqiu.shirohaquiz.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.rememberImagePainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toDp
import com.yiqiu.shirohaquiz.ui.text.latex.LatexWebViewRenderer

@Composable
fun LatexText(
    text: String,
    renderer: LatexWebViewRenderer? = null,
    modifier: Modifier = Modifier,
    heightPx: Int = 40
) {
    val density = LocalDensity.current
    val segments = remember(text) { splitLatex(text) }
    var bitmaps by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }

    LaunchedEffect(segments, renderer, heightPx) {
        val map = mutableMapOf<Int, Bitmap>()
        segments.forEachIndexed { i, seg ->
            if (seg is Segment.Formula) {
                renderer?.render(seg.tex, heightPx, AndroidColor.BLACK) { bmp, _ ->
                    if (bmp != null) map[i] = bmp
                }
            }
        }
        bitmaps = map
    }

    Text(
        text = buildAnnotatedString {
            segments.forEachIndexed { i, seg ->
                when (seg) {
                    is Segment.Plain -> append(seg.text)
                    is Segment.Formula -> if (bitmaps.containsKey(i)) appendInlineContent("f$i", alternateText = seg.tex) else append(seg.tex)
                }
            }
        },
        inlineContent = buildMap {
            segments.forEachIndexed { i, seg ->
                val bmp = bitmaps[i] ?: return@forEachIndexed
                put("f$i", InlineTextContent(
                    placeholder = Placeholder(
                        width = with(density) { bmp.width.toDp() },
                        height = with(density) { bmp.height.toDp() },
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                    )
                ) { _ ->
                    Image(
                        painter = rememberImagePainter(bmp.asImageBitmap()),
                        contentDescription = null
                    )
                }
            }
        },
        modifier = modifier,
        color = Color(0xFF1A1A2E),
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

sealed class Segment {
    data class Plain(val text: String) : Segment()
    data class Formula(val tex: String) : Segment()
}

private val BARE_LATEX_CMD = Regex("""\\(?:mathrm|frac|sqrt|ce|text|mathit)\b""")

private fun wrapBareLatex(src: String): String {
    val sb = StringBuilder(src.length + 16)
    var i = 0
    var inDollar = false
    while (i < src.length) {
        val c = src[i]
        if (c == '$') { inDollar = !inDollar; sb.append(c); i++; continue }
        if (!inDollar) {
            val m = BARE_LATEX_CMD.find(src, i)
            if (m != null && m.range.first == i) {
                var j = i
                var depth = 0
                while (j < src.length) {
                    val ch = src[j]
                    if (ch == '{') { depth++; j++; continue }
                    if (ch == '}') { depth--; j++; continue }
                    if (depth == 0 && (ch == ' ' || ch == '\u3000' || ch == '\n' || ch == '\r' || ch == '$' || ch in '\u4e00'..'\u9fff')) break
                    j++
                }
                sb.append('$').append(src, i, j).append('$')
                i = j
                continue
            }
        }
        sb.append(c); i++
    }
    return sb.toString()
}

internal fun splitLatex(rawText: String): List<Segment> {
    val text = wrapBareLatex(rawText)
    val result = mutableListOf<Segment>()
    var i = 0
    val len = text.length
    val plainBuf = StringBuilder()

    while (i < len) {
        val ch = text[i]
        if (ch == '$') {
            if (i + 1 < len && text[i + 1] == '$') {
                if (plainBuf.isNotEmpty()) {
                    result.add(Segment.Plain(plainBuf.toString()))
                    plainBuf.clear()
                }
                i += 2
                val texStart = i
                var found = false
                while (i + 1 < len) {
                    if (text[i] == '$' && text[i + 1] == '$') {
                        val tex = text.substring(texStart, i)
                        result.add(Segment.Formula(tex))
                        i += 2
                        found = true
                        break
                    }
                    i++
                }
                if (!found) {
                    plainBuf.append("$$").append(text.substring(texStart))
                    break
                }
            } else {
                if (plainBuf.isNotEmpty()) {
                    result.add(Segment.Plain(plainBuf.toString()))
                    plainBuf.clear()
                }
                i++
                val texStart = i
                var found = false
                while (i < len) {
                    if (text[i] == '$') {
                        val tex = text.substring(texStart, i)
                        result.add(Segment.Formula(tex))
                        i++
                        found = true
                        break
                    }
                    i++
                }
                if (!found) {
                    plainBuf.append("$").append(text.substring(texStart))
                    break
                }
            }
        } else {
            plainBuf.append(ch)
            i++
        }
    }

    if (plainBuf.isNotEmpty()) {
        result.add(Segment.Plain(plainBuf.toString()))
    }

    val merged = mutableListOf<Segment>()
    for (seg in result) {
        if (seg is Segment.Plain) {
            if (merged.lastOrNull() is Segment.Plain) {
                val last = merged.removeAt(merged.lastIndex) as Segment.Plain
                merged.add(Segment.Plain(last.text + seg.text))
            } else {
                merged.add(seg)
            }
        } else {
            merged.add(seg)
        }
    }
    return merged
}
