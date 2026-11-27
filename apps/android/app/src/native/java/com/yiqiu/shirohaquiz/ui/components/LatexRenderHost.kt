package com.yiqiu.shirohaquiz.ui.components

import android.app.Activity
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yiqiu.shirohaquiz.ui.text.latex.LatexRenderSwitch
import com.yiqiu.shirohaquiz.ui.text.latex.LatexWebViewRenderer
import com.yiqiu.shirohaquiz.ui.text.latex.LocalLatexRenderer
import kotlin.runCatching

@Composable
fun LatexRenderHost(content: @Composable () -> Unit) {
    if (!LatexRenderSwitch.enabled) { content(); return }
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    if (activity == null) { content(); return }

    var renderer by remember { mutableStateOf<LatexWebViewRenderer?>(null) }
    Box(Modifier.fillMaxWidth()) {
        AndroidView(
            factory = { c ->
                FrameLayout(c).also { host ->
                    runCatching {
                        val r = LatexWebViewRenderer(activity)
                        r.attach(host)
                        r.warmUp { }
                        renderer = r
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(1.dp)
        )
        DisposableEffect(Unit) { onDispose { renderer?.release() } }
        CompositionLocalProvider(LocalLatexRenderer provides renderer) { content() }
    }
}