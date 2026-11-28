package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache
import android.util.Log
import ru.noties.jlatexmath.JLatexMathDrawable

object JLatexMathRenderer {
    private const val TAG = "JLatexMath"
    private val cache = LruCache<String, Bitmap>(60)

    fun render(latex: String, heightPx: Int, colorArgb: Int): Bitmap? {
        val key = "$latex|$heightPx|$colorArgb"
        cache.get(key)?.let { return it }
        return runCatching {
            val d = JLatexMathDrawable.builder(latex)
                .textSize(heightPx.toFloat() * 0.75f)   // 字号略小于目标高度，留边距
                .color(colorArgb)
                .build()
            val w = d.intrinsicWidth.coerceAtLeast(1)
            val h = d.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
            cache.put(key, bmp)
            bmp
        }.onFailure { Log.e(TAG, "render fail: ${it.javaClass.simpleName}: ${it.message} latex=$latex") }.getOrNull()
    }
}