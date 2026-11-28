package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import kotlin.text.Regex
import ru.noties.jlatexmath.JLatexMathDrawable

object JLatexMathRenderer {
    private const val TAG = "JLatexMath"
    private val cache = LruCache<String, Bitmap>(60)

    // \ce{} normalization: convert mhchem syntax to \mathrm with subscripts
    private val CE = Regex("""\\ce\{([^}]*)\}""")
    private val CE_SUB = Regex("""([A-Za-z\)\]])(\d+)""")

    private fun normalize(latex: String): String =
        CE.replace(latex) { m ->
            val body = m.groupValues[1]
            val subbed = CE_SUB.replace(body) { mm -> "${mm.groupValues[1]}_{${mm.groupValues[2]}}" }
            "\\mathrm{$subbed}"
        }

    fun render(latex: String, heightPx: Int, colorArgb: Int): Bitmap? {
        val tex = normalize(latex)
        val key = "$tex|$heightPx|$colorArgb"
        cache.get(key)?.let { return it }
        return runCatching {
            val d = JLatexMathDrawable.builder(tex)
                .textSize(heightPx.toFloat() * 0.75f)
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