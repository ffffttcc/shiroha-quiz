package com.yiqiu.shirohaquiz.ui.text.latex

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * LaTeX rendering engine stub (JLaTeXMath is now primary).
 * This is a no-op stub kept for API compatibility.
 * Actual rendering is done by JLatexMathRenderer.
 */
object LatexEngine {
    private const val TAG = "LatexEngine"
    private const val RENDER_TIMEOUT_MS = 5000L
    private const val MAX_CACHE_ENTRIES = 300

    @Volatile
    private var initialized = false
    @Volatile
    private var initError: String? = "QuickJS removed; use JLatexMathRenderer instead"
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

    /** Initialize the engine (no-op). */
    fun init(appContext: android.content.Context) {
        if (initialized) return
        initialized = true
        initTimeMs = 0
        Log.i(TAG, "LaTeX engine stub initialized (JLaTeXMath is primary)")
    }

    /** Returns true if init() has completed successfully. */
    fun isReady(): Boolean = false // QuickJS removed; use JLaTeXMathRenderer instead

    /** Returns the initialization time in milliseconds, or -1 if not initialized. */
    fun getInitTimeMs(): Long = initTimeMs

    /** Returns the initialization error message, or null if successful. */
    fun getInitError(): String? = initError

    /** Returns the last render error message, or null if last render succeeded. */
    fun getLastRenderError(): String? = lastRenderError

    /**
     * Render LaTeX to SVG string (blocking, with timeout).
     * Returns error message since QuickJS is removed.
     */
    fun renderSvgBlocking(latex: String): String? {
        return "ERR:QuickJS removed; use JLatexMathRenderer instead"
    }

    /**
     * Render LaTeX to SVG string (non-blocking Result wrapper).
     */
    fun renderSvgString(latex: String): Result<String> {
        return Result.failure(RuntimeException("QuickJS removed; use JLatexMathRenderer instead"))
    }

    /** Clear the LRU cache. */
    fun clearCache() {
        // No-op
        Log.d(TAG, "Cache cleared (no-op)")
    }

    /** Shutdown the engine (no-op). */
    fun shutdown() {
        // No-op
    }
}