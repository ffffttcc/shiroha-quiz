package com.yiqiu.shirohaquiz.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.io.File

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

    // dp -> px 转换（Composable 上下文直接可用 LocalDensity）
    val targetHeightPx = LocalDensity.current.run { 56.dp.roundToPx() }

    fun renderFormulas(targetHeight: Int) {
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

        val rendered = formulas.mapIndexed { i, latex ->
            val svgResult = LatexEngine.renderSvgString(latex)
            if (svgResult.isSuccess) {
                val svg = svgResult.getOrNull() ?: ""
                val svgOkFlag = !svg.isBlank()
                val svgLenVal = svg.length
                val svgHeadVal = svg.take(200)
                
                // Get sanitized SVG info from LatexSvgRenderer.sanitize
                val sanitizedResult = com.yiqiu.shirohaquiz.ui.text.latex.LatexSvgRenderer.sanitize(
                    svg, 
                    android.graphics.Color.BLACK
                )
                val sanitizedHeadVal = sanitizedResult.cleaned.take(160)
                val vbWVal = sanitizedResult.vbWidth
                val vbHVal = sanitizedResult.vbHeight
                val targetHVal = 56 // targetHeight will be passed
                
                val bmp = if (!svg.isBlank()) {
                    LatexSvgRenderer.render(svg, 56, android.graphics.Color.BLACK)
                } else null
                val bmpWVal = bmp?.width ?: 0
                val bmpHVal = bmp?.height ?: 0
                
                val errorMsg: String? = if (bmp != null && bmp.width > 0 && bmp.height > 0) null else "render returned null bitmap"
                
                // Dump PNG for diagnosis
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    val pngDir = File(context.filesDir, "latex_png").apply { mkdirs() }
                    File(pngDir, "%02d.png".format(i)).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                
                // Dump SVG to file for diagnosis
                val svgDir = File(context.filesDir, "latex_svg").apply { mkdirs() }
                File(svgDir, "%02d.svg".format(i)).writeText(svg)
                
                // Log each bitmap generation for debugging
                android.util.Log.d("LatexTestScreen", "idx=$i bmp=${bmp?.width}x${bmp?.height} vb=${sanitizedResult.vbWidth}x${sanitizedResult.vbHeight} error=$errorMsg")
                
                FormulaResult(
                    latex = latex,
                    bitmap = bmp,
                    renderError = if (bmp != null && bmp.width > 0 && bmp.height > 0) null else "render returned null bitmap",
                    svgOk = svgOkFlag,
                    svgLen = svg.length,
                    svgHead = svg.take(200),
                    sanitizedHead = sanitizedResult.cleaned.take(160),
                    vbW = sanitizedResult.vbWidth,
                    vbH = sanitizedResult.vbHeight,
                    targetH = 56,
                    bmpW = bmp?.width ?: 0,
                    bmpH = bmp?.height ?: 0,
                    error = if (bmp != null && bmp.width > 0 && bmp.height > 0) null else "render returned null bitmap"
                )
            } else {
                val errMsg = svgResult.exceptionOrNull()?.message ?: "Unknown error"
                FormulaResult(
                    latex = latex,
                    bitmap = null,
                    renderError = "SVG error: $errMsg",
                    svgOk = false,
                    svgLen = 0,
                    svgHead = "",
                    sanitizedHead = "",
                    vbW = 0f,
                    vbH = 0f,
                    targetH = 56,
                    bmpW = 0,
                    bmpH = 0,
                    error = "SVG error: $errMsg"
                )
            }
        }
        results = rendered
        
        // Write self-test file
        val sb = StringBuilder()
        sb.appendLine("engine=${LatexEngine.isReady()} initMs=$initTimeMs")
        sb.appendLine("targetHeightPx=56")
        for (i in results.indices) {
            val r = results[i]
            sb.appendLine("[#$i] tex=${r.latex}")
            sb.appendLine("  okSvg=${r.svgOk} svgLen=${r.svgLen} svgHead=${r.svgHead}")
            sb.appendLine("  sanitizedHead=${r.sanitizedHead}")
            sb.appendLine("  vb=${r.vbW}x${r.vbH} targetH=${r.targetH} bitmap=${r.bmpW}x${r.bmpH}")
            sb.appendLine("  error=${r.error}")
        }
        try {
            File(context.filesDir, "latex_selftest.txt").writeText(sb.toString())
        } catch (e: Exception) {
            android.util.Log.e("LatexTestScreen", "Failed to write selftest file", e)
        }
    }

    LaunchedEffect(targetHeightPx) {
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
                renderFormulas(targetHeightPx)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            results.forEach { result ->
                val bmp = result.bitmap
                val bmpW = bmp?.width ?: 0
                val bmpH = bmp?.height ?: 0
                
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Rendered Bitmap - 固定高度 72dp，内容居中显示
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .padding(end = 16.dp)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            result.bitmap?.let { bmp ->
                                // 优先用 Image，若不生效可改 Canvas 手绘
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp),
                                    contentScale = ContentScale.Fit,
                                    alpha = 1f
                                )
                            } ?: Text(
                                text = "bitmap=null",
                                color = Color.Red,
                                fontSize = 12.sp
                            )
                        }
                        
                        // 调试信息：显示 bitmap 实际尺寸
                        Text(
                            text = "bmp=${bmp?.width}x${bmp?.height}",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )

                        // Raw LaTeX source + render error
                        Column(modifier = Modifier.fillMaxWidth()) {
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
    val bitmap: android.graphics.Bitmap?,
    val renderError: String?,
    val svgOk: Boolean = false,
    val svgLen: Int = 0,
    val svgHead: String = "",
    val sanitizedHead: String = "",
    val vbW: Float = 0f,
    val vbH: Float = 0f,
    val targetH: Int = 0,
    val bmpW: Int = 0,
    val bmpH: Int = 0,
    val error: String? = null
)