package com.yiqiu.shirohaquiz.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiqiu.shirohaquiz.ui.text.latex.LatexEngine
import com.yiqiu.shirohaquiz.ui.text.latex.LatexSvgRenderer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect

/**
 * Test screen for LaTeX rendering engine (PoC).
 * Shows 12 formulas with rendered Bitmap and raw LaTeX source.
 */
@Composable
fun LatexTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var initDone by remember { mutableStateOf(false) }
    var initTimeMs by remember { mutableStateOf<Long>(-1) }
    var initError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<FormulaResult>>(emptyList()) }

    // dp -> px 转换（Composable 上下文可用 LocalDensity）
    val targetHeightPx = remember { LocalDensity.current.run { 56.dp.roundToPx() } }

    fun renderFormulas() {
        val formulas = listOf(
            "\\mathbf{B}_{12}",
            "\\mathrm{ED}_{50}",
            "\\mathrm{LD}_{50}/\\mathrm{ED}_{50}",
            "\\frac{\\mathrm{d}C}{\\mathrm{d}t}=-kC^{n}",
            "K^{+}",
            "pK_{a}",
            "\\mathrm{pK}_{\\mathrm{a}_1}=1.2",
            "Ca^{2+}",
            "\\mathrm{Ox}_{1}",
            "\\rightleftharpoons",
            "1.8\\times10^{-5}",
            "K_{ZnY}^{\\prime}"
        )

        val rendered = formulas.map { latex ->
            val svgResult = LatexEngine.renderSvgString(latex)
            val pair: Pair<ImageBitmap?, String?> = if (svgResult.isSuccess) {
                val svg = svgResult.getOrNull()
                val bmp = svg?.let { LatexSvgRenderer.render(it, targetHeightPx) }
                bmp?.let { it.asImageBitmap() } to null
            } else {
                null to svgResult.exceptionOrNull()?.message ?: "Unknown error"
            }
            FormulaResult(latex, pair.first, pair.second)
        }
        results = rendered
    }

    LaunchedEffect(true) {
        if (!initDone) {
            LatexEngine.init(context)
            // Wait for init to complete
            while (!LatexEngine.isReady() && LatexEngine.getInitError() == null) {
                Thread.sleep(50)
            }
            initTimeMs = LatexEngine.getInitTimeMs()
            initError = LatexEngine.getInitError()
            initDone = true

            if (LatexEngine.isReady()) {
                renderFormulas()
            }
        }
    }

    val successCount = results.count { it.bitmap != null }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "LaTeX 渲染引擎测试 (PoC)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "引擎初始化耗时: ${if (initTimeMs >= 0) "${initTimeMs} ms" else "--"}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Text(
                    text = "成功渲染: $successCount / ${results.size}",
                    fontSize = 14.sp,
                    color = if (successCount == results.size) Color.Green else Color(0xFFFFA500)
                )
                initError?.let {
                    Text(
                        text = "初始化错误: $it",
                        fontSize = 14.sp,
                        color = Color.Red
                    )
                }
            }
        }

        // Formula list
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(results) { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rendered Bitmap
                        result.bitmap?.let { bmp ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(end = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } ?: Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(end = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "渲染失败: ${result.renderError ?: "Unknown"}",
                                fontSize = 12.sp,
                                color = Color.Red,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Raw LaTeX source + render error
                        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Text(
                                text = result.latex,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            result.renderError?.let { err ->
                                Text(
                                    text = "错误: $err",
                                    fontSize = 11.sp,
                                    color = Color.Red,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class FormulaResult(
    val latex: String,
    val bitmap: ImageBitmap?,
    val renderError: String?
)