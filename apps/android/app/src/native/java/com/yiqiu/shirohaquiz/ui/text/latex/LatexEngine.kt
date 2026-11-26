package com.yiqiu.shirohaquiz.ui.text.latex

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.taoweiji.quickjs.QuickJSContext
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
 */
object LatexEngine {
    private const val TAG = "LatexEngine"
    private const val ASSET_PATH = "latex/mathjax.bundle.js"
    private const val RENDER_TIMEOUT_MS = 5000L
    private const val MAX_CACHE_ENTRIES = 300

    @Volatile
    private var context: QuickJSContext? = null
    @Volatile
    private var initialized = false
    @Volatile
    private var initError: String? = null
    @Volatile
    private var initTimeMs: Long = -1

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
                val ctx = QuickJSContext.create()
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
                ctx.evaluate(jsCode)
                context = ctx
                initTimeMs = System.currentTimeMillis() - start
                initialized = true
                Log.i(TAG, "LaTeX engine initialized in ${initTimeMs} ms")
            } catch (e: Exception) {
                initError = e.message ?: e.javaClass.simpleName
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

    /**
     * Render LaTeX to SVG string (blocking, with timeout).
     * Returns null on failure, timeout, or if engine not ready.
     * Uses LRU cache (key = raw LaTeX string).
     */
    fun renderSvgBlocking(latex: String): String? {
        if (!isReady()) {
            Log.w(TAG, "Engine not ready: initialized=$initialized, context=${context != null}, error=$initError")
            return null
        }
        // Check cache first
        cache.get(latex)?.let { return it }

        val future: Future<String?> = executor.submit(Callable {
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
                val result = ctx.evaluate(js).toString()
                if (result.startsWith("ERR:")) {
                    Log.w(TAG, "MathJax error: ${result.substring(4)}")
                    null
                } else {
                    // Basic validation: should be SVG
                    if (!result.trim().startsWith("<svg")) {
                        Log.w(TAG, "Unexpected output (not SVG): ${result.take(100)}")
                        null
                    } else {
                        result
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Render exception: ${e.message}")
                null
            }
        })

        return try {
            future.get(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)?.also { svg ->
                if (svg != null) cache.put(latex, svg)
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            Log.w(TAG, "Render timeout (>${RENDER_TIMEOUT_MS}ms) for: ${latex.take(50)}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Render future exception: ${e.message}")
            null
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
        context?.dispose()
        context = null
        initialized = false
        initError = null
        initTimeMs = -1
        cache.evictAll()
    }
}