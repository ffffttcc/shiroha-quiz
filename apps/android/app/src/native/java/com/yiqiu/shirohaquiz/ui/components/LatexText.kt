package com.yiqiu.shirohaquiz.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.maxWidth
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiqiu.shirohaquiz.ui.text.latex.LatexWebViewRenderer

@Composable
fun LatexText(
    text: String,
    renderer: LatexWebViewRenderer? = null,
    modifier: Modifier = Modifier,
    heightPx: Int = 40
) {
    val segments = remember(text) { splitLatex(text) }
    
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = Int.MAX_VALUE
    ) {
        segments.forEachIndexed { idx, seg ->
            when (seg) {
                is Segment.Plain -> {
                    Text(
                        text = seg.text,
                        fontSize = 14.sp,
                        color = Color(0xFF1A1A2E),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(0.dp)
                            .weight(1f, fill = true)
                    )
                }
                is Segment.Formula -> {
                    val bmp by remember { mutableStateOf<Bitmap?>(null) }
                    val err by remember { mutableStateOf<String?>(null) }
                    
                    LaunchedEffect(seg.tex, renderer) {
                        bmp = null
                        err = null
                        renderer?.render(seg.tex, heightPx, AndroidColor.BLACK) { bitmap, error ->
                            bmp = bitmap
                            err = error
                        }
                    }
                    
                    if (bmp != null) {
                        Image(
                            bitmap = bmp!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .heightIn(max = (heightPx + 8).dp)
                                .width(0.dp)
                                .weight(1f, fill = true),
                            contentScale = ContentScale.Fit
                        )
                    } else if (err != null) {
                        Text(
                            text = "$${seg.tex}$$",
                            fontSize = 14.sp,
                            color = Color(0xFFCC0000),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(0.dp)
                                .weight(1f, fill = true)
                        )
                    } else {
                        Text(
                            text = "$${seg.tex}$$",
                            fontSize = 14.sp,
                            color = Color(0xFF888888),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(0.dp)
                                .weight(1f, fill = true)
                        )
                    }
                }
            }
        }
    }
}

sealed class Segment {
    data class Plain(val text: String) : Segment()
    data class Formula(val tex: String) : Segment()
}

internal fun splitLatex(text: String): List<Segment> {
    val result = mutableListOf<Segment>()
    var i = 0
    val len = text.length
    val plainBuf = StringBuilder()
    
    while (i < len) {
        val ch = text[i]
        if (ch == '$') {
            // Check for $$ (display math) or $ (inline)
            if (i + 1 < len && text[i + 1] == '$') {
                // Display math: $$...$$
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
                    // Unclosed $$, treat remaining as plain text
                    plainBuf.append("$$").append(text.substring(texStart))
                    break
                }
            } else {
                // Inline math: $...$
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
                    // Unclosed $, treat as plain text
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
    
    // Merge consecutive Plain segments
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