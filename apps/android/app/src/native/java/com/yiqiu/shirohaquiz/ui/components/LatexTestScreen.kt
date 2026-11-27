package com.yiqiu.shirohaquiz.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.LaunchedEffect
import com.yiqiu.shirohaquiz.ui.text.latex.LatexWebViewRenderer

private val FORMULAS = listOf(
    "\\mathbf{B}_{12}",
    "\\mathrm{ED}_{50}",
    "\\mathrm{LD}_{50}/\\mathrm{ED}_{50}",
    "\\frac{\\mathrm{d}C}{\\mathrm{d}t}=-kC^{n}",
    "K^{+}",
    "pK_{a}",
    "\\sqrt{x^2+y^2}",
    "\\ce{H2SO4}",
    "\\alpha\\beta\\gamma",
    "\\int_0^1 x dx",
    "x_{i}^{2}",
    "\\sum_{n=1}^{\\infty}"
)

@Composable
fun LatexTestScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val renderer = remember { activity?.let { LatexWebViewRenderer(it) } }

    val bitmaps = remember { mutableStateListOf<Bitmap?>().also { l -> repeat(FORMULAS.size) { l.add(null) } } }
    val errors = remember { mutableStateListOf<String?>().also { l -> repeat(FORMULAS.size) { l.add(null) } } }

    var ready by remember { mutableStateOf(false) }
    val successCount = bitmaps.count { it != null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(
            text = "LaTeX 渲染引擎测试 (WebView)",
            fontSize = 16.sp
        )
        Text(
            text = "ready=$ready  success=$successCount / ${FORMULAS.size}",
            fontSize = 12.sp,
            color = Color(0xFF888888)
        )

        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).also { host -> renderer?.attach(host) }
            },
            modifier = Modifier.fillMaxWidth().height(1.dp)
        )

        FORMULAS.forEachIndexed { idx, latex ->
            val bmp = bitmaps[idx]
            val err = errors[idx]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 72.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = "bitmap=null",
                            fontSize = 12.sp,
                            color = Color(0xFFFF0000)
                        )
                    }
                    Text(
                        text = "bmp=${bmp?.width}x${bmp?.height} src=webview",
                        fontSize = 10.sp,
                        color = Color(0xFF888888)
                    )
                    Text(
                        text = latex,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    err?.let {
                        Text(
                            text = "err=$it",
                            fontSize = 10.sp,
                            color = Color(0xFFFF0000)
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        renderer?.warmUp { ok ->
            ready = ok
            Log.d("LatexRender", "warmUp=$ok")
        }
        onDispose { renderer?.release() }
    }

    LaunchedEffect(ready) {
        if (ready) {
            FORMULAS.forEachIndexed { idx, f ->
                renderer?.render(f, 56, AndroidColor.BLACK) { bmp, error ->
                    bitmaps[idx] = bmp
                    errors[idx] = error
                }
            }
        }
    }
}