package com.yiqiu.shirohaquiz.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yiqiu.shirohaquiz.ui.text.latex.LatexEngine
import com.yiqiu.shirohaquiz.ui.text.latex.LatexSvgRenderer
import com.yiqiu.shirohaquiz.ui.text.latex.LatexWebViewRenderer
import java.io.File

/**
 * Test screen for LaTeX rendering engine (PoC).
 * Uses WebView+MathJax as primary, falls back to QuickJS (LatexEngine).
 * Shows 12 formulas with rendered Bitmap and raw LaTeX source.
 */
@Composable
fun LatexTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity

    // State for WebView renderer
    var webRenderer by remember { mutableStateOf<LatexWebViewRenderer?>(null) }
    var webReady by remember { mutableStateOf(false) }
    var webInitError by remember { mutableStateOf<String?>(null) }

    // State for QuickJS fallback
    var qsInitDone by remember { mutableStateOf(false) }
    var qsInitTimeMs by remember { mutableStateOf<Long>(-1) }
    var qsInitError by remember { mutableStateOf<String?>(null) }

    // Per-formula results
    var results by remember { mutableStateOf<List<FormulaResult>>(emptyList()) }

    // dp -> px 转换
    val targetHeightPx = LocalDensity.current.run { 56.dp.roundToPx() }
    val targetHeightDp = 56

    // Test formulas
    val formulas = remember {
        listOf(
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
    }

    // Create and attach WebView renderer via 1dp AndroidView
    AndroidView(
        factory = { container ->
            val renderer = LatexWebViewRenderer(activity)
            renderer.attach(container)
            webRenderer = renderer
            container
        },
        update = { /* no-op */ },
        modifier = Modifier.height(1.dp)
    )

    // Warm up WebView
    LaunchedEffect(targetHeightPx) {
        webRenderer?.warmUp { ready ->
            webReady = ready
            if (!ready) {
                webInitError = "WEBVIEW_UNSUPPORTED"
                Log.w("LatexTestScreen", "WebView warm-up failed, will use QuickJS fallback")
            } else {
                Log.d("LatexTestScreen", "WebView ready")
            }
        }
    }

    // Initialize QuickJS fallback (always, for comparison/fallback)
    LaunchedEffect(targetHeightPx) {
        if (!qsInitDone) {
            LatexEngine.init(context)
            while (!LatexEngine.isReady() && LatexEngine.getInitError() == null) {
                Thread.sleep(50)
            }
            qsInitTimeMs = LatexEngine.getInitTimeMs()
            qsInitError = LatexEngine.getInitError()
            qsInitDone = true
            Log.d("LatexTestScreen", "QuickJS init: ready=${LatexEngine.isReady()} time=${qsInitTimeMs}ms error=$qsInitError")
        }
    }

    // Render formulas when WebView is ready (or fallback)
    LaunchedEffect(key1 = webReady, key2 = qsInitDone) {
        if ((webReady || (qsInitDone && LatexEngine.isReady())) && results.isEmpty()) {
            renderAllFormulas()
        }
    }

    // Release WebView on dispose
    DisposableEffect(Unit) {
        onDispose {
            webRenderer?.release()
        }
    }

    fun renderAllFormulas() {
        val renderer = webRenderer
        val useWeb = renderer != null && webReady

        val rendered = formulas.mapIndexed { i, latex ->
            if (useWeb) {
                // WebView path: async, we need to wait for each
                val result = FormulaResult(
                    latex = latex,
                    bitmap = null,
                    renderError = null,
                    source = "webview"
                )
                // We'll update this result via callback
                result
            } else {
                // QuickJS fallback
                val svgResult = LatexEngine.renderSvgString(latex)
                if (svgResult.isSuccess) {
                    val svg = svgResult.getOrNull() ?: ""
                    val bmp = if (svg.isNotBlank()) {
                        LatexSvgRenderer.render(svg, targetHeightDp, Color.BLACK)
                    } else null
                    val err = if (bmp != null && bmp.width > 0 && bmp.height > 0) null else "render returned null bitmap"
                    FormulaResult(
                        latex = latex,
                        bitmap = bmp,
                        renderError = err,
                        source = "quickjs"
                    )
                } else {
                    val errMsg = svgResult.exceptionOrNull()?.message ?: "Unknown error"
                    FormulaResult(
                        latex = latex,
                        bitmap = null,
                        renderError = "SVG error: $errMsg",
                        source = "quickjs"
                    )
                }
            }
        }

        // If WebView, trigger async renders and update state per item
        if (useWeb) {
            val mutableResults = rendered.toMutableList()
            results = mutableResults // initial with null bitmaps

            for ((idx, formula) in formulas.withIndex()) {
                renderer.render(formula, targetHeightPx, Color.BLACK) { bmp, error ->
                    val updated = mutableResults[idx].copy(
                        bitmap = bmp,
                        renderError = error
                    )
                    mutableResults[idx] = updated
                    // Post on animation to update UI per item
                    android.view.Choreographer.getInstance().postFrameCallback {
                        results = mutableResults.toList()
                    }
                }
            }
        } else {
            results = rendered

            // Write self-test file for QuickJS path
            writeSelftestFile()
        }
    }

    fun writeSelftestFile() {
        val sb = StringBuilder()
        sb.appendLine("webReady=$webReady webInitError=$webInitError")
        sb.appendLine("qsReady=${LatexEngine.isReady()} qsInitMs=$qsInitTimeMs qsError=$qsInitError")
        sb.appendLine("targetHeightPx=$targetHeightPx")
        for (i in results.indices) {
            val r = results[i]
            sb.appendLine("[#$i] tex=${r.latex} src=${r.source}")
            sb.appendLine("  bitmap=${r.bitmap?.width}x${r.bitmap?.height} error=${r.renderError}")
        }
        try {
            File(context.filesDir, "latex_selftest.txt").writeText(sb.toString())
        } catch (e: Exception) {
            Log.e("LatexTestScreen", "Failed to write selftest file", e)
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
                    text = "LaTeX 渲染引擎测试 (WebView + QuickJS)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "WebView: ${if (webReady) "就绪" else "未就绪 ($webInitError)"} | " +
                           "QuickJS: ${if (LatexEngine.isReady()) "${qsInitTimeMs} ms" else "失败 ($qsInitError)"}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "成功渲染: $successCount / ${results.size}",
                    fontSize = 14.sp,
                    color = if (successCount == results.size) Color.Green else Color(0xFFFFA500)
                )
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Rendered Bitmap
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .padding(end = 16.dp)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            bmp?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp),
                                    contentScale = ContentScale.Fit,
                                    alpha = 1f
                                )
                            } ?: Text(
                                text = "bitmap=null ${result.renderError ?: ""}",
                                color = Color.Red,
                                fontSize = 12.sp
                            )
                        }

                        // Debug: bitmap dimensions
                        Text(
                            text = "bmp=${bmp?.width}x${bmp?.height} [${result.source}]",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )

                        // Raw LaTeX + error
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
    val bitmap: Bitmap?,
    val renderError: String?,
    val source: String = "webview" // "webview" or "quickjs"
) {
    fun copy(
        latex: String = this.latex,
        bitmap: Bitmap? = this.bitmap,
        renderError: String? = this.renderError,
        source: String = this.source
    ): FormulaResult = FormulaResult(latex, bitmap, renderError, source)
}