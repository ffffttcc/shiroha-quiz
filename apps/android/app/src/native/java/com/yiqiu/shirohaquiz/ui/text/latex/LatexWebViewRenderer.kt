package com.yiqiu.shirohaquiz.ui.text.latex

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import java.util.concurrent.atomic.AtomicInteger

/**
 * JLaTeXMath-based LaTeX renderer (synchronous, no WebView).
 *
 * Design:
 * - Synchronous rendering using JLaTeXMath (pure Java)
 * - Serial queue: one render at a time, tracked by sequenceId
 * - LRU cache + optional disk cache (delegated to JLatexMathRenderer)
 * - Result delivered via callback on UI thread
 * - Fallback to LatexEngine (QuickJS) on failure
 * - Errors: JLATEX_FAILED, DECODE_FAILED, JS_ERROR:<msg>
 */
class LatexWebViewRenderer(private val activity: Activity) {

    companion object {
        private const val TAG = "LatexRender"
        private const val RENDER_TIMEOUT_MS = 3000L
        private const val MAX_CACHE_ENTRIES = 60
    }

    private var destroyed = false
    private var isReady = true // JLaTeXMath is always ready (synchronous)
    private var warmUpCallback: ((Boolean) -> Unit)? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val renderQueue = java.util.ArrayDeque<RenderRequest>()
    private var isProcessing = false
    private val sequenceCounter = AtomicInteger(0)
    private val lruCache = object : LruCache<String, Bitmap>(60) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                // Do NOT recycle bitmap, let GC handle it
                Log.d(TAG, "Bitmap cache evicted (no recycle)")
            }
        }
    }

    /**
     * Request holder for the serial queue.
     */
    private data class RenderRequest(
        val sequenceId: Int,
        val latex: String,
        val heightPx: Int,
        val color: Int,
        val onResult: (Bitmap?, String?) -> Unit
    )

    /**
     * Attach to a container ViewGroup.
     * For JLaTeXMath, this is a no-op (kept for API compatibility).
     */
    @Suppress("UNUSED_PARAMETER")
    fun attach(container: android.view.ViewGroup) {
        // JLaTeXMath doesn't need a WebView container - no-op for API compatibility
    }

    /**
     * Warm up: JLaTeXMath is always ready synchronously.
     * Callback invoked immediately on UI thread with success.
     */
    fun warmUp(onReady: (Boolean) -> Unit) {
        // JLaTeXMath is always ready - no warm-up needed
        isReady = true
        handler.post { onReady(true) }
    }

    /**
     * Render a single LaTeX formula to Bitmap.
     * Uses JLaTeXMath synchronously.
     *
     * @param latex LaTeX source string
     * @param heightPx Target height in pixels (at device pixel ratio)
     * @param color ARGB color for the formula
     * @param onResult Callback with (Bitmap?, errorCode?) on UI thread
     */
    fun render(latex: String, heightPx: Int, color: Int, onResult: (Bitmap?, String?) -> Unit) {
        if (destroyed) {
            onResult(null, "WEBVIEW_DESTROYED")
            return
        }
        val cacheKey = "$latex|$heightPx|$color"

        // 1) Memory cache (LruCache)
        lruCache.get(cacheKey)?.let { cached ->
            Log.d(TAG, "Memory cache hit for: ${latex.take(40)}")
            handler.post { onResult(cached, null) }
            return
        }

        // 2) Enqueue for JLaTeXMath rendering (serial queue)
        enqueueRender(latex, heightPx, color, onResult, cacheKey)
    }

    private fun enqueueRender(latex: String, heightPx: Int, color: Int, onResult: (Bitmap?, String?) -> Unit, cacheKey: String) {
        if (destroyed) {
            onResult(null, "WEBVIEW_DESTROYED")
            return
        }
        val seqId = sequenceCounter.incrementAndGet()
        val request = RenderRequest(seqId, latex, heightPx, color, onResult)
        renderQueue.add(request)
        Log.d(TAG, "Enqueued render seq=$seqId queueSize=${renderQueue.size}")
        processQueue()
    }

    /**
     * Process the serial render queue.
     * Only one render in flight at a time.
     */
    private fun processQueue() {
        if (destroyed || isProcessing || renderQueue.isEmpty()) return
        val peekRequest = renderQueue.peekFirst() ?: return
        isProcessing = true

        // Render synchronously using JLaTeXMath
        val bmp = JLatexMathRenderer.render(peekRequest.latex, peekRequest.heightPx, peekRequest.color)

        isProcessing = false
        val pollRequest = renderQueue.pollFirst() ?: return
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            lruCache.put("$pollRequest.latex|${pollRequest.heightPx}|${pollRequest.color}", bmp)
            handler.post { pollRequest.onResult(bmp, null) }
        } else {
            handler.post { pollRequest.onResult(null, "JLATEX_FAILED") }
        }
        processQueue()
    }

    /**
     * Release resources.
     * Must call from onDestroy / DisposableEffect onDispose.
     */
    fun release() {
        destroyed = true
        lruCache.evictAll()
        Log.d(TAG, "JLaTeXMathRenderer released")
    }

    /** Check if renderer is ready for rendering. */
    fun isReady(): Boolean = isReady && !destroyed

    /**
     * Returns a diagnostic string for debugging.
     */
    fun diagnostic(): String {
        return "engine=jlatexmath cached=${lruCache.snapshot().size} destroyed=$destroyed"
    }
}