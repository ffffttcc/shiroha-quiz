package com.yiqiu.shirohaquiz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.yiqiu.shirohaquiz.state.QuizRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义背景层：挂在 [com.yiqiu.shirohaquiz.ui.app.ShirohaAppShell] 根容器最底层。
 *
 * 性能约束（P1 的教训）：
 * - 只依赖「开关 + version」两个状态，滚动/翻页不会让它重组。
 * - 图片解码走 [produceState] + Dispatchers.IO，只在换图（version 变化）或屏幕宽度变化时做一次；
 *   draw / composition 阶段绝不 decode。
 * - 缺文件、解码失败一律静默返回空 → 调用方自然退回默认背景，不会崩。
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    if (!QuizRepository.customBackgroundEnabled) return
    val version = QuizRepository.customBackgroundVersion
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val image by produceState<ImageBitmap?>(null, version, screenWidthDp) {
        value = withContext(Dispatchers.IO) {
            decodeBackground(context.applicationContext, screenWidthDp)
        }
    }
    val bitmap = image ?: return
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // 固定蒙层保证文字可读；深色主题压得更重。
        val scrimAlpha = if (QuizRepository.darkThemeEnabled) 0.55f else 0.32f
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
    }
}

/** 按屏幕宽度算 inSampleSize，只解码到够用的大小；RGB_565 省一半内存。 */
private fun decodeBackground(context: Context, screenWidthDp: Int): ImageBitmap? = runCatching {
    val file = QuizRepository.customBackgroundImageFile(context) ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val density = context.resources.displayMetrics.density
    val targetWidth = (screenWidthDp.coerceAtLeast(1) * density).toInt().coerceAtLeast(1)
    var inSampleSize = 1
    while (inSampleSize * 2 < 4096 && bounds.outWidth / (inSampleSize * 2) >= targetWidth) {
        inSampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()
