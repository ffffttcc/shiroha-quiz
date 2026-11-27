package com.yiqiu.shirohaquiz.ui.text.latex

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class LatexWebViewRenderer(private val activity: Activity) {

    companion object {
        private const val TAG = "LatexRender"
        private const val PAGE = "file:///android_asset/latex/mathjax_render.html"
        private const val READY_TIMEOUT_MS = 15_000L
        private const val RENDER_TIMEOUT_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var container: ViewGroup? = null
    private var ready = false
    private var destroyed = false
    private val seq = AtomicInteger(1)
    private val queue = ArrayDeque<Task>()

    private data class Task(
        val id: Int,
        val latex: String,
        val heightPx: Int,
        val colorArgb: Int,
        val onResult: (Bitmap?, String?) -> Unit
    )

    fun attach(host: ViewGroup) {
        if (webView != null) return
        webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            layoutParams = ViewGroup.LayoutParams(1, 1)
            webViewClient = WebViewClient()
        }
        container = host
        host.addView(webView!!, ViewGroup.LayoutParams(1, 1))
        webView!!.loadUrl(PAGE)
    }

    fun warmUp(onReady: (Boolean) -> Unit) {
        if (container == null) {
            container = activity.window.decorView as ViewGroup
        }
        if (webView == null) {
            attach(container!!)
        }
        val start = System.currentTimeMillis()
        fun poll() {
            if (destroyed || webView == null) {
                onReady(false)
                return
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > READY_TIMEOUT_MS) {
                Log.d(TAG, "warmUp timeout after ${elapsed}ms")
                onReady(false)
                return
            }
            main.post {
                webView!!.evaluateJavascript("window.isReady && window.isReady()") { value ->
                    if (destroyed) {
                        onReady(false)
                        return@evaluateJavascript
                    }
                    if ("true" == value.trim()) {
                        ready = true
                        Log.d(TAG, "ready in ${System.currentTimeMillis() - start}ms")
                        onReady(true)
                    } else {
                        main.postDelayed({ poll() }, 200)
                    }
                }
            }
        }
        poll()
    }

    fun render(latex: String, heightPx: Int, color: Int, onResult: (Bitmap?, String?) -> Unit) {
        if (destroyed) {
            onResult(null, "WEBVIEW_DESTROYED")
            return
        }
        if (!ready) {
            onResult(null, "WEBVIEW_NOT_READY")
            return
        }
        val id = seq.getAndIncrement()
        val task = Task(id, latex, heightPx, color, onResult)
        queue.add(task)
        pump()
    }

    private fun pump() {
        if (destroyed || queue.isEmpty()) return
        val task = queue.peek()
        if (task == null) return
        main.post {
            if (destroyed || webView == null) {
                queue.removeFirst()
                task.onResult(null, "WEBVIEW_DESTROYED")
                pump()
                return
            }
            val json = try {
                val arr = JSONArray()
                val obj = JSONObject()
                obj.put("id", task.id)
                obj.put("latex", task.latex)
                obj.put("heightPx", task.heightPx)
                val colorHex = String.format("#%06X", (0xFFFFFF and task.colorArgb))
                obj.put("color", colorHex)
                arr.put(obj)
                arr.toString()
            } catch (e: Exception) {
                Log.e(TAG, "build json failed", e)
                queue.removeFirst()
                task.onResult(null, "JSON_BUILD_FAILED")
                pump()
                return
            }
            // Escape for JS string literal
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
            webView!!.evaluateJavascript("window.startBatch('$escaped')") { _ ->
                if (destroyed) return@evaluateJavascript
                pollResult(task)
            }
        }
    }

    private fun pollResult(task: Task) {
        val start = System.currentTimeMillis()
        fun doPoll() {
            if (destroyed || webView == null) {
                queue.removeFirst()
                task.onResult(null, "WEBVIEW_DESTROYED")
                main.post { pump() }
                return
            }
            if (System.currentTimeMillis() - start > RENDER_TIMEOUT_MS) {
                queue.removeFirst()
                task.onResult(null, "JS_TIMEOUT")
                main.post { pump() }
                return
            }
            main.post {
                if (destroyed || webView == null) return@evaluateJavascript
                webView!!.evaluateJavascript("window.getResult(${task.id})") { value ->
                    if (destroyed) return@evaluateJavascript
                    val str = value.trim()
                    if (str.isEmpty() || str == "\"\"" || str == "''") {
                        main.postDelayed({ doPoll() }, 50)
                        return@evaluateJavascript
                    }
                    // Got result
                    queue.removeFirst()
                    try {
                        val obj = JSONObject(str)
                        if (obj.has("error")) {
                            task.onResult(null, obj.getString("error"))
                        } else if (obj.has("pngB64")) {
                            val b64 = obj.getString("pngB64")
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                task.onResult(bmp, null)
                            } else {
                                task.onResult(null, "DECODE_FAILED")
                            }
                        } else {
                            task.onResult(null, "UNEXPECTED_RESULT")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "parse result failed", e)
                        task.onResult(null, "PARSE_FAILED: ${e.message}")
                    }
                    main.post { pump() }
                }
            }
        }
        doPoll()
    }

    fun release() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        webView?.let {
            it.loadUrl("about:blank")
            it.removeAllViews()
            it.destroy()
        }
        container?.removeAllViews()
        webView = null
        queue.clear()
    }
}