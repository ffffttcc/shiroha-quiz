package com.yiqiu.shirohaquiz

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.io.readTextOrNull
import androidx.lifecycle.lifecycleScope
import com.yiqiu.shirohaquiz.ui.text.latex.LatexEngine
import com.yiqiu.shirohaquiz.ui.text.latex.LatexSvgRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ShirohaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 启动时自动执行一次 LaTeX 自检（DEBUG 阶段强制执行）
        lifecycleScope.launch(Dispatchers.Default) {
            runSelfTest()
        }
    }

    private fun runSelfTest() {
        val tag = "LatexSelfTest"
        Log.i(tag, "Starting LaTeX self-test on app startup")
        try {
            // 1. 初始化引擎
            val context = applicationContext
            LatexEngine.init(context)
            var retry = 0
            while (!LatexEngine.isReady() && LatexEngine.getInitError() == null && retry < 100) {
                Thread.sleep(50)
                retry++
            }
            if (LatexEngine.getInitError() != null) {
                Log.e(tag, "Engine init failed: ${LatexEngine.getInitError()}")
                writeResult("engine_init_failed: ${LatexEngine.getInitError()}")
                return
            }
            val initMs = LatexEngine.getInitTimeMs()
            Log.i(tag, "Engine initialized in $initMs ms")

            // 2. 渲染 12 条公式并存 PNG
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

            val dir = File(filesDir, "selftest").apply { mkdirs() }
            val sb = StringBuilder()
            sb.appendLine("engine=true initMs=${LatexEngine.getInitTimeMs()}")
            sb.appendLine("targetHeightPx=56")

            formulas.forEachIndexed { i, latex ->
                val svgResult = LatexEngine.renderSvgString(latex)
                if (svgResult.isSuccess) {
                    val svg = svgResult.getOrNull() ?: ""
                    val svgOkFlag = !svg.isBlank()
                    val svgLenVal = svg.length
                    val svgHeadVal = svg.take(200)
                    
                    val sanitizedResult = com.yiqiu.shirohaquiz.ui.text.latex.LatexSvgRenderer.sanitize(
                        svg, 
                        android.graphics.Color.BLACK
                    )
                    val sanitizedHeadVal = sanitizedResult.cleaned.take(160)
                    val vbWVal = sanitizedResult.vbWidth
                    val vbHVal = sanitizedResult.vbHeight
                    val targetHVal = 56
                    
                    val bmp = if (!svg.isBlank()) {
                        LatexSvgRenderer.render(svg, 56, android.graphics.Color.BLACK)
                    } else null
                    val bmpWVal = bmp?.width ?: 0
                    val bmpHVal = bmp?.height ?: 0
                    
                    val errorMsg: String? = if (bmpWVal > 0 && bmpHVal > 0) null else "render returned null bitmap"
                    
                    // 存 PNG
                    if (bmp != null) {
                        val pngFile = File(filesDir, "selftest/%02d.png".format(i))
                        pngFile.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    
                    val errorMsg: String? = if (bmpWVal > 0 && bmpHVal > 0) null else "render returned null bitmap"
                    
                    // 写诊断
                    val sb = StringBuilder()
                    sb.appendLine("[#$i] tex=$latex")
                    sb.appendLine("  okSvg=$svgOkFlag svgLen=${svg.length} svgHead=${svg.take(200)}")
                    sb.appendLine("  sanitizedHead=${sanitizedResult.cleaned.take(160)}")
                    sb.appendLine("  vb=${sanitizedResult.vbWidth}x${sanitizedResult.vbHeight} targetH=56 bitmap=${bmp?.width}x${bmp?.height}")
                    sb.appendLine("  error=$errorMsg")
                    
                    // 写入诊断文件
                    File(filesDir, "latex_selftest.txt").writeText(
                        File(filesDir, "latex_selftest.txt").readTextOrNull()?.plus(sb.toString()) ?: sb.toString()
                    )
                } else {
                    val errMsg = svgResult.exceptionOrNull()?.message ?: "Unknown error"
                    File(filesDir, "latex_selftest.txt").writeText(
                        File(filesDir, "latex_selftest.txt").readTextOrNull()?.plus(
                            "[#$i] tex=$latex\n  okSvg=false svgLen=0 svgHead=\n  sanitizedHead=\n  vb=0x0 targetH=56 bitmap=0x0\n  error=SVG error: $errMsg\n"
                        ) ?: "[#$i] tex=$latex\n  okSvg=false svgLen=0 svgHead=\n  sanitizedHead=\n  vb=0x0 targetH=56 bitmap=0x0\n  error=SVG error: $errMsg\n"
                    )
                }
            }
            
            // 写完成标记
            File(filesDir, "latex_selftest.txt").appendText("DONE ${System.currentTimeMillis()}\n")
            Log.i("LatexSelfTest", "Self-test completed, PNGs saved to ${filesDir.absolutePath}/selftest/")
        } catch (e: Exception) {
            Log.e("LatexSelfTest", "Self-test failed", e)
            writeResult("self_test_exception: ${e.message}")
        }
    }

    private fun writeResult(msg: String) {
        try {
            File(filesDir, "latex_selftest.txt").writeText(msg)
        } catch (e: Exception) {
            Log.e("LatexSelfTest", "Failed to write result", e)
        }
    }
}