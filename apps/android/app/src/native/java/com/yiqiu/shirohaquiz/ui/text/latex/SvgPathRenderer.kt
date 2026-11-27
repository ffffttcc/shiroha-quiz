package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Minimal SVG path renderer for MathJax SVG output.
 * Supports: viewBox, <g transform="...">, <path d="..."> with M/L/Q/C/H/V/Z commands.
 * No external dependencies; pure Android graphics.
 */
object SvgPathRenderer {
    private const val TAG = "SvgPathRenderer"

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
        return try {
            val parser = SvgParser(svgStr)
            parser.parse()
            
            // Scale from viewBox to target height
            val vb = parser.viewBox
            val scale = targetHeightPx.toFloat() / vb.height()
            val bitmapWidth = (vb.width() * scale).roundToInt()
            val bitmapHeight = targetHeightPx
            
            if (bitmapWidth <= 0 || bitmapHeight <= 0) {
                Log.e(TAG, "Invalid bitmap size: ${bitmapWidth}x${bitmapHeight}")
                return null
            }
            
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            
            // Transform: viewBox -> bitmap coordinates
            // viewBox: (minX, minY, width, height) -> (0, 0, bitmapWidth, bitmapHeight)
            // Need to flip Y because viewBox Y increases upward (math coords), canvas Y increases downward
            val viewToBitmap = Matrix().apply {
                postScale(1f, -1f)  // Flip Y
                postTranslate(-vb.left, -vb.bottom)  // Move origin to viewBox bottom-left
                postScale(scale, scale)  // Scale to target size
                postTranslate(0f, bitmapHeight.toFloat())  // Flip Y back for canvas
            }
            
            val paint = Paint().apply {
                isAntiAlias = true
                color = colorArgb
                style = Paint.Style.FILL
            }
            
            var drawn = false
            for (pathData in parser.paths) {
                val path = buildPath(pathData.commands, pathData.transform)
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
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Render failed", e)
            null
        }
    }
    
    /** Build Android Path from path commands with transform matrix applied */
    private fun buildPath(commands: List<PathCmd>, transform: Matrix): Path {
        val path = Path()
        val tempMatrix = Matrix()
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        
        for (cmd in commands) {
            val isRelative = cmd.letter.isLowerCase()
            val letter = cmd.letter.uppercase()
            val args = cmd.args.toFloatArray()
            var i = 0
            
            when (cmd.letter.uppercase()) {
                'M' -> {
                    while (i + 1 < cmd.args.size) {
                        val nx = cmd.args[i].toFloat()
                        val ny = cmd.args[i + 1].toFloat()
                        val tx = if (cmd.letter.isLowerCase()) x + nx else nx
                        val ty = if (cmd.letter.isLowerCase()) y + ny else ny
                        x = tx; y = ty
                        if (i == 0) {
                            startX = x; startY = y
                            tempMatrix.setScale(1f, -1f)  // Will be combined with transform later
                            path.moveTo(nx, ny)  // Apply transform in batch below
                        } else {
                            path.lineTo(nx, ny)
                        }
                        i += 2
                    }
                }
                'L' -> {
                    while (i + 1 < cmd.args.size) {
                        val nx = cmd.args[i].toFloat()
                        val ny = cmd.args[i + 1].toFloat()
                        val tx = if (cmd.letter.isLowerCase()) x + nx else nx
                        val ty = if (cmd.letter.isLowerCase()) y + ny else ny
                        x = tx; y = ty
                        path.lineTo(nx, ny)
                        i += 2
                    }
                }
                'H' -> {
                    for (arg in cmd.args) {
                        val nx = arg.toFloat()
                        val tx = if (cmd.letter.isLowerCase()) x + nx else nx
                        x = tx
                        path.lineTo(nx, y)
                    }
                }
                'V' -> {
                    for (arg in cmd.args) {
                        val ny = arg.toFloat()
                        val ty = if (cmd.letter.isLowerCase()) y + ny else ny
                        y = ty
                        path.lineTo(x, ny)
                    }
                }
                'C' -> {
                    while (i + 5 < cmd.args.size) {
                        val x1 = cmd.args[i].toFloat()
                        val y1 = cmd.args[i + 1].toFloat()
                        val x2 = cmd.args[i + 2].toFloat()
                        val y2 = cmd.args[i + 3].toFloat()
                        val nx = cmd.args[i + 4].toFloat()
                        val ny = cmd.args[i + 5].toFloat()
                        val tx1 = if (cmd.letter.isLowerCase()) x + x1 else x1
                        val ty1 = if (cmd.letter.isLowerCase()) y + y1 else y1
                        val tx2 = if (cmd.letter.isLowerCase()) x + x2 else x2
                        val ty2 = if (cmd.letter.isLowerCase()) y + y2 else y2
                        val tx = if (cmd.letter.isLowerCase()) x + nx else nx
                        val ty = if (cmd.letter.isLowerCase()) y + ny else ny
                        path.cubicTo(tx1, ty1, tx2, ty2, nx, ny)
                        x = tx; y = ty
                        i += 6
                    }
                }
                'Q' -> {
                    while (i + 3 < cmd.args.size) {
                        val x1 = cmd.args[i].toFloat()
                        val y1 = cmd.args[i + 1].toFloat()
                        val nx = cmd.args[i + 2].toFloat()
                        val ny = cmd.args[i + 3].toFloat()
                        val tx1 = if (cmd.letter.isLowerCase()) x + x1 else x1
                        val ty1 = if (cmd.letter.isLowerCase()) y + y1 else y1
                        val tx = if (cmd.letter.isLowerCase()) x + nx else nx
                        val ty = if (cmd.letter.isLowerCase()) y + ny else ny
                        path.quadTo(tx1, ty1, nx, ny)
                        x = tx; y = ty
                        i += 4
                    }
                }
                'Z' -> {
                    path.close()
                }
                else -> {
                    Log.w("SvgPathRenderer", "Unsupported path command: ${cmd.letter}")
                }
            }
            return path
        }
    }
    
