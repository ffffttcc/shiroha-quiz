package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Picture
import androidx.compose.ui.graphics.Painter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toPx
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.ByteArrayInputStream

/**
 * Converts MathJax SVG output to a Compose Painter using AndroidSVG.
 * Handles MathJax-specific quirks: inline styles, ex units, etc.
 */
object LatexSvgPainter {
    private const val TAG = "LatexSvgPainter"

    /**
     * Parse SVG string to a Compose Painter.
     * Returns null on parse failure (caller should fall back).
     */
    fun toPainter(svgString: String): Painter? {
        val picture = parseToPicture(svgString) ?: return null
        return SvgPainter(picture)
    }

    /** Parse SVG string to Android Picture. */
    private fun parseToPicture(svgString: String): Picture? {
        val processed = preprocessSvg(svgString)
        return try {
            val svg = SVG.getFromInputStream(ByteArrayInputStream(processed.toByteArray()))
            val picture = Picture()
            val width = svg.getDocumentWidth().toInt()
            val height = svg.getDocumentHeight().toInt()
            val canvas = picture.beginRecording(width, height)
            svg.renderToCanvas(canvas)
            picture.endRecording()
            picture
        } catch (e: SVGParseException) {
            android.util.Log.w(TAG, "SVG parse failed: ${e.message}")
            null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "SVG render failed: ${e.message}")
            null
        }
    }

    /**
     * Preprocess MathJax SVG output for AndroidSVG compatibility:
     * - Convert inline style="fill:currentColor" to fill attribute
     * - Remove width/height with ex units (rely on viewBox)
     * - Ensure viewBox is present
     */
    private fun preprocessSvg(svg: String): String {
        var result = svg.trim()

        // Replace style="fill:currentColor" (and similar) with fill attribute
        // MathJax outputs: style="fill:currentColor;stroke:currentColor;stroke-width:0"
        result = result.replace(Regex("""style="[^"]*fill:\s*currentColor[^"]*""")) { match ->
            val style = match.value
            var replacement = ""
            if (style.contains("fill:")) replacement += " fill=\"currentColor\""
            if (style.contains("stroke:")) replacement += " stroke=\"currentColor\""
            if (style.contains("stroke-width:")) {
                val width = Regex("""stroke-width:\s*([^;"]+)""").find(style)?.groupValues?.get(1)
                width?.let { replacement += " stroke-width=\"$it\"" }
            }
            replacement
        }

        // Remove width/height attributes that use 'ex' units (AndroidSVG doesn't support ex)
        // Keep viewBox if present
        result = result.replace(Regex("""\swidth="[^"]*ex[^"]*""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\sheight="[^"]*ex[^"]*""", RegexOption.IGNORE_CASE), "")

        // If width/height are pure numbers without units, keep them
        // If they have px/pt/cm/mm/in/pc, AndroidSVG handles them

        // Ensure viewBox exists (MathJax usually provides it)
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
                picture.draw(canvas)
                canvas.restore()
            }
        }

        override val intrinsicWidth: Dp = (picture.width * 1.0f).dp
        override val intrinsicHeight: Dp = (picture.height * 1.0f).dp
    }
}