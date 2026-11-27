package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Picture
import android.util.LruCache
import android.util.Log
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import kotlin.ranges.coerceAtLeast

/**
 * SVG → Bitmap 渲染器（单一写入者，替代 LatexSvgPainter 的两套尺寸逻辑）。
 * 线程安全：内部 LruCache + AndroidSVG 无状态解析，可多线程并发调用。
 */
object LatexSvgRenderer {
    private const val TAG = "LatexRender"
    private const val MAX_CACHE_ENTRIES = 80

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            if (evicted) {
                oldValue.recycle()
                Log.d(TAG, "Bitmap cache evicted: ${key.take(40)}...")
            }
        }
    }

    /**
     * 清洗后的 SVG 数据类（供单测/调试复用）。
     */
    data class SanitizedSvg(
        val cleaned: String,
        val vbWidth: Float,
        val vbHeight: Float
    )

    /**
     * SVG 原始字符串 → Bitmap。
     * @param svgRaw MathJax 输出的原始 SVG 字符串
     * @param targetHeightPx 目标高度（像素），宽度按 viewBox 比例自动计算
     * @param colorArgb 替换 currentColor 用的颜色（ARGB，默认黑）
     * @return Bitmap 或 null（失败已在内部记日志）
     */
    fun render(svgRaw: String, targetHeightPx: Int, colorArgb: Int = Color.BLACK): Bitmap? {
        val cacheKey = "$svgRaw|$targetHeightPx|$colorArgb"
        cache.get(cacheKey)?.let { return it }

        val sanitized = sanitize(svgRaw, colorArgb)
        val cleaned = sanitized.cleaned
        val vbW = sanitized.vbWidth
        val vbH = sanitized.vbHeight

        // 计算目标宽高（保持 viewBox 比例）
        val targetWidthPx = (targetHeightPx * vbW / vbH).toInt().coerceAtLeast(1)
        val targetHeight = targetHeightPx.coerceAtLeast(1)

        Log.d(TAG, "sanitized=${cleaned.take(160)}")
        Log.d(TAG, "vb=${vbW.toInt()}x${vbH.toInt()} targetH=$targetHeightPx bitmap=${targetWidthPx}x$targetHeight")

        return try {
            val svg = SVG.getFromString(cleaned)
            // 首选：renderToPicture(w, h) —— 直接产出 Picture，尺寸由我们控制
            val picture: Picture = try {
                val method = SVG::class.java.getMethod("renderToPicture", Int::class.java, Int::class.java)
                method.invoke(svg, targetWidthPx, targetHeight) as Picture
            } catch (e: NoSuchMethodException) {
                // 兜底：旧版本用 setDocumentWidth/Height + beginRecording
                svg.setDocumentWidth(targetWidthPx.toFloat())
                svg.setDocumentHeight(targetHeight.toFloat())
                val pic = Picture()
                val canvas = pic.beginRecording(targetWidthPx, targetHeight)
                svg.renderToCanvas(canvas)
                pic.endRecording()
                pic
            }

            val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            picture.draw(canvas)
            cache.put(cacheKey, bitmap)
            bitmap
        } catch (e: SVGParseException) {
            Log.e(TAG, "render fail: SVGParseException: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "render fail: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 纯函数清洗链（固定顺序、幂等、每步独立）。
     * 返回 SanitizedSvg(cleaned, vbWidth, vbHeight)
     */
    internal fun sanitize(svg: String, colorArgb: Int): SanitizedSvg {
        var result = svg.trim()
        val colorHex = String.format("#%06X", (0xFFFFFF and colorArgb))

        // a) 根 <svg> 的 style 里删除含 vertical-align 的声明（androidsvg 不支持 ex）
        result = result.replace(Regex("""<svg(\s[^>]*)?>""")) { match ->
            val tag = match.groupValues[0]
            val styleMatch = Regex("""style\s*=\s*"([^"]*)"""").find(tag)
            if (styleMatch != null) {
                val styleContent = styleMatch.groupValues[1]
                val kept = styleContent
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains("vertical-align", true) }
                    .joinToString("; ")
                val newStyle = if (kept.isEmpty()) "" else """style="$kept""""
                tag.replace(styleMatch.groupValues[0], newStyle)
            } else tag
        }

        // b) currentColor → #RRGGBB
        result = result.replace("currentColor", colorHex)

        // c) 用 viewBox 的 W/H 覆盖根 svg 的 width/height，写成纯数字
        // 先提取 viewBox
        var vbW = 1608f
        var vbH = 836f
        val vbMatch = Regex("""viewBox\s*=\s*"([^"]+)"""").find(result)
        if (vbMatch != null) {
            val parts = vbMatch.groupValues[1].trim().split("\\s+").filter { it.isNotEmpty() }
            if (parts.size >= 4) {
                vbW = parts[2].toFloat()
                vbH = parts[3].toFloat()
            }
        }
        // 删除旧 width/height，重写为纯数字
        result = result.replace(Regex("""\swidth="[^"]*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\sheight="[^"]*""", RegexOption.IGNORE_CASE), "")
        // 确保 viewBox 存在
        if (vbMatch == null) {
            result = result.replaceFirst("<svg", "<svg viewBox=\"0 0 $vbW $vbH\"")
        }
        result = result.replaceFirst("<svg", "<svg width=\"${vbW.toInt()}\" height=\"${vbH.toInt()}\"")

        // d) 删 role / focusable / aria-*
        result = result.replace(Regex("""\s(role|focusable|aria-[a-z-]+)\s*=\s*"[^"]*"""", RegexOption.IGNORE_CASE), "")

        return SanitizedSvg(result, vbW, vbH)
    }

    /** 清空缓存并回收 Bitmap */
    fun clearCache() {
        cache.snapshot().forEach { (_, bmp) -> bmp.recycle() }
        cache.evictAll()
        Log.d(TAG, "Bitmap cache cleared")
    }
}