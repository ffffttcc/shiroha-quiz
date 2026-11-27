package com.yiqiu.shirohaquiz.ui.text.latex

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.quickjs.JSContext
import com.quickjs.QuickJS
import java.io.InputStreamReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * LaTeX rendering engine using QuickJS + MathJax.
 * Singleton; init(Context) must be called once before use.
 * All JS execution happens on a dedicated single-thread executor because QuickJS is not thread-safe.
 * Returns SVG string; bitmap rendering is delegated to LatexSvgRenderer.
 */
object LatexEngine {
    private const val TAG = "LatexEngine"
    private const val ASSET_PATH = "latex/mathjax.bundle.js"
    private const val RENDER_TIMEOUT_MS = 5000L
    private const val MAX_CACHE_ENTRIES = 300

    @Volatile
    private var context: JSContext? = null
    @Volatile
    private var runtime: QuickJS? = null
    @Volatile
    private var initialized = false
    @Volatile
    private var initError: String? = null
    @Volatile
    private var initTimeMs: Long = -1
    @Volatile
    private var lastRenderError: String? = null

    private val executor = Executors.newSingleThreadExecutor({ r ->
        Thread(r, "LatexEngine-JS").apply { isDaemon = true }
    })

    private val cache = object : LruCache<String, String>(MAX_CACHE_ENTRIES) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: String, newValue: String?) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            if (evicted) Log.d(TAG, "Cache evicted: ${key.take(50)}...")
        }
    }

    /** Initialize the engine. Must be called once from Application or first Activity. */
    fun init(appContext: Context) {
        if (initialized) return
        val start = System.currentTimeMillis()
        executor.execute {
            try {
                val rt = QuickJS.createRuntime()
                val ctx = rt.createContext()

                // Polyfill for MathJax bundle (node-verified minimal set)
                val polyfill = """
                    if (typeof globalThis === 'undefined') { var globalThis = this; }
                    if (typeof require === 'undefined') {
                        var require = function (id) {
                            if (id === 'path') return { resolve: function () { return Array.prototype.join.call(arguments, '/'); } };
                            return {};
                        };
                    }
                    if (typeof __dirname === 'undefined') { var __dirname = '/tmp'; }
                """.trimIndent()
                ctx.executeScript(polyfill, "polyfill.js")

                // Load MathJax bundle from assets
                val inputStream = appContext.assets.open(ASSET_PATH)
                val reader = InputStreamReader(inputStream)
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                var len: Int
                while (reader.read(buffer).also { len = it } != -1) {
                    sb.append(buffer, 0, len)
                }
                reader.close()
                val jsCode = sb.toString()

                // Evaluate the bundle (defines globalThis.renderLatex)
                try {
                    ctx.executeScript(jsCode, "mathjax.bundle.js")
                } catch (e: Exception) {
                    throw RuntimeException("bundle exec failed: ${e.message}")
                }

                // Verify bundle exported renderLatex
                val check = ctx.executeScript("typeof globalThis.renderLatex", "check.js").toString()
                if (check != "function") {
                    throw RuntimeException("bundle 未导出 renderLatex（typeof=$check）")
                }

                runtime = rt
                context = ctx
                initTimeMs = System.currentTimeMillis() - start
                initialized = true
                Log.i(TAG, "LaTeX engine initialized in ${initTimeMs} ms")
            } catch (e: Exception) {
                // Clean message: strip redundant "ReferenceError: ReferenceError;" prefix if present
                var msg = e.message ?: e.javaClass.simpleName
                if (msg.startsWith("ReferenceError: ReferenceError;")) {
                    msg = msg.substringAfter("ReferenceError: ReferenceError;").trim()
                }
                initError = msg
                Log.e(TAG, "Failed to initialize LaTeX engine", e)
            }
        }
    }

    /** Returns true if init() has completed successfully. */
    fun isReady(): Boolean = initialized && context != null && initError == null

    /** Returns the initialization time in milliseconds, or -1 if not initialized. */
    fun getInitTimeMs(): Long = initTimeMs

    /** Returns the initialization error message, or null if successful. */
    fun getInitError(): String? = initError

    /** Returns the last render error message, or null if last render succeeded. */
    fun getLastRenderError(): String? = lastRenderError

    /**
     * Render LaTeX to SVG string (blocking, with timeout).
     * Returns SVG string on success, or error message string on failure (prefixed with "ERR:").
     * Uses LRU cache (key = raw LaTeX string).
     */
    fun renderSvgBlocking(latex: String): String? {
        if (!isReady()) {
            Log.w(TAG, "Engine not ready: initialized=$initialized, context=${context != null}, error=$initError")
            return "ERR:Engine not ready: $initError"
        }
        // Check cache first
        cache.get(latex)?.let { return it }

        val future: Future<String?> = executor.submit(Callable<String?> {
            val ctx = context!!
            try {
                // Escape for JS string literal
                val escaped = latex
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                val js = "globalThis.renderLatex(\"$escaped\")"
                val resultObj = ctx.executeScript(js, "render.js")
                val retClass = resultObj?.javaClass?.name ?: "null"
                val result = resultObj?.toString() ?: "null"
                Log.d(TAG, "render retClass=$retClass ret=${result.take(200)}")

                if (result.startsWith("ERR:")) {
                    Log.w(TAG, "render fail tex=$latex ret=$result")
                    return@Callable result
                } else {
                    // Basic validation: should be SVG
                    if (!result.trim().startsWith("<svg")) {
                        val err = "Unexpected output (not SVG): ${result.take(200)}"
                        Log.w(TAG, "render fail tex=$latex ret=$result")
                        return@Callable "ERR:$err"
                    } else {
                        result
                    }
                }
            } catch (e: Exception) {
                val err = "Render exception: ${e.message}"
                Log.w(TAG, "render fail tex=$latex err=$err", e)
                return@Callable "ERR:$err"
            }
        })

        return try {
            future.get(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)?.also { result ->
                if (result != null) {
                    if (result.startsWith("ERR:")) {
                        lastRenderError = result.substring(4)
                    } else {
                        lastRenderError = null
                        cache.put(latex, result)
                    }
                    result
                } else {
                    lastRenderError = "Unknown error"
                    "ERR:Unknown error"
                }
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            val err = "Render timeout (>${RENDER_TIMEOUT_MS}ms) for: ${latex.take(50)}"
            Log.w(TAG, err)
            lastRenderError = err
            "ERR:$err"
        } catch (e: Exception) {
            val err = "Render future exception: ${e.message}"
            Log.w(TAG, err, e)
            lastRenderError = err
            "ERR:$err"
        }
    }

    /**
     * Render LaTeX to SVG string (non-blocking Result wrapper).
     * Returns Result.success(svgString) or Result.failure(exception).
     * Uses LRU cache (key = raw LaTeX string).
     */
    fun renderSvgString(latex: String): Result<String> {
        return runCatching {
            val result = renderSvgBlocking(latex)
            if (result == null || result.startsWith("ERR:")) {
                throw RuntimeException(result ?: "Unknown error")
            }
            result
        }
    }

    /** Clear the LRU cache. */
    fun clearCache() {
        cache.evictAll()
        Log.d(TAG, "Cache cleared")
    }

    /** Shutdown the engine (for testing/cleanup). */
    fun shutdown() {
        executor.shutdownNow()
        context?.close()
        context = null
        runtime?.close()
        runtime = null
        initialized = false
        initError = null
        initTimeMs = -1
        cache.evictAll()
    }
}