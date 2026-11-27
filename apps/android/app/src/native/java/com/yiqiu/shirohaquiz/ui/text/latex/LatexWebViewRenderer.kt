package com.yiqiu.shirohaquiz.ui.text.latex

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView-based LaTeX renderer using MathJax 3.
 *
 * Design:
 * - Single WebView per Activity, created with Activity Context (not applicationContext)
 * - Serial queue: one evaluateJavascript at a time, tracked by sequenceId
 * - JS side: renderBatch(jsonItems) → returns base64 PNG per item
 * - Result delivered via postOnAnimation to UI thread
 * - Fallback to LatexEngine (QuickJS) on WebView failure
 * - Errors: WEBVIEW_UNSUPPORTED, JS_TIMEOUT, DECODE_FAILED, JS_ERROR:<msg>
 */
class LatexWebViewRenderer(private val activity: Activity) {

    companion object {
        private const val TAG = "LatexRender"
        private const val WARMUP_POLL_INTERVAL_MS = 200L
        private const val WARMUP_MAX_ATTEMPTS = 15 // 3 seconds
        private const val RENDER_TIMEOUT_MS = 3000L
        private const val MAX_CACHE_ENTRIES = 60
        private const val DISK_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    private var webView: WebView? = null
    private var container: ViewGroup? = null
    private var isReady = false
    private var pageLoaded = false
    private var pageLoadFailed = false
    private var warmUpCallback: ((Boolean) -> Unit)? = null
    private var warmUpAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private val renderQueue = ArrayDeque<RenderRequest>()
    private var isProcessing = false
    private val sequenceCounter = AtomicInteger(0)
    private val lruCache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                // Do NOT recycle bitmap, let GC handle it
                Log.d(TAG, "Bitmap cache evicted (no recycle)")
            }
        }
    }

    // Disk cache for cold-start reuse
    private val diskDir: File by lazy { File(activity.cacheDir, "latex_cache").apply { mkdirs() } }

    private fun diskKey(latex: String, heightPx: Int, color: Int): String {
        val md = MessageDigest.getInstance("SHA-1")
        val h = md.digest("$latex|$heightPx|$color".toByteArray()).joinToString("") { "%02x".format(it) }
        return "$h-$heightPx.png"
    }

    // Coroutine scope for background bitmap decoding
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)

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
     * Attach WebView to a container ViewGroup.
     * Call this once from Compose via AndroidView.
     */
    fun attach(container: ViewGroup) {
        this.container = container
        val wv = WebView(activity)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            // Disable zoom, we control scaling via heightPx
            builtInZoomControls = false
            displayZoomControls = false
            // Transparent background
            setSupportZoom(false)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith("file:///android_asset/latex/mathjax_render.html") == true) {
                    pageLoaded = true
                    Log.d(TAG, "mathjax_render.html loaded")
                    startWarmUpPolling()
                    // Clean old disk cache files on startup
                    cleanOldDiskCache()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e(TAG, "WebView load error: ${error?.description}")
                pageLoadFailed = true
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                Log.e(TAG, "WebView http error: ${errorResponse?.statusCode} ${request?.url}")
                pageLoadFailed = true
            }
        }
        webView = wv
        container.addView(wv)
        wv.loadUrl("file:///android_asset/latex/mathjax_render.html")
        Log.d(TAG, "WebView attached and loading mathjax_render.html")
    }

    /**
     * Warm up WebView: poll isReady() until MathJax is initialized.
     * Callback invoked on UI thread with success/failure.
     */
    fun warmUp(onReady: (Boolean) -> Unit) {
        warmUpCallback = onReady
        warmUpAttempts = 0
        if (isReady) {
            onReady(true)
        } else {
            startWarmUpPolling()
        }
    }

    private fun startWarmUpPolling() {
        warmUpAttempts++
        if (warmUpAttempts > WARMUP_MAX_ATTEMPTS) {
            Log.w(TAG, "Warm-up timeout after ${WARMUP_MAX_ATTEMPTS} attempts")
            warmUpCallback?.invoke(false)
            warmUpCallback = null
            return
        }
        if (pageLoadFailed) {
            Log.e(TAG, "Page load failed, aborting warm-up")
            warmUpCallback?.invoke(false)
            warmUpCallback = null
            return
        }
        if (!pageLoaded) {
            // Page not loaded yet, wait a bit and retry
            handler.postDelayed({ startWarmUpPolling() }, WARMUP_POLL_INTERVAL_MS)
            return
        }
        webView?.evaluateJavascript("window.isReady && window.isReady()") { value ->
            if (destroyed) {
                warmUpCallback?.invoke(false)
                warmUpCallback = null
                return@evaluateJavascript
            }
            val v = value?.trim()?.trim('"')
            if (v == "true") {
                isReady = true
                Log.d(TAG, "WebView warm-up complete, MathJax ready")
                warmUpCallback?.invoke(true)
                warmUpCallback = null
                processQueue()
            } else {
                handler.postDelayed({ startWarmUpPolling() }, WARMUP_POLL_INTERVAL_MS)
            }
        }
    }

    private var destroyed = false

    /**
     * Render a single LaTeX formula to Bitmap.
     * Queued and processed serially.
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

        // 2) Disk cache
        val diskFile = File(diskDir, diskKey(latex, heightPx, color))
        if (diskFile.exists() && diskFile.length() > 0) {
            Log.d(TAG, "Disk cache hit for: ${latex.take(40)}")
            Thread {
                val bmp = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
                handler.post {
                    if (bmp != null && !destroyed) {
                        lruCache.put(cacheKey, bmp)
                        onResult(bmp, null)
                    } else {
                        // Disk file corrupted, fall through to render
                        enqueueRender(latex, heightPx, color, onResult, cacheKey)
                    }
                }
            }.start()
            return
        }

        // 3) Enqueue for WebView rendering
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
     * Only one evaluateJavascript in flight at a time.
     */
    private fun processQueue() {
        if (destroyed || isProcessing || renderQueue.isEmpty() || !isReady) return
        val request = renderQueue.peekFirst() ?: return
        isProcessing = true

        val jsonItems = JSONArray().put(
            JSONObject().apply {
                put("id", request.sequenceId)
                put("tex", request.latex)
                put("heightPx", request.heightPx)
                put("color", String.format("#%06X", (0xFFFFFF and request.color)))
            }
        ).toString()

        Log.d(TAG, "Sending renderBatch seq=${request.sequenceId} tex=${request.latex.take(50)}")

        // Escape JSON for JS string literal: escape backslashes first, then single quotes
        val payload = jsonItems
            .replace("\\", "\\\\")
            .replace("'", "\\'")

        // 3-second timeout per request - track the Runnable to cancel later
        val timeoutRunnable = Runnable {
            if (!destroyed && isProcessing && renderQueue.isNotEmpty() && renderQueue.peekFirst()?.sequenceId == request.sequenceId) {
                Log.w(TAG, "Render timeout seq=${request.sequenceId}")
                handleJsResult(request.sequenceId, null, "JS_TIMEOUT")
            }
        }
        handler.postDelayed(timeoutRunnable, RENDER_TIMEOUT_MS)

        webView?.evaluateJavascript("window.startBatch('$payload')") { jsResult ->
            if (!destroyed) {
                handler.removeCallbacks(timeoutRunnable)
                // jsResult is empty string; actual result retrieved via getResult()
                pollJsResult(request)
            }
        }
    }

    /**
     * Poll for JS result via getResult() until we get our sequenceId or timeout.
     */
    private fun pollJsResult(request: RenderRequest) {
        val startTime = System.currentTimeMillis()
        fun doPoll() {
            if (destroyed || System.currentTimeMillis() - startTime > RENDER_TIMEOUT_MS) {
                if (!destroyed) {
                    Log.w(TAG, "Poll timeout seq=${request.sequenceId}")
                    handleJsResult(request.sequenceId, null, "JS_TIMEOUT")
                }
                return
            }
            webView?.evaluateJavascript("window.getResult()") { resultStr ->
                if (destroyed) return@evaluateJavascript
                if (resultStr != null && resultStr != "''" && resultStr != "\"\"" && resultStr.length > 2) {
                    try {
                        // resultStr is a JSON string like "[{\"id\":1,\"pngBase64\":\"...\"}]"
                        // Need to unescape it
                        val unescaped = resultStr
                            .replace("\\\"", "\"")
                        val results = JSONArray(unescaped)
                        var found = false
                        for (i in 0 until results.length()) {
                            val obj = results.getJSONObject(i)
                            val id = obj.getInt("id")
                            if (id == request.sequenceId) {
                                if (obj.has("pngBase64")) {
                                    handleJsResult(id, obj.getString("pngBase64"), null)
                                } else if (obj.has("error")) {
                                    handleJsResult(id, null, "JS_ERROR:${obj.getString("error")}")
                                }
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            // Our ID not in results yet, keep polling
                            handler.postDelayed({ doPoll() }, 50)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Parse JS result failed: ${e.message}, raw=$resultStr")
                        handler.postDelayed({ doPoll() }, 50)
                    }
                } else {
                    // No result yet, keep polling
                    handler.postDelayed({ doPoll() }, 50)
                }
            }
        }
        doPoll()
    }

    /**
     * Handle JS result: decode base64 → Bitmap on IO thread, callback on UI thread.
     */
    private fun handleJsResult(sequenceId: Int, pngBase64: String?, error: String?) {
        isProcessing = false
        val request = renderQueue.pollFirst()
        if (request == null || request.sequenceId != sequenceId) {
            Log.w(TAG, "Sequence mismatch: expected ${request?.sequenceId}, got $sequenceId")
            // Try to find and complete the right request
            var foundRequest: RenderRequest? = null
            val iterator = renderQueue.iterator()
            while (iterator.hasNext()) {
                val req = iterator.next()
                if (req.sequenceId == sequenceId) {
                    foundRequest = req
                    iterator.remove()
                    break
                }
            }
            foundRequest?.let { req ->
                deliverResult(req, pngBase64, error)
            }
            processQueue()
            return
        }

        deliverResult(request, pngBase64, error)
        processQueue()
    }

    private fun deliverResult(request: RenderRequest, pngBase64: String?, error: String?) {
        if (error != null) {
            Log.w(TAG, "Render failed seq=${request.sequenceId} error=$error")
            handler.post { request.onResult(null, error) }
            return
        }

        pngBase64?.let { b64 ->
            scope.launch {
                try {
                    val bitmap = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                        try {
                            val bytes = Base64.decode(b64, Base64.NO_WRAP)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            bmp
                        } catch (e: Exception) {
                            Log.e(TAG, "Decode failed seq=${request.sequenceId}", e)
                            null
                        }
                    }
                    if (!destroyed) {
                        handler.post {
                            if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                                val cacheKey = "${request.latex}|${request.heightPx}|${request.color}"
                                lruCache.put(cacheKey, bitmap)
                                // Write to disk cache (async, non-blocking)
                                writeDiskCache(bitmap, cacheKey, request.latex, request.heightPx, request.color)
                                request.onResult(bitmap, null)
                            } else {
                                request.onResult(null, "DECODE_FAILED")
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Expected on shutdown, ignore
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in deliverResult", e)
                    if (!destroyed) {
                        handler.post { request.onResult(null, "UNEXPECTED_ERROR") }
                    }
                }
            }
        } ?: run {
            if (!destroyed) {
                request.onResult(null, "DECODE_FAILED")
            }
        }
    }

    private fun writeDiskCache(bitmap: Bitmap, cacheKey: String, latex: String, heightPx: Int, color: Int) {
        Thread {
            runCatching {
                val name = diskKey(latex, heightPx, color)
                val dst = File(diskDir, name)
                val tmp = File(diskDir, "${name}.tmp")
                FileOutputStream(tmp).use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                tmp.renameTo(dst)
                Log.d(TAG, "Disk cache written: $name")
            }
        }.start()
    }

    private fun cleanOldDiskCache() {
        Thread {
            runCatching {
                val now = System.currentTimeMillis()
                diskDir.listFiles()?.forEach { file ->
                    if (file.isFile && (now - file.lastModified()) > DISK_CACHE_MAX_AGE_MS) {
                        file.delete()
                        Log.d(TAG, "Deleted old disk cache: ${file.name}")
                    }
                }
            }
        }.start()
    }

    /**
     * Clear disk cache (for debugging/testing).
     */
    fun clearDiskCache() {
        Thread {
            runCatching {
                diskDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
                Log.d(TAG, "Disk cache cleared")
            }
        }.start()
    }

    /**
     * Release WebView resources.
     * Must call from onDestroy / DisposableEffect onDispose.
     */
    fun release() {
        destroyed = true
        scope.coroutineContext.cancel()
        handler.removeCallbacksAndMessages(null)
        val wv = webView
        webView = null
        wv?.apply {
            destroy()
            container?.removeAllViews()
        }
        container = null
        isReady = false
        pageLoaded = false
        pageLoadFailed = false
        renderQueue.clear()
        isProcessing = false
        lruCache.evictAll()
        Log.d(TAG, "WebViewRenderer released")
    }

    /** Check if WebView is ready for rendering. */
    fun isReady(): Boolean = isReady
}