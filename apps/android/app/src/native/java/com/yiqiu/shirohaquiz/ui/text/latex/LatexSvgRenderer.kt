package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Bitmap
import android.graphics.Picture
import android.util.Log
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException

/**
 * Renders MathJax SVG string to Android Bitmap using AndroidSVG.
 * Handles MathJax-specific quirks: vertical-align with ex units, currentColor, etc.
 * Provides LRU cache keyed by formula hash + target height.
 */
object LatexSvgRenderer {
    private const val TAG = "LatexRender"
    private const val MAX_CACHE_ENTRIES = 100

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            if (evicted) {
                oldValue.recycle()
                Log.d(TAG, "Cache evicted: $key")
            }
        }
    }

    /**
     * Render SVG string to Bitmap.
     * @param svgStr Raw SVG string from MathJax
     * @param targetHeightPx Target height in pixels (width calculated from viewBox aspect ratio)
     * @param color Foreground color as ARGB int (default black #FF000000)
     * @return Bitmap (ARGB_8888) or null on failure
     */
    fun render(svgStr: String, targetHeightPx: Int, color: Int = 0xFF000000): Bitmap? {
        val key = "${svgStr.hashCode()}_$targetHeightPx"
        cache.get(key)?.let { return it }

        val cleaned = cleanSvg(svgStr, color)
        Log.d(TAG, "cleaned SVG head: ${cleaned.take(200)}")

        val svg = try {
            SVG.getFromString(cleaned)
        } catch (e: SVGParseException) {
            Log.e(TAG, "SVG parse failed: ${e.message}", e)
            return null
        }

        val vb = svg.documentViewBox
        val viewW = if (vb != null && vb.width() > 0) vb.width() else 1608f
        val viewH = if (vb != null && vb.height() > 0) vb.height() else 836f

        val targetW = (targetHeightPx * viewW / viewH).toInt().coerceAtLeast(1)
        val targetH = targetHeightPx.coerceAtLeast(1)

        Log.d(TAG, "viewBox=${viewW}x${viewH} targetH=$targetHeightPx bitmap=${targetW}x$targetH")

        val bitmap: Bitmap
        try {
            // Attempt 1: renderToPicture with explicit dimensions (most reliable)
            val picture = svg.renderToPicture(targetW, targetH)
            bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            picture.draw(canvas)
        } catch (e1: Exception) {
            Log.w(TAG, "renderToPicture failed, trying setDocumentWidth/Height: ${e1.message}")
            try {
                // Attempt 2: setDocumentWidth/Height + beginRecording
                svg.setDocumentWidth(targetW.toFloat())
                svg.setDocumentHeight(targetH.toFloat())
                val picture = android.graphics.Picture()
                val canvas = picture.beginRecording(targetW, targetH)
                svg.renderToCanvas(canvas)
                picture.endRecording()
                bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val drawCanvas = android.graphics.Canvas(bitmap)
                picture.draw(drawCanvas)
            } catch (e2: Exception) {
                Log.w(TAG, "setDocumentWidth/Height failed, trying manual scale: ${e2.message}")
                try {
                    // Attempt 3: manual scale using viewBox
                    val picture = svg.renderToPicture(viewW.toInt().coerceAtLeast(1), viewH.toInt().coerceAtLeast(1))
                    bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    val scaleX = targetW.toFloat() / viewW
                    val scaleY = targetH.toFloat() / viewH
                    canvas.scale(scaleX, scaleY)
                    picture.draw(canvas)
                } catch (e3: Exception) {
                    Log.e(TAG, "All render attempts failed: ${e3.message}", e3)
                    return null
                }
            }
        }

        cache.put(key, bitmap)
        Log.d(TAG, "render ok: ${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    /**
     * Clean MathJax SVG for AndroidSVG compatibility.
     * Steps (order fixed, each step idempotent):
     * a) Remove vertical-align with ex units from root svg style
     * b) Replace currentColor with provided color hex
     * c) Extract viewBox, set width/height to pure px numbers (strip ex/em/pt)
     * d) Remove role/focusable/aria-* attributes (optional, avoid interference)
     */
    private fun cleanSvg(svg: String, color: Int): String {
        var result = svg.trim()

        // a) Remove vertical-align with ex units from style attribute
        result = result.replace(Regex("""style\s*=\s*"([^"]*)"""")) { match ->
            val styleContent = match.groupValues[1]
            val kept = styleContent
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("vertical-align", true) }
                .joinToString("; ")
            if (kept.isEmpty()) "" else """style="$kept""""
        }

        // b) Replace currentColor with provided color
        val colorHex = String.format("#%06X", color and 0xFFFFFF)
        result = result.replace("currentColor", colorHex)

        // c) Ensure width/height are pure pixel numbers (strip ex/em/pt), keep viewBox
        // First, extract viewBox if present for later use
        val viewBoxMatch = Regex("""viewBox\s*=\s*"([^"]+)"""").find(result)
        val viewBoxStr = viewBoxMatch?.groupValues?.get(1)

        // Remove width/height with ex/em/pt units
        result = result.replace(Regex("""\swidth="[^"]*?(ex|em|pt)[^"]*"""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\sheight="[^"]*?(ex|em|pt)[^"]*"""", RegexOption.IGNORE_CASE), "")

        // Remove any remaining width/height attributes (we'll add pure px ones if viewBox exists)
        result = result.replace(Regex("""\swidth="[^"]*"""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\sheight="[^"]*"""", RegexOption.IGNORE_CASE), "")

        // If viewBox exists, add pure px width/height based on targetHeightPx logic
        // But we don't know targetHeightPx here; let AndroidSVG use viewBox directly
        // So we just ensure viewBox is present and don't add width/height

        // d) Remove role/focusable/aria-* attributes
        result = result.replace(Regex("""\s(role|focusable|aria-[a-z-]+)\s*=\s*"[^"]*"""", RegexOption.IGNORE_CASE), "")

        // Ensure viewBox exists (fallback to default)
        if (!result.contains("viewBox", true)) {
            val w = 1608.0
            val h = 836.0
            result = result.replaceFirst("<svg", "<svg viewBox=\"0 0 $w $h\"")
        }

        return result
    }

    /** Clear the LRU cache and recycle bitmaps. */
    fun clearCache() {
        cache.snapshot().values.forEach { it.recycle() }
        cache.evictAll()
        Log.d(TAG, "Cache cleared")
    }
}