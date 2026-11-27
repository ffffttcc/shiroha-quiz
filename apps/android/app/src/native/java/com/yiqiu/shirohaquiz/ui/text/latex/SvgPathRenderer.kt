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
import kotlin.math.abs

/**
 * Minimal SVG path renderer for MathJax SVG output.
 * Supports: viewBox, <g transform="...">, <path d="..."> with M/L/H/V/C/Q/S/T/Z commands.
 * No external dependencies; pure Android graphics.
 *
 * Matrix multiplication order: parent.postConcat(child) == child * parent
 * This matches Python script's mat_multiply(parent, child) which applies child after parent.
 */
object SvgPathRenderer {
    private const val TAG = "SvgPathRenderer"
    private const val CACHE_SIZE = 64

    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

    /** Path command data class - defined first for forward references */
    private data class PathCmd(val letter: Char, val args: List<String>)

    /** Parse SVG transform string to Android Matrix */
    private fun parseTransform(transformStr: String?): Matrix {
        val result = Matrix()
        if (transformStr == null || transformStr.isBlank()) return result

        val pattern = Regex("""(\w+)\s*\(([^)]+)\)""")
        for (match in pattern.findAll(transformStr)) {
            val fn = match.groupValues[1]
            val argsStr = match.groupValues[2]
            val args = argsStr.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }.map { it.toFloat() }
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
            // preConcat for SVG spec order: transform="A B" means A then B => matrix = A * B
            // Loop processes left-to-right, so result.preConcat(m) = result * m accumulates correctly
            result.preConcat(m)
        }
        return result
    }

    /** Parse path d attribute into commands using NumberScanner for robust number parsing */
    private fun parsePathCommands(d: String): List<PathCmd> {
        val result = mutableListOf<PathCmd>()
        val scanner = NumberScanner(d)
        while (scanner.hasNext()) {
            val c = scanner.nextChar()
            if (c.isLetter()) {
                val letter = c
                val args = mutableListOf<String>()
                while (scanner.hasNext() && !scanner.peekChar().isLetter()) {
                    val num = scanner.nextNumber()
                    if (num != null) {
                        args.add(num)
                    }
                }
                result.add(PathCmd(letter, args))
            }
        }
        // Debug: log used command letters
        val letters = result.map { it.letter }.distinct().joinToString("")
        Log.d(TAG, "parsePathCommands: letters=$letters")
        return result
    }

    /** Number scanner for SVG path data: handles negative, decimal, scientific, and concatenated numbers */
    private class NumberScanner(private val input: String) {
        private var pos = 0

        fun hasNext(): Boolean = pos < input.length

        fun peekChar(): Char = if (pos < input.length) input[pos] else '\u0000'

        fun nextChar(): Char {
            val c = if (pos < input.length) input[pos] else '\u0000'
            pos++
            return c
        }

        fun nextNumber(): String? {
            skipWhitespaceAndComma()
            if (!hasNext()) return null

            val start = pos
            var hasExponent = false
            var hasDecimal = false

            while (hasNext()) {
                val c = input[pos]
                when (c) {
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                        // digit, continue
                    }
                    '-' -> {
                        if (pos == start || hasExponent) {
                            // minus at start or after e/E
                        } else {
                            break // minus in middle = next number
                        }
                    }
                    '+' -> {
                        if (hasExponent) {
                            // plus after e/E
                        } else if (pos == start) {
                            // plus at start
                        } else {
                            break
                        }
                    }
                    '.', ',' -> {
                        if (!hasDecimal) {
                            hasDecimal = true
                        } else {
                            break // second decimal/comma = end of number
                        }
                    }
                    'e', 'E' -> {
                        if (!hasExponent) {
                            hasExponent = true
                        } else {
                            break
                        }
                    }
                    else -> break
                }
                pos++
            }

            if (pos > start) {
                return input.substring(start, pos)
            }
            return null
        }

        private fun skipWhitespaceAndComma() {
            while (hasNext()) {
                val c = input[pos]
                if (c <= ' ' || c == ',') {
                    pos++
                } else {
                    break
                }
            }
        }
    }

    /** Parse SVG and extract paths with transforms using stack-based parsing */
    private class SvgParser(private val svgStr: String) {
        data class PathData(
            val commands: List<PathCmd>,
            val transform: Matrix
        )

        val viewBox: RectF = RectF()
        val paths: MutableList<PathData> = mutableListOf()
        val transformLogs: MutableList<String> = mutableListOf()  // debug: transform strings and matrices (max 5)

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
                            // Debug: log transform string and resulting matrix (first 5 only)
                            if (transformLogs.size < 5) {
                                val vals = FloatArray(9)
                                m.getValues(vals)
                                transformLogs.add("transform=\"$transformStr\" -> [${vals.joinToString(",")}]")
                            }
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
                            val parts = vbStr.trim().split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
                            if (parts.size >= 4) {
                                viewBox.set(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
                            }
                        } else if (tag == "path") {
                            val d = parser.getAttributeValue(null, "d") ?: ""
                            if (d.isNotBlank()) {
                                val commands = parsePathCommands(d)
                                val matrix = Matrix(matrixStack.last())
                                paths.add(PathData(commands, matrix))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "g") {
                            if (matrixStack.size > 1) {
                                matrixStack.removeAt(matrixStack.lastIndex)
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
    }

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
        // Debug logging setup
        val dbg = StringBuilder()
        fun dbgLog(s: String) { dbg.appendLine(s) }
        fun flushDbg(tag: String) {
            runCatching {
                val f = java.io.File("/data/data/com.reqir.shirohaquiz/files/svgpath_debug.txt")
                f.parentFile?.mkdirs()
                f.appendText("=== $tag @ ${System.currentTimeMillis()} ===\n$dbg\n")
            }
        }

        // 1. Entry
        dbgLog("ENTRY: svg.length=${svgStr.length}, head120=${svgStr.take(120).replace("\n", "\\n")}")
        val cacheKey = "$svgStr|$targetHeightPx|$colorArgb"
        cache.get(cacheKey)?.let { return it }

        return try {
            val parser = SvgParser(svgStr)
            parser.parse()

            // Debug: log parsed transforms (max 5)
            parser.transformLogs.forEach { dbgLog(it) }

            // 2. viewBox
            val vb = parser.viewBox
            if (vb.width() <= 0 || vb.height() <= 0) {
                dbgLog("viewBox parse FAILED: $vb")
                flushDbg("viewBox_FAIL")
                Log.e(TAG, "Invalid viewBox: $vb")
                return null
            }
            dbgLog("viewBox: minX=${vb.left}, minY=${vb.top}, W=${vb.width()}, H=${vb.height()}")

            // 3. path count and d lengths
            val pathCount = parser.paths.size
            dbgLog("paths found: $pathCount")
            parser.paths.take(3).forEachIndexed { idx, pd ->
                val dLen = pd.commands.sumOf { it.args.joinToString(" ").length }
                dbgLog("  path#$idx d.len=$dLen")
            }

            // FIRST PASS: collect all transformed points to compute content bbox
            // Points are transformed by localTransform (includes <g transform="scale(1,-1)">), NOT by baseMatrix
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var hasPoints = false

            for (pathData in parser.paths) {
                collectTransformedPoints(pathData.commands, pathData.transform) { x, y ->
                    hasPoints = true
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }

            // Fallback to viewBox if no points collected
            if (!hasPoints) {
                minX = vb.left
                minY = vb.top
                maxX = vb.right
                maxY = vb.bottom
            }

            val bw = (maxX - minX).coerceAtLeast(1f)
            val bh = (maxY - minY).coerceAtLeast(1f)
            val s = targetHeightPx.toFloat() / bh                      // height fills target
            val outW = (bw * s).roundToInt().coerceAtLeast(1)
            val outH = targetHeightPx.coerceAtLeast(1)

            dbgLog("bbox: minX=$minX, minY=$minY, maxX=$maxX, maxY=$maxY, bw=$bw, bh=$bh")
            dbgLog("targetHeightPx=$targetHeightPx, outW=$outW, outH=$outH, scale=$s")

            // 3) Bitmap - use bbox-derived dimensions
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            // 4) Paint
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorArgb
                style = Paint.Style.FILL
            }

            // 5) Base matrix: content bbox -> canvas
            // Path coordinates already include <g transform="scale(1,-1)"> from ancestors,
            // so NO additional Y flip here. Just translate bbox to origin, then scale.
            val baseMatrix = Matrix().apply {
                postTranslate(-minX, -minY)
                postScale(s, s)
            }

            var drawn = false
            for ((idx, pathData) in parser.paths.withIndex()) {
                try {
                    val path = buildPath(pathData.commands, pathData.transform, baseMatrix)
                    if (!path.isEmpty) {
                        canvas.drawPath(path, paint)
                        drawn = true
                        dbgLog("path#$idx points=${pathData.commands.sumOf { it.args.size / 2 }}")
                    } else {
                        dbgLog("path#$idx EMPTY")
                    }
                } catch (e: Exception) {
                    val stack = e.stackTrace.take(5).joinToString("\n") { it.toString() }
                    dbgLog("path#$idx EXCEPTION: ${e.javaClass.simpleName}: ${e.message}\n$stack")
                }
            }

            if (!drawn) {
                dbgLog("return null reason=No paths drawn")
                flushDbg("NO_PATHS_DRAWN")
                Log.w(TAG, "No paths drawn")
                bitmap.recycle()
                return null
            }

            cache.put(cacheKey, bitmap)
            dbgLog("bitmap created ${bitmap.width}x${bitmap.height}")
            flushDbg("SUCCESS")
            bitmap
        } catch (e: Exception) {
            val stack = e.stackTrace.take(5).joinToString("\n") { it.toString() }
            dbgLog("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}\n$stack")
            flushDbg("EXCEPTION")
            Log.e(TAG, "Render failed", e)
            null
        }
    }

    /** Collect all transformed points from path commands for bbox computation.
     * Applies localTransform (includes <g> ancestors like scale(1,-1)), NOT baseMatrix. */
    private fun collectTransformedPoints(
        commands: List<PathCmd>,
        localTransform: Matrix,
        onPoint: (Float, Float) -> Unit
    ) {
        var x = 0f
        var y = 0f
        var lastCubicCtrlX = 0f
        var lastCubicCtrlY = 0f
        var lastQuadCtrlX = 0f
        var lastQuadCtrlY = 0f

        for (cmd in commands) {
            val isRelative = cmd.letter.isLowerCase()
            val letter = cmd.letter.uppercaseChar()
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
                        val pt = floatArrayOf(tx, ty)
                        localTransform.mapPoints(pt)
                        onPoint(pt[0], pt[1])
                        i += 2
                    }
                    // Reset control points on move (SVG spec)
                    lastCubicCtrlX = 0f; lastCubicCtrlY = 0f
                    lastQuadCtrlX = 0f; lastQuadCtrlY = 0f
                }
                'L' -> {
                    while (i + 1 < args.size) {
                        val nx = args[i].toFloat()
                        val ny = args[i + 1].toFloat()
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny
                        x = tx; y = ty
                        val pt = floatArrayOf(tx, ty)
                        localTransform.mapPoints(pt)
                        onPoint(pt[0], pt[1])
                        i += 2
                    }
                }
                'H' -> {
                    for (arg in args) {
                        val nx = arg.toFloat()
                        val tx = if (isRelative) x + nx else nx
                        x = tx
                        val pt = floatArrayOf(tx, y)
                        localTransform.mapPoints(pt)
                        onPoint(pt[0], pt[1])
                    }
                }
                'V' -> {
                    for (arg in args) {
                        val ny = arg.toFloat()
                        val ty = if (isRelative) y + ny else ny
                        y = ty
                        val pt = floatArrayOf(x, ty)
                        localTransform.mapPoints(pt)
                        onPoint(pt[0], pt[1])
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

                        // Store second control point for S reflection
                        lastCubicCtrlX = tx2
                        lastCubicCtrlY = ty2

                        // Include control points in bbox (approximation, slightly enlarges bbox)
                        val pts = floatArrayOf(tx1, ty1, tx2, ty2, tx, ty)
                        localTransform.mapPoints(pts)
                        onPoint(pts[0], pts[1])
                        onPoint(pts[2], pts[3])
                        onPoint(pts[4], pts[5])

                        x = tx; y = ty
                        i += 6
                    }
                }
                'S' -> {
                    while (i + 3 < args.size) {
                        val x2 = args[i].toFloat()
                        val y2 = args[i + 1].toFloat()
                        val nx = args[i + 2].toFloat()
                        val ny = args[i + 3].toFloat()

                        // First control point = reflection of last cubic's second control point
                        val tx1 = 2 * x - lastCubicCtrlX
                        val ty1 = 2 * y - lastCubicCtrlY
                        val tx2 = if (isRelative) x + x2 else x2
                        val ty2 = if (isRelative) y + y2 else y2
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny

                        // Store this segment's second control point for next S
                        lastCubicCtrlX = tx2
                        lastCubicCtrlY = ty2

                        val pts = floatArrayOf(tx1, ty1, tx2, ty2, tx, ty)
                        localTransform.mapPoints(pts)
                        onPoint(pts[0], pts[1])
                        onPoint(pts[2], pts[3])
                        onPoint(pts[4], pts[5])

                        x = tx; y = ty
                        i += 4
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

                        // Store control point for T reflection
                        lastQuadCtrlX = tx1
                        lastQuadCtrlY = ty1

                        val pts = floatArrayOf(tx1, ty1, tx, ty)
                        localTransform.mapPoints(pts)
                        onPoint(pts[0], pts[1])
                        onPoint(pts[2], pts[3])

                        x = tx; y = ty
                        i += 4
                    }
                }
                'T' -> {
                    while (i + 1 < args.size) {
                        val nx = args[i].toFloat()
                        val ny = args[i + 1].toFloat()

                        // Control point = reflection of last quad's control point
                        val tx1 = 2 * x - lastQuadCtrlX
                        val ty1 = 2 * y - lastQuadCtrlY
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny

                        // Store this end point as control point for next T
                        lastQuadCtrlX = tx1
                        lastQuadCtrlY = ty1

                        val pts = floatArrayOf(tx1, ty1, tx, ty)
                        localTransform.mapPoints(pts)
                        onPoint(pts[0], pts[1])
                        onPoint(pts[2], pts[3])

                        x = tx; y = ty
                        i += 2
                    }
                }
                'Z' -> {
                    // close path - no new points
                }
                else -> {
                    // Unsupported command - ignore for bbox
                }
            }
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
        var lastCubicCtrlX = 0f
        var lastCubicCtrlY = 0f
        var lastQuadCtrlX = 0f
        var lastQuadCtrlY = 0f

        // Combine: first apply localTransform (from <g> ancestors), then baseMatrix (viewBox->canvas)
        // M = baseMatrix * localTransform  (apply local first, then base)
        val combinedTransform = Matrix()
        combinedTransform.set(localTransform)
        combinedTransform.postConcat(baseMatrix)   // M = base × local：先应用 SVG 内部变换，再映射到画布

        for (cmd in commands) {
            val isRelative = cmd.letter.isLowerCase()
            val letter = cmd.letter.uppercaseChar()
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
                        val pt = floatArrayOf(tx, ty)
                        combinedTransform.mapPoints(pt)
                        if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
                        i += 2
                    }
                    // Reset control points on move (SVG spec)
                    lastCubicCtrlX = 0f; lastCubicCtrlY = 0f
                    lastQuadCtrlX = 0f; lastQuadCtrlY = 0f
                }
                'L' -> {
                    while (i + 1 < args.size) {
                        val nx = args[i].toFloat()
                        val ny = args[i + 1].toFloat()
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny
                        x = tx; y = ty
                        val pt = floatArrayOf(tx, ty)
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
                        val pt = floatArrayOf(tx, y)
                        combinedTransform.mapPoints(pt)
                        path.lineTo(pt[0], pt[1])
                    }
                }
                'V' -> {
                    for (arg in args) {
                        val ny = arg.toFloat()
                        val ty = if (isRelative) y + ny else ny
                        y = ty
                        val pt = floatArrayOf(x, ty)
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

                        // Store second control point for S reflection
                        lastCubicCtrlX = tx2
                        lastCubicCtrlY = ty2

                        val pts = floatArrayOf(tx1, ty1, tx2, ty2, tx, ty)
                        combinedTransform.mapPoints(pts)
                        path.cubicTo(pts[0], pts[1], pts[2], pts[3], pts[4], pts[5])

                        x = tx; y = ty
                        i += 6
                    }
                }
                'S' -> {
                    // Smooth cubic: first control point = reflection of previous cubic's second control point
                    while (i + 3 < args.size) {
                        val x2 = args[i].toFloat()
                        val y2 = args[i + 1].toFloat()
                        val nx = args[i + 2].toFloat()
                        val ny = args[i + 3].toFloat()

                        // First control point = reflection of last cubic's second control point
                        val tx1 = 2 * x - lastCubicCtrlX
                        val ty1 = 2 * y - lastCubicCtrlY
                        val tx2 = if (isRelative) x + x2 else x2
                        val ty2 = if (isRelative) y + y2 else y2
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny

                        // Store this segment's second control point for next S
                        lastCubicCtrlX = tx2
                        lastCubicCtrlY = ty2

                        val pts = floatArrayOf(tx1, ty1, tx2, ty2, tx, ty)
                        combinedTransform.mapPoints(pts)
                        path.cubicTo(pts[0], pts[1], pts[2], pts[3], pts[4], pts[5])

                        x = tx; y = ty
                        i += 4
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

                        // Store control point for T reflection
                        lastQuadCtrlX = tx1
                        lastQuadCtrlY = ty1

                        val pts = floatArrayOf(tx1, ty1, tx, ty)
                        combinedTransform.mapPoints(pts)
                        path.quadTo(pts[0], pts[1], pts[2], pts[3])

                        x = tx; y = ty
                        i += 4
                    }
                }
                'T' -> {
                    // Smooth quadratic: control point = reflection of previous quad's control point
                    while (i + 1 < args.size) {
                        val nx = args[i].toFloat()
                        val ny = args[i + 1].toFloat()

                        // Control point = reflection of last quad's control point
                        val tx1 = 2 * x - lastQuadCtrlX
                        val ty1 = 2 * y - lastQuadCtrlY
                        val tx = if (isRelative) x + nx else nx
                        val ty = if (isRelative) y + ny else ny

                        // Store this end point as control point for next T
                        lastQuadCtrlX = tx1
                        lastQuadCtrlY = ty1

                        val pts = floatArrayOf(tx1, ty1, tx, ty)
                        combinedTransform.mapPoints(pts)
                        path.quadTo(pts[0], pts[1], pts[2], pts[3])

                        x = tx; y = ty
                        i += 2
                    }
                }
                'Z' -> {
                    path.close()
                }
                else -> {
                    Log.w(TAG, "Unsupported path command: $letter")
                }
            }
        }
        return path
    }
}