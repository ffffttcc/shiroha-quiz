package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Picture
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.ByteArrayInputStream
import kotlin.Result
import kotlin.runCatching

/**
 * Converts MathJax SVG output to a Compose Painter using AndroidSVG.
 * Handles MathJax-specific quirks: inline styles, ex units, currentColor, etc.
 */
object LatexSvgPainter {
    private const val TAG = "LatexSvgPainter"

    /**
     * Parse SVG string to a Compose Painter with fallback and error reporting.
     * Returns PainterResult which contains either a Painter or an error message.
     */
    fun toPainter(svgString: String): PainterResult {
        // Attempt 1: standard preprocessing
        return runCatching { parseToPicture(preprocessSvg(svgString)) }
            .fold(
                onFailure = { e1 ->
                    android.util.Log.w(TAG, "SVG parse attempt 1 failed: ${e1.message}")
                    // Attempt 2: aggressive sanitization
                    runCatching { parseToPicture(sanitizeAggressive(svgString)) }
                        .fold(
                            onFailure = { e2 ->
                                android.util.Log.e(TAG, "SVG parse failed after aggressive sanitize: ${e2.message}; svg_head=${svgString.take(200)}")
                                PainterResult.Error("androidsvg: ${e2.message}")
                            },
                            onSuccess = { picture ->
                                android.util.Log.i(TAG, "SVG parse succeeded on attempt 2 (aggressive sanitize)")
                                PainterResult.Success(SvgPainter(picture))
                            }
                        )
                },
                onSuccess = { picture ->
                    PainterResult.Success(SvgPainter(picture))
                }
            )
    }

    /** Parse SVG string to Android Picture using renderToPicture with explicit dimensions from viewBox. */
    private fun parseToPicture(svgString: String): Picture {
        val sanitized = replaceCurrentColor(svgString)
        android.util.Log.d("LatexSvgPainter", "parsing SVG: ${sanitized.take(200)}")
        
        val svg = SVG.getFromString(sanitized)
        
        // Get dimensions from viewBox - this is the most reliable source
        val vb = svg.documentViewBox
        val wPx = if (vb != null && vb.width() > 0) vb.width() else 1608f
        val hPx = if (vb != null && vb.height() > 0) vb.height() else 836f
        val bitmapW = wPx.toInt().coerceAtLeast(1)
        val bitmapH = hPx.toInt().coerceAtLeast(1)
        
        android.util.Log.d("LatexSvgPainter", "parseToPicture: vb=$vb wPx=$wPx hPx=$hPx bitmap=${bitmapW}x${bitmapH}")
        
        // Use renderToPicture with explicit pixel dimensions - most reliable approach
        val picture = svg.renderToPicture(bitmapW, bitmapH)
        
        android.util.Log.d("LatexSvgPainter", "renderToPicture result: picture=${picture.width}x${picture.height}")
        return picture
    }

    /**
     * Preprocess MathJax SVG output for AndroidSVG compatibility:
     * - Replace currentColor with #000000 (androidsvg doesn't support currentColor well)
     * - Remove vertical-align with ex units from style
     * - Remove width/height with ex units (rely on viewBox)
     * - Ensure viewBox is present
     */
    private fun preprocessSvg(svg: String): String {
        var result = svg.trim()

        // 1. Replace currentColor with black (#000000) - androidsvg doesn't handle currentColor well
        result = result.replace("currentColor", "#000000")

        // 2. Clean style attribute: remove vertical-align with ex units, keep other declarations
        result = result.replace(Regex("""style\s*=\s*"([^"]*)"""")) { match ->
            val styleContent = match.groupValues[1]
            val kept = styleContent
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("vertical-align", true) }
                .joinToString("; ")
            if (kept.isEmpty()) "" else """style="$kept""""
        }

        // 3. Remove width/height attributes that use 'ex' units (AndroidSVG doesn't support ex)
        result = result.replace(Regex("""\swidth="[^"]*ex[^"]*""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\sheight="[^"]*ex[^"]*""", RegexOption.IGNORE_CASE), "")

        // 4. Remove any remaining width/height attributes (we'll use viewBox for dimensions)
        // but keep viewBox if present
        result = result.replace(Regex("""\swidth="[^"]*""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\sheight="[^"]*""", RegexOption.IGNORE_CASE), "")

        // 5. Ensure viewBox exists
        if (!result.contains("viewBox", true)) {
            // Try to extract width/height for viewBox
            val widthMatch = Regex("""width="([^"]+)"""").find(result)
            val heightMatch = Regex("""height="([^"]+)"""").find(result)
            val w = widthMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 1608.0
            val h = heightMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 836.0
            result = result.replaceFirst("<svg", "<svg viewBox=\"0 0 $w $h\"")
        }

        return result
    }

    /**
     * Aggressive sanitization: remove all style, role, focusable, aria-* attributes.
     * Used as fallback when standard preprocessing fails.
     */
    private fun sanitizeAggressive(svg: String): String {
        var result = svg.trim()
        // Replace currentColor first
        result = result.replace("currentColor", "#000000")
        // Remove style, role, focusable, aria-* attributes entirely
        result = result.replace(Regex("""\s(style|role|focusable|aria-[a-z-]+)\s*=\s*"[^"]*"""", RegexOption.IGNORE_CASE), "")
        // Ensure viewBox and width/height
        if (!result.contains("viewBox", true)) {
            val widthMatch = Regex("""width="([^"]+)"""").find(result)
            val heightMatch = Regex("""height="([^"]+)"""").find(result)
            val w = widthMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 1608.0
            val h = heightMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 836.0
            result = result.replaceFirst("<svg", "<svg viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\"")
        }
        return result
    }

    /** Result of SVG to Painter conversion. */
    sealed class PainterResult {
        data class Success(val painter: Painter) : PainterResult()
        data class Error(val message: String) : PainterResult()
    }

    /** Compose Painter that draws an Android Picture. */
    private class SvgPainter(private val picture: Picture) : Painter() {
        override fun DrawScope.onDraw() {
            val width = size.width
            val height = size.height
            val picWidth = picture.width.toFloat()
            val picHeight = picture.height.toFloat()

            // Scale to fit bounds while preserving aspect ratio
            val scale = minOf(width / picWidth, height / picHeight)
            val scaledWidth = picWidth * scale
            val scaledHeight = picHeight * scale
            val translateX = (width - scaledWidth) / 2
            val translateY = (height - scaledHeight) / 2

            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(translateX, translateY)
                canvas.scale(scale, scale)
                picture.draw(canvas.nativeCanvas)
                canvas.restore()
            }
        }

        override val intrinsicSize: Size
            get() {
                val w = if (picture.width > 0) picture.width.toFloat() else 120f
                val h = if (picture.height > 0) picture.height.toFloat() else 60f
                return Size(w, h)
            }
    }
}