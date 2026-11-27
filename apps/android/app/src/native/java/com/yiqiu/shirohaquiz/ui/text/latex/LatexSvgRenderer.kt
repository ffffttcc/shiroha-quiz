package com.yiqiu.shirohaquiz.ui.text.latex

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.util.Log
import kotlin.collections.LinkedHashMap
import kotlin.ranges.coerceAtLeast

/**
 * SVG → Bitmap 渲染器（单一写入者，替代 LatexSvgPainter 的两套尺寸逻辑）。
 * 内部使用 SvgPathRenderer（纯 Android 实现，无 androidsvg 依赖）。
 * 线程安全：内部 LruCache + 无状态解析，可多线程并发调用。
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
     * SVG 清洗后的数据类（供单测/调试复用）。
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
            // 使用自研 SvgPathRenderer 替代 androidsvg
            val bitmap = SvgPathRenderer.renderToBitmap(cleaned, targetHeightPx, Color.BLACK)
            if (bitmap != null) {
                cache.put(cacheKey, bitmap)
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "render fail: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 纯函数清洗链（固定顺序、幂等、每步独立）。
     * 返回 SanitizedSvg(cleaned, vbWidth, vbHeight)
     */
    internal fun sanitize(svgRaw: String, colorArgb: Int): SanitizedSvg {
        val colorHex = String.format("#%06X", (0xFFFFFF and colorArgb))

        // 1. 只切出根标签 <svg ...>
        val openStart = svgRaw.indexOf("<svg")
        if (openStart < 0) {
            Log.e("LatexRender", "sanitize: no <svg tag found, svg_raw_head=${svgRaw.take(200)}")
            return SanitizedSvg(svgRaw, 1608f, 836f)
        }
        val openEnd = svgRaw.indexOf('>', openStart)
        if (openEnd < 0) {
            Log.e("LatexRender", "sanitize: no closing > for <svg, svg_raw_head=${svgRaw.take(200)}")
            return SanitizedSvg(svgRaw, 1608f, 836f)
        }
        val head = svgRaw.substring(0, openEnd + 1)     // 含 '>'
        val body = svgRaw.substring(openEnd + 1)        // 其余内容原样保留
        val attrArea = head.substring(4, head.length - 1) // 去掉 "<svg" 和 ">"

        // 2. 解析成有序列属性表（保持顺序，避免重复）
        val attrs = LinkedHashMap<String, String>()
        Regex("""([\w:-]+)\s*=\s*"([^"]*)"""").findAll(attrArea).forEach { attrs[it.groupValues[1]] = it.groupValues[2] }

        // 3. 独立解析本条的 viewBox
        val vb = attrs["viewBox"]?.split(Regex("""[\s,]+"""))?.mapNotNull { it.toFloatOrNull() }
        val vbW = vb?.getOrNull(2) ?: 0f
        val vbH = vb?.getOrNull(3) ?: 0f

        // 4. 清洗属性
        attrs.remove("style")            // vertical-align 等 ex 单位一律不要
        attrs.remove("role"); attrs.remove("focusable")
        attrs.keys.filter { it.startsWith("aria-") }.forEach { attrs.remove(it) }
        attrs.remove("width")
        attrs.remove("height")
        if (vbW > 0 && vbH > 0) {
            attrs["width"] = vbW.toInt().toString()
            attrs["height"] = vbH.toInt().toString()
        }

        // 5. 重组根标签（属性值统一双引号，绝不产生三引号）
        val newHead = buildString {
            append("<svg")
            attrs.forEach { (k, v) -> append(" ").append(k).append("=\"").append(v).append("\"") }
            append(">")
        }
        var cleaned = newHead + body

        // 6. currentColor 只在属性值里替换（安全）
        cleaned = cleaned.replace("currentColor", String.format("#%06X", (0xFFFFFF and colorArgb)))

        // 自检：确保 xmlns 存在且引号成对
        if (!cleaned.contains("xmlns=\"http://www.w3.org/2000/svg\"")) {
            Log.e("LatexRender", "HEAD_MALFORMED: xmlns missing or malformed in head: $newHead")
            // 仍尝试继续，但记录诊断
        }

        return SanitizedSvg(cleaned, vbW.toFloat(), vbH.toFloat())
    }

    /**
     * 清空缓存并回收 Bitmap
     */
    fun clearCache() {
        cache.snapshot().forEach { (_, bmp) -> bmp.recycle() }
        cache.evictAll()
        Log.d(TAG, "Bitmap cache cleared")
    }
}
