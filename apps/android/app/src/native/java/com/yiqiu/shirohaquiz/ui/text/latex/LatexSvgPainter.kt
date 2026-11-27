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
 * Handles MathJax-specific quirks: inline styles, ex units, etc.
 */
object LatexSvgPainter {
    private const val TAG = "LatexSvgPainter"

    /**
     * Parse SVG string to a Compose Painter with fallback and error reporting.
     * Returns PainterResult which contains either a Painter or an error message.
     */
    fun toPainter(svgString: String): PainterResult {
        // Attempt 1: standard preprocessing
        runCatching { parseToPicture(preprocessSvg(svgString)) }
            .onSuccess { picture ->
                return PainterResult.Success(SvgPainter(picture))
            }
            .onFailure { e1 ->
                android.util.Log.w(TAG, "SVG parse attempt 1 failed: ${e1.message}")
            }

        // Attempt 2: aggressive sanitization
        runCatching { parseToPicture(sanitizeAggressive(svgString)) }
            .onSuccess { picture ->
                android.util.Log.i(TAG, "SVG parse succeeded on attempt 2 (aggressive sanitize)")
                return PainterResult.Success(SvgPainter(picture))
            }
            .onFailure { e2 ->
                android.util.Log.e(TAG, "SVG parse failed after aggressive sanitize: ${e2.message}; svg_head=${svgString.take(200)}")
                return PainterResult.Error("androidsvg: ${e2.message}")
            }
    }

    /** Parse SVG string to Android Picture. */
    private fun parseToPicture(svgString: String): Picture {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(svgString.toByteArray()))
        val picture = Picture()
        val width = svg.getDocumentWidth().toInt()
        val height = svg.getDocumentHeight().toInt()
        val canvas = picture.beginRecording(width, height)
        svg.renderToCanvas(canvas)
        picture.endRecording()
        return picture
    }

    /**
     * Preprocess MathJax SVG output for AndroidSVG compatibility:
     * - Convert inline style="fill:currentColor" to fill attribute
     * - Remove width/height with ex units (rely on viewBox)
     * - Ensure viewBox is present
     * - Clean style attribute: remove vertical-align with ex units
     */
    private fun preprocessSvg(svg: String): String {
        var result = svg.trim()

        // 1. Clean style attribute: remove vertical-align with ex units, keep other declarations
        result = result.replace(Regex("""style\s*=\s*"([^"]*)"""")) { match ->
            val styleContent = match.groupValues[1]
            val kept = styleContent
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("vertical-align", true) }
                .joinToString("; ")
            if (kept.isEmpty()) "" else """style="$kept""""
        }

        // 2. Remove width/height attributes that use 'ex' units (AndroidSVG doesn't support ex)
        // Keep viewBox if present
        result = result.replace(Regex("""\swidth="[^"]*ex[^"]*""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\sheight="[^"]*ex[^"]*""", RegexOption.IGNORE_CASE), "")

        // 3. Ensure viewBox exists (MathJax usually provides it)
        if (!result.contains("viewBox", true)) {
            // Try to extract width/height for viewBox
            val widthMatch = Regex("""width="([^"]+)"""").find(result)
            val heightMatch = Regex("""height="([^"]+)"""").find(result)
            val w = widthMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0
            val h = heightMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0
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
        // Remove style, role, focusable, aria-* attributes entirely
        result = result.replace(Regex("""\s(style|role|focusable|aria-[a-z-]+)\s*=\s*"[^"]*"""", RegexOption.IGNORE_CASE), "")
        // Ensure viewBox
        if (!result.contains("viewBox", true)) {
            val widthMatch = Regex("""width="([^"]+)"""").find(result)
            val heightMatch = Regex("""height="([^"]+)"""").find(result)
            val w = widthMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0
            val h = heightMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0
            result = result.replaceFirst("<svg", "<svg viewBox=\"0 0 $w $h\"")
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
            get() = Size(picture.width.toFloat(), picture.height.toFloat())
    }
}