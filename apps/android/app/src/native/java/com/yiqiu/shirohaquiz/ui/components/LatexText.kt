package com.yiqiu.shirohaquiz.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.yiqiu.shirohaquiz.ui.text.latex.LatexWebViewRenderer

@Composable
fun LatexText(
    text: String,
    renderer: LatexWebViewRenderer? = null,
    modifier: Modifier = Modifier,
    heightPx: Int = 22
) {
    val segments = remember(text) { splitLatex(text) }
    
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        segments.forEachIndexed { idx, seg ->
            when (seg) {
                is Segment.Plain -> {
                    Text(
                        text = seg.text,
                        fontSize = 14.sp,
                        color = Color(0xFF1A1A2E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is Segment.Formula -> {
                    var bmp by remember { mutableStateOf<Bitmap?>(null) }
                    var err by remember { mutableStateOf<String?>(null) }
                    
                    val density = LocalDensity.current.density
                    LaunchedEffect(seg.tex, renderer, density) {
                        bmp = null
                        err = null
                        val px = (heightPx * density).roundToInt()
                        renderer?.render(seg.tex, px, AndroidColor.BLACK) { bitmap, error ->
                            bmp = bitmap
                            err = error
                        }
                    }
                    
                    if (bmp != null) {
                        bmp?.let {
                            Log.d("LatexTextDbg", "tex=[${seg.tex}] bmp=${it.width}x${it.height}")
                        }
                        val bmpNonNull = bmp!!
                        val wDp = if (bmpNonNull.height > 0) heightPx * bmpNonNull.width / bmpNonNull.height else heightPx * 2
                        Image(
                            bitmap = bmpNonNull.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.height(heightPx.dp).width(wDp.dp)
                        )
                    } else if (err != null) {
                        err?.let { Log.d("LatexTextDbg", "tex=[${seg.tex}] err=$it") }
                        Text(
                            text = "$${seg.tex}$$",
                            fontSize = 14.sp,
                            color = Color(0xFFCC0000),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "$${seg.tex}$$",
                            fontSize = 14.sp,
                            color = Color(0xFF888888),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (idx < segments.lastIndex) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
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