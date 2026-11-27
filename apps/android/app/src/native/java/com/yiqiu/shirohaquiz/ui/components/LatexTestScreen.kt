package com.yiqiu.shirohaquiz.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import android.widget.FrameLayout
import android.view.Choreographer

/**
 * Test screen for LaTeX rendering engine (PoC).
 * Uses WebView+MathJax as primary, falls back to QuickJS (LatexEngine).
 * Shows 12 formulas with rendered Bitmap and raw LaTeX source.
 */
@Composable
fun LatexTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    // WebView renderer
    val renderer = remember { LatexWebViewRenderer(activity) }
    var webReady by remember { mutableStateOf(false) }
    var webInitError by remember { mutableStateOf<String?>(null) }

    // QuickJS fallback
    var qsInitDone by remember { mutableStateOf(false) }
    var qsInitTimeMs by remember { mutableStateOf<Long>(-1) }
    var qsInitError by remember { mutableStateOf<String?>(null) }

    // Per-formula results (each has its own mutableState for independent recomposition)
    val results = remember { mutableStateListOf<FormulaResult>() }

    val targetHeightPx = LocalDensity.current.run { 56.dp.roundToPx() }
    val targetHeightDp = 56

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

    // 1dp AndroidView to host WebView
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).also { host -> renderer.attach(host) }
        },
        modifier = Modifier.fillMaxWidth().height(1.dp)
    )

    // Warm up WebView
    LaunchedEffect(targetHeightPx) {
        renderer.warmUp { ready ->
            webReady = ready
            if (!ready) {
                webInitError = "WEBVIEW_UNSUPPORTED"
                Log.w("LatexRender", "WebView warm-up failed")
            } else {
                Log.d("LatexRender", "WebView ready")
            }
        }
    }

    // Initialize QuickJS fallback
    LaunchedEffect(targetHeightPx) {
        if (!qsInitDone) {
            LatexEngine.init(context)
            while (!LatexEngine.isReady() && LatexEngine.getInitError() == null) {
                Thread.sleep(50)
            }
            qsInitTimeMs = LatexEngine.getInitTimeMs()
            qsInitError = LatexEngine.getInitError()
            qsInitDone = true
            Log.d("LatexRender", "QuickJS init: ready=${LatexEngine.isReady()} time=${qsInitTimeMs}ms error=$qsInitError")
        }
    }

    // Render when ready
    LaunchedEffect(key1 = webReady, key2 = qsInitDone) {
        if ((webReady || (qsInitDone && LatexEngine.isReady())) && results.isEmpty()) {
            renderAll()
        }
    }

    // Release on dispose
    DisposableEffect(Unit) {
        onDispose { renderer.release() }
    }

    fun renderAll() {
        val useWeb = webReady

        // Initialize results with empty bitmaps
        val initial = formulas.map { latex ->
            FormulaResult(latex = latex, source = if (useWeb) "webview" else "quickjs")
        }
        results.clear()
        results.addAll(initial)

        if (useWeb) {
            formulas.forEachIndexed { idx, latex ->
                renderer.render(latex, targetHeightPx, 0xFF000000) { bmp, error ->
                    // Post on animation to update this single item
                    val updated = results[idx].copy(
                        bitmap = bmp,
                        renderError = error
                    )
                    Choreographer.getInstance().postFrameCallback {
                        results[idx] = updated
                    }
                }
            }
        } else {
            // QuickJS fallback (synchronous)
            val fallbackResults = formulas.mapIndexed { idx, latex ->
                val svgResult = LatexEngine.renderSvgString(latex)
                if (svgResult.isSuccess) {
                    val svg = svgResult.getOrNull() ?: ""
                    val bmp = if (svg.isNotBlank()) {
                        LatexSvgRenderer.render(svg, targetHeightDp, Color.BLACK)
                    } else null
                    val err = if (bmp != null && bmp.width > 0 && bmp.height > 0) null else "render returned null bitmap"
                    FormulaResult(latex = latex, bitmap = bmp, renderError = err, source = "quickjs")
                } else {
                    val errMsg = svgResult.exceptionOrNull()?.message ?: "Unknown error"
                    FormulaResult(latex = latex, bitmap = null, renderError = "SVG error: $errMsg", source = "quickjs")
                }
            }
            results.clear()
            results.addAll(fallbackResults)
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(results) { result ->
                val bmp = result.bitmap
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rendered Bitmap
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(56.dp)
                                .padding(end = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            bmp?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: Text(
                                text = result.renderError ?: "ERROR",
                                fontSize = 12.sp,
                                color = Color.Red,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Source + Debug
                        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Text(
                                text = "bmp=${bmp?.width}x${bmp?.height} src=${result.source}",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
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
    val bitmap: Bitmap? = null,
    val renderError: String? = null,
    val source: String = "webview"
) {
    fun copy(
        latex: String = this.latex,
        bitmap: Bitmap? = this.bitmap,
        renderError: String? = this.renderError,
        source: String = this.source
    ): FormulaResult = FormulaResult(latex, bitmap, renderError, source)
}

