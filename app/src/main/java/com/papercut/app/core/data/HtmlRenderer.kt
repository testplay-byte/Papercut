package com.papercut.app.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * HTML -> high-res PNG export.
 *
 * The old app called webView.draw() after a fixed 3s+1s sleep (blank captures on
 * slow devices, wasted 4s on fast ones). This version waits for the REAL render
 * signal — onPageFinished, then one Choreographer frame so layout/paint actually
 * happened — with a hard timeout as the safety net. OOM falls back to half-scale.
 */
class HtmlRenderer(private val context: Context) {

    companion object {
        private const val TARGET_WIDTH_PX = 2480 // A4 @ ~300dpi
        private const val MIN_HEIGHT_PX = 3508
        private const val RENDER_TIMEOUT_MS = 15_000L
    }

    /** True while an export is running — the UI shows progress from this. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Render [html] to a bitmap at A4 width. Returns null on timeout or OOM.
     * Safe to call from any thread; WebView work is marshalled to Main inside.
     */
    suspend fun render(html: String): Bitmap? {
        _busy.value = true
        return try {
            withContext(Dispatchers.Main) {
                renderOnce(html, TARGET_WIDTH_PX)
                    ?: renderOnce(html, TARGET_WIDTH_PX / 2) // half-scale OOM fallback
            }
        } finally {
            _busy.value = false
        }
    }

    private suspend fun renderOnce(html: String, widthPx: Int): Bitmap? {
        val webView = WebView(context).apply {
            setBackgroundColor(Color.WHITE)
            // digitized pages use MathJax -> JS must run; loadAndWait waits for it
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return try {
            val loaded = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                loadAndWait(webView, html)
            }
            if (!loaded) return null

            webView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val height = (webView.measuredHeight * (TARGET_WIDTH_PX.toFloat() / widthPx))
                .toInt()
                .coerceAtLeast(if (widthPx == TARGET_WIDTH_PX) MIN_HEIGHT_PX else MIN_HEIGHT_PX / 2)
            webView.layout(0, 0, widthPx, height)

            val bitmap = try {
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return null
            }
            webView.draw(Canvas(bitmap))
            bitmap
        } finally {
            webView.destroy()
        }
    }

    /**
     * Resolve when: page loaded -> MathJax (if present) finished typesetting ->
     * one real Choreographer frame passed. All event-driven, no blind sleeps;
     * the caller's withTimeoutOrNull is the only bound.
     */
    private suspend fun loadAndWait(webView: WebView, html: String): Boolean {
        suspendCancellableCoroutine { cont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = cont.resume(Unit)
            }
            cont.invokeOnCancellation { webView.stopLoading() }
            webView.loadDataWithBaseURL(null, wrapForA4(html), "text/html", "UTF-8", null)
        }

        // Arm a done-flag on MathJax's startup promise, then poll the flag.
        // Non-MathJax pages are idle instantly; pages with math wait properly.
        webView.evaluateJavascript(
            """
            (function(){
              if (typeof MathJax === 'undefined') return 'idle';
              if (!MathJax.startup) return 'busy';
              MathJax.startup.promise.then(function(){ window.__mjDone = true; });
              return 'armed';
            })()
            """.trimIndent(),
        ) { /* result only used to know we tried arming */ }

        withTimeoutOrNull(RENDER_TIMEOUT_MS / 2) {
            while (!isMathJaxDone(webView)) delay(150)
        }

        // One more frame so final layout/paint state is what we snapshot.
        suspendCancellableCoroutine<Unit> { cont ->
            val cb = Choreographer.FrameCallback { cont.resume(Unit) }
            Choreographer.getInstance().postFrameCallback(cb)
            cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(cb) }
        }
        return true
    }

    private suspend fun isMathJaxDone(webView: WebView): Boolean =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(
                "(typeof MathJax === 'undefined') || !!window.__mjDone",
            ) { value -> cont.resume(value == "true") }
        }

    /** Wrap fragment/classic HTML in a full document with our print canvas style. */
    private fun wrapForA4(html: String): String =
        if (html.contains("<html", ignoreCase = true)) html else
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<style>html,body{margin:0;background:#1a1a1a;}</style>" +
                "</head><body>$html</body></html>"

    /** Fire-and-collect helper for UI flows that just want the PNG later. */
    fun requestExport(html: String, onResult: (Bitmap?) -> Unit) {
        scope.launch { onResult(render(html)) }
    }
}
