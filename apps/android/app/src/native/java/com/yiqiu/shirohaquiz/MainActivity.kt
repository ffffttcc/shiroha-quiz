package com.yiqiu.shirohaquiz

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.yiqiu.shirohaquiz.document.DocumentRecognitionManager
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.ui.app.ShirohaAppShell
import com.yiqiu.shirohaquiz.ui.theme.ShirohaColors
import com.yiqiu.shirohaquiz.ui.theme.ShirohaMotion
import com.yiqiu.shirohaquiz.ui.theme.ShirohaQuizTheme
import com.yiqiu.shirohaquiz.util.LauncherIconSwitcher
import kotlinx.coroutines.delay
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import com.yiqiu.shirohaquiz.ui.text.latex.LatexEngine
import com.yiqiu.shirohaquiz.ui.text.latex.LatexSvgRenderer
import java.io.File
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // 从别的设备回来/切回前台时拉一次进度：用户预期是「打开就是最新的」，
        // 不该等 15 分钟周期。5 秒防抖，启动那一次会和它合并。
        runCatching { com.yiqiu.shirohaquiz.sync.sync.SyncScheduler.syncSoon(applicationContext, progressOnly = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        QuizRepository.init(applicationContext)
        DocumentRecognitionManager.init(applicationContext)
        com.yiqiu.shirohaquiz.sync.webdav.WebDavAutoBackup.schedule(applicationContext)
        // 打开 App 就静默拉一次（5 秒防抖），这样别的设备刚答的题和进度能立刻接上。
        runCatching { com.yiqiu.shirohaquiz.sync.sync.SyncScheduler.syncSoon(applicationContext, progressOnly = true) }
        // 冷启动补登记周期同步：force-stop/覆盖安装会清掉 WorkManager 作业，
        // 不补的话「15 分钟自动同步」会无声消失。
        runCatching { com.yiqiu.shirohaquiz.sync.sync.SyncScheduler.schedule(applicationContext) }
        LauncherIconSwitcher.applyShirohaMode(applicationContext, QuizRepository.shirohaModeEnabled)
        applyStatusBarTheme(QuizRepository.darkThemeEnabled)
        // 启动时延迟执行 LaTeX 自检（后台线程，不阻塞 UI）
        Handler(Looper.getMainLooper()).postDelayed({
            Thread { runSelfTest() }.start()
        }, 800)
        setContent {
            val darkTheme = QuizRepository.darkThemeEnabled
            SideEffect {
                applyStatusBarTheme(darkTheme)
            }
            ShirohaQuizTheme(darkTheme = darkTheme) {
                ShirohaStartupGate {
                    ShirohaAppShell()
                }
            }
        }
    }

    private fun applyStatusBarTheme(darkTheme: Boolean) {
        window.statusBarColor = if (darkTheme) {
            Color(0xFF0E1627).toArgb()
        } else {
            Color(0xFFF7F9FF).toArgb()
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !darkTheme
    }

    /**
     * 启动时延迟执行 LaTeX 自检（后台线程，不阻塞 UI）
     * 结果写入 filesDir/selftest/：00.png..11.png + latex_selftest.txt + DONE
     */
    private fun runSelfTest() {
        Thread {
            val tag = "LatexSelfTest"
            val context = this@MainActivity
            Log.i("LatexSelfTest", "Starting LaTeX self-test on app startup (delayed)")
            try {
                // 1. 初始化引擎（复用单例）
                LatexEngine.init(context)
                var retry = 0
                while (!LatexEngine.isReady() && LatexEngine.getInitError() == null && retry < 100) {
                    Thread.sleep(50)
                    retry++
                }
                if (LatexEngine.getInitError() != null) {
                    Log.e("LatexSelfTest", "Engine init failed: ${LatexEngine.getInitError()}")
                    writeResult(context, "engine_init_failed: ${LatexEngine.getInitError()}")
                    return
                }
                val initMs = LatexEngine.getInitTimeMs()
                Log.i("LatexSelfTest", "Engine initialized in $initMs ms")

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
                            // 及时释放 bitmap 内存，仅保留路径
                            bmp.recycle()
                        }

                        val errorMsg: String? = if (bmp != null && bmp.width > 0 && bmp.height > 0) null else "render returned null bitmap"

                        // 写诊断
                        val sb = StringBuilder()
                        sb.appendLine("[#$i] tex=$latex")
                        sb.appendLine("  okSvg=${!svg.isBlank()} svgLen=${svg.length} svgHead=${svg.take(200)}")
                        sb.appendLine("  sanitizedHead=${sanitizedResult.cleaned.take(160)}")
                        sb.appendLine("  vb=${sanitizedResult.vbWidth}x${sanitizedResult.vbHeight} targetH=56 bitmap=${bmp?.width}x${bmp?.height}")
                        sb.appendLine("  error=$errorMsg")

                        // 写入诊断文件（追加）
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

                // 写完成标记（原子替换：先写 DONE.tmp 再 renameTo(DONE)）
                File(filesDir, "latex_selftest.txt").appendText("DONE ${System.currentTimeMillis()}\n")
                // 原子替换：先写 .tmp 再 rename
                File(filesDir, "latex_selftest_done.txt").writeText("DONE ${System.currentTimeMillis()}\n")
                File(filesDir, "latex_selftest_done.txt").renameTo(File(filesDir, "DONE"))

                Log.i("LatexSelfTest", "Self-test completed, PNGs saved to ${filesDir.absolutePath}/selftest/")
            } catch (e: Exception) {
                Log.e("LatexSelfTest", "Self-test failed", e)
                File(filesDir, "latex_selftest.txt").writeText("self_test_exception: ${e.message}")
            }
        }.start()
    }

    private fun writeResult(context: Context, msg: String) {
        try {
            File(context.filesDir, "latex_selftest.txt").writeText(msg)
        } catch (e: Exception) {
            Log.e("LatexSelfTest", "Failed to write result", e)
        }
    }

    private fun writeResult(msg: String) {
        writeResult(this, msg)
    }