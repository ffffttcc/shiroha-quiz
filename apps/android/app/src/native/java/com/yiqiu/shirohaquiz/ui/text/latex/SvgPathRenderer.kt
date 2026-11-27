package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.roundToInt

/**
 * Minimal SVG path renderer for MathJax SVG output.
 * Supports: viewBox, <g transform="...">, <path d="..."> with M/L/H/V/C/Q/Z commands.
 * No external dependencies; pure Android graphics.
 * 
 * Matrix multiplication order: parentMatrix.postConcat(childMatrix) == childMatrix * parentMatrix
 * This matches Python script's mat_multiply(parent, child) which applies child after parent.
 */
object SvgPathRenderer {
    private const val TAG = "SvgPathRenderer"
    private const val CACHE_SIZE = 64

    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

    /**
     * Render MathJax SVG string to Bitmap.
     * @param svgStr SVG content from LatexEngine.renderSvgString()
     * @param targetHeightPx Target height in pixels (width scaled proportionally)
     * @param colorArgb ARGB color for the paths (e.g., Color.BLACK)
     * @return Bitmap or null on failure
     */
    fun renderToBitmap(
        svgStr: String,
        targetHeightPx: Int,
        colorArgb: Int = Color.BLACK
    ): Bitmap? {
        val cacheKey = "$svgStr|$targetHeightPx|$colorArgb"
        cache.get(cacheKey)?.let { return it }

        return try {
            val parser = SvgParser(svgStr)
            parser.parse()

            // 1) viewBox
            val vb = parser.viewBox
            if (vb.width() <= 0 || vb.height() <= 0) {
                Log.e(TAG, "Invalid viewBox: $vb")
                return null
            }

            // 2) scale
            val scale = targetHeightPx.toFloat() / vb.height()
            val bitmapWidth = (vb.width() * scale).roundToInt().coerceAtLeast(1)
            val bitmapHeight = targetHeightPx.coerceAtLeast(1)

            // 3) Bitmap
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            // 4) Paint
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorArgb
                style = Paint.Style.FILL
            }

            // 5) Base matrix: viewBox -> canvas
            // viewBox: (minX, minY) top-left, Y up; Canvas: (0,0) top-left, Y down
            // Transform: translate(-minX, -minY) -> scale(scale, scale) -> flip Y
            val baseMatrix = Matrix().apply {
                postTranslate(-vb.left, -vb.bottom)  // Move viewBox origin to (0,0)
                postScale(scale, scale)               // Scale to target size
                postScale(1f, -1f)                    // Flip Y (math coords -> canvas coords)
                postTranslate(0f, bitmapHeight.toFloat()) // Flip Y back for canvas
            }

            var drawn = false
            for (pathData in parser.paths) {
                val path = buildPath(pathData.commands, pathData.transform, baseMatrix)
                if (!path.isEmpty) {
                    canvas.drawPath(path, paint)
                    drawn = true
                }
            }

            if (!drawn) {
                Log.w(TAG, "No paths drawn")
                bitmap.recycle()
                return null
            }

            cache.put(cacheKey, bitmap)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Render failed", e)
            null
        }
    }

    /** Build Android Path from path commands with combined transform applied */
    private fun buildPath(
        commands: List<PathCmd>,
        localTransform: Matrix,
        baseMatrix: Matrix
    ): Path {
        val path = Path()
        var x = 0f
        var y = 0f

        // Combine: first apply localTransform (from <g> ancestors), then baseMatrix (viewBox->canvas)
        // M = baseMatrix * localTransform  (apply local first, then base)
        val combinedTransform = Matrix()
        combinedTransform.set(baseMatrix)
        combinedTransform.postConcat(localTransform)

        for (cmd in commands) {
            val isRelative = cmd.letter.isLowerCase()
            val letter = cmd.letter.uppercase()
            val args = cmd.args
            var i = 0

            when (letter) {
                'M' -> {
                    while (i + 1 < args.size) {
                        val nx = args[i].toFloat()
                        val ny = args[i + 1].toFloat()
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny
                        x = tx; y = ty
                        val pt = floatArrayOf(nx, ny)
                        combinedTransform.mapPoints(pt)
                        if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
                        i += 2
                    }
                }
                'L' -> {
                    while (i + 1 < args.size) {
                        val nx = args[i].toFloat()
                        val ny = args[i + 1].toFloat()
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny
                        x = tx; y = ty
                        val pt = floatArrayOf(nx, ny)
                        combinedTransform.mapPoints(pt)
                        path.lineTo(pt[0], pt[1])
                        i += 2
                    }
                }
                'H' -> {
                    for (arg in args) {
                        val nx = arg.toFloat()
                        val tx = if (isRelative) x + nx else nx
                        x = tx
                        val pt = floatArrayOf(nx, y)
                        combinedTransform.mapPoints(pt)
                        path.lineTo(pt[0], pt[1])
                    }
                }
                'V' -> {
                    for (arg in args) {
                        val ny = arg.toFloat()
                        val ty = if (isRelative) y + ny else ny
                        y = ty
                        val pt = floatArrayOf(x, ny)
                        combinedTransform.mapPoints(pt)
                        path.lineTo(pt[0], pt[1])
                    }
                }
                'C' -> {
                    while (i + 5 < args.size) {
                        val x1 = args[i].toFloat()
                        val y1 = args[i + 1].toFloat()
                        val x2 = args[i + 2].toFloat()
                        val y2 = args[i + 3].toFloat()
                        val nx = args[i + 4].toFloat()
                        val ny = args[i + 5].toFloat()

                        val tx1 = if (isRelative) x + x1 else x1
                        val ty1 = if (isRelative) y + y1 else y1
                        val tx2 = if (isRelative) x + x2 else x2
                        val ty2 = if (isRelative) y + y2 else y2
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny

                        val pts = floatArrayOf(tx1, ty1, tx2, ty2, tx, ty)
                        combinedTransform.mapPoints(pts)
                        path.cubicTo(pts[0], pts[1], pts[2], pts[3], pts[4], pts[5])

                        x = tx; y = ty
                        i += 6
                    }
                }
                'Q' -> {
                    while (i + 3 < args.size) {
                        val x1 = args[i].toFloat()
                        val y1 = args[i + 1].toFloat()
                        val nx = args[i + 2].toFloat()
                        val ny = args[i + 3].toFloat()

                        val tx1 = if (isRelative) x + x1 else x1
                        val ty1 = if (isRelative) y + y1 else y1
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny

                        val pts = floatArrayOf(tx1, ty1, tx, ty)
                        combinedTransform.mapPoints(pts)
                        path.quadTo(pts[0], pts[1], pts[2], pts[3])

                        x = tx; y = ty
                        i += 4
                    }
                }
                'Z' -> {
                    path.close()
                }
                else -> {
                    Log.w(TAG, "Unsupported path command: $letter")
                }
            }
            return path
        }

    /** Parse SVG transform string to Android Matrix */
    fun parseTransform(transformStr: String?): Matrix {
        val matrix = Matrix()
        if (transformStr == null || transformStr.isBlank()) return matrix

        val pattern = Regex("""(\w+)\s*\(([^)]+)\)""")
        for (match in pattern.findAll(transformStr)) {
            val fn = match.groupValues[1]
            val args = match.groupValues[2].split(Regex("""[\s,]+""")).filter { it.isNotBlank() }.map { it.toFloat() }
            val m = Matrix()
            when (fn) {
                "translate" -> {
                    val tx = args.getOrElse(0) { 0f }
                    val ty = args.getOrElse(1) { 0f }
                    m.postTranslate(tx, ty)
                }
                "scale" -> {
                    val sx = args.getOrElse(0) { 1f }
                    val sy = args.getOrElse(1) { sx }
                    m.postScale(sx, sy)
                }
                "matrix" -> {
                    if (args.size >= 6) {
                        m.setValues(floatArrayOf(
                            args[0], args[2], args[4],
                            args[1], args[3], args[5],
                            0f, 0f, 1f
                        ))
                    }
                }
            }
            // postConcat applies in correct order: matrix.postConcat(child) = child * matrix
            return matrix
        }

    /** Parse path d attribute into commands */
    data class PathCmd(val letter: Char, val args: List<String>)

    private fun parsePathData(d: String, path: Path) {
        val commands = parsePathCommands(d)
        for (cmd in commands) {
            // This is kept for compatibility but not used in new flow
        }
    }

    private fun parsePathCommands(d: String): List<PathCmd> {
        val result = mutableListOf<PathCmd>()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            if (c.isLetter()) {
                val letter = c
                i++
                val start = i
                while (i < d.length && !d[i].isLetter()) i++
                val argsStr = d.substring(start, i).trim()
                val args = if (argsStr.isBlank()) emptyList() else argsStr.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
                result.add(PathCmd(c, args))
            } else {
                i++
            }
        }
        return result
    }

    /** Parse SVG and extract paths with transforms using stack-based parsing */
    class SvgParser(private val svgStr: String) {
        data class PathData(
            val commands: List<PathCmd>,
            val transform: Matrix
        )

        val viewBox: RectF = RectF()
        val paths: MutableList<Pair<List<PathCmd>, Matrix>> = mutableListOf()

        fun parse() {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(svgStr))

            val matrixStack = mutableListOf<Matrix>()
            matrixStack.add(Matrix())

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        val transformStr = parser.getAttributeValue(null, "transform")
                        val parentMatrix = matrixStack.last()
                        val currentMatrix = if (transformStr != null) {
                            val m = parseTransform(transformStr)
                            val result = Matrix()
                            result.set(parentMatrix)
                            result.postConcat(m)
                            result
                        } else {
                            Matrix(parentMatrix)
                        }
                        matrixStack.add(currentMatrix)

                        if (tag == "svg") {
                            val vbStr = parser.getAttributeValue(null, "viewBox") ?: "0 0 100 100"
                            val parts = vbStr.split(Regex("""[\s,]+""")).map { it.toFloat() }
                            if (parts.size >= 4) {
                                viewBox.set(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
                            }
                        } else if (tag == "path") {
                            val d = parser.getAttributeValue(null, "d") ?: ""
                            if (d.isNotBlank()) {
                                val commands = parsePathCommands(d)
                                val matrix = Matrix(matrixStack.last())
                                paths.add(commands to matrix)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "g") {
                            matrixStack.removeAt(matrixStack.lastIndex)
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        private fun parsePathCommands(d: String): List<PathCmd> {
            val result = mutableListOf<PathCmd>()
            var i = 0
            while (i < d.length) {
                val c = d[i]
                if (c.isLetter()) {
                    val letter = c
                    i++
                    val start = i
                    while (i < d.length && !d[i].isLetter()) i++
                    val argsStr = d.substring(start, i).trim()
                    val args = if (argsStr.isBlank()) emptyList() else argsStr.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
                    result.add(PathCmd(c, args))
                } else {
                    i++
                }
            }
            return result
        }
    }
}