    /** 2D affine matrix [a c e; b d f; 0 0 1] */
    data class Matrix2D(
        val a: Float = 1f, val b: Float = 0f,
        val c: Float = 0f, val d: Float = 1f,
        val e: Float = 0f, val f: Float = 0f
    ) {
        companion object {
            val IDENTITY = Matrix2D()
        }
        
        fun multiply(other: Matrix2D): Matrix2D = Matrix2D(
            a = a * other.a + c * other.b,
            b = b * other.a + d * other.b,
            c = a * other.c + c * other.d,
            d = b * other.c + d * other.d,
            e = a * other.e + c * other.f + e,
            f = b * other.e + d * other.f + f
        )
        
        fun mapPoint(x: Float, y: Float): Pair<Float, Float> =
            (a * x + c * y + e, b * x + d * y + f)
        
        fun toAndroidMatrix(): Matrix = Matrix().apply {
            setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f))
        }
    }
    
    /** Parse SVG transform string to Matrix2D */
    fun parseTransform(transformStr: String?): Matrix2D {
        if (transformStr == null || transformStr.isBlank()) return Matrix2D.IDENTITY
        var matrix = Matrix2D.IDENTITY
        val pattern = Regex("""(\w+)\s*\(([^)]+)\)""")
        for (match in pattern.findAll(transformStr)) {
            val fn = match.groupValues[1]
            val args = match.groupValues[2].split(Regex(@"[\s,]+")).filter { it.isNotBlank() }.map { it.toFloat() }
            val m = when (fn) {
                "translate" -> {
                    val tx = args.getOrElse(0) { 0f }
                    val ty = args.getOrElse(1) { 0f }
                    Matrix2D(1f, 0f, 0f, 1f, tx, ty)
                }
                "scale" -> {
                    val sx = args.getOrElse(0) { 1f }
                    val sy = args.getOrElse(1) { sx }
                    Matrix2D(sx, 0f, 0f, sy, 0f, 0f)
                }
                "matrix" -> {
                    if (args.size >= 6) Matrix2D(args[0], args[1], args[2], args[3], args[4], args[5])
                    else Matrix2D.IDENTITY
                }
                else -> Matrix2D.IDENTITY
            }
            matrix = matrix.multiply(m)
        }
        return matrix
    }
    
    /** Parse path d attribute into commands */
    data class PathCmd(val letter: Char, val args: List<String>)
    
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
    
    /** Parse SVG and extract paths with transforms */
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
                            val m = parseTransform(transformStr).toAndroidMatrix()
                            val result = Matrix()
                            parentMatrix.postConcat(m)
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
                        matrixStack.removeAt(matrixStack.lastIndex)
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
