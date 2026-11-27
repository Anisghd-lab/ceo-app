package com.foundryos.app.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class WebViewReadResult(
    val ok: Boolean,
    val finalUrl: String,
    val title: String,
    val text: String,
    val linksJson: String = "[]",
    val httpStatusHint: Int = 200,
    val error: String = "",
)

/**
 * Renders authenticated web pages inside an off-screen Android WebView sharing
 * CEO Browser's exact CookieManager, DOM Storage, CSRF/Session tokens, and JS runtime.
 * Fixes 404/SPA/cookie failures when agents access web-session apps (Kittl, Canva, Notion, Figma, etc.).
 */
class BrowserSessionReader(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun readAuthenticatedPage(url: String, timeoutMs: Long = 16_000L): WebViewReadResult =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                var wv: WebView? = null
                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().flush()
                    val finished = CompletableDeferred<Int>()
                    var mainFrameHttpError = 200

                    wv = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                if (request?.isForMainFrame == true && errorResponse != null) {
                                    mainFrameHttpError = errorResponse.statusCode
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true && !finished.isCompleted) {
                                    mainFrameHttpError = 500
                                    finished.complete(500)
                                }
                            }

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                if (!finished.isCompleted) finished.complete(mainFrameHttpError)
                            }
                        }
                        loadUrl(url)
                    }

                    finished.await()
                    // Allow client-side React/Next.js SPA hydration after page load
                    delay(900)

                    val jsResult = CompletableDeferred<String>()
                    val extractScript = """
                        (function() {
                          try {
                            var links = Array.prototype.slice.call(document.querySelectorAll('a[href]')).slice(0, 20).map(function(a) {
                              return {text: (a.innerText || '').trim().slice(0, 80), href: a.href};
                            }).filter(function(x) { return x.href && x.href.indexOf('http') === 0; });
                            var txt = (document.body && (document.body.innerText || document.body.textContent) || '').replace(/\s+/g, ' ').trim();
                            return JSON.stringify({
                              title: document.title || '',
                              finalUrl: location.href || '',
                              text: txt.slice(0, 5500),
                              links: links
                            });
                          } catch (e) {
                            return JSON.stringify({title: document.title || '', finalUrl: location.href || '', text: '', links: []});
                          }
                        })();
                    """.trimIndent()

                    wv.evaluateJavascript(extractScript) { raw ->
                        if (!jsResult.isCompleted) jsResult.complete(raw ?: "{}")
                    }
                    val rawWrapped = withTimeoutOrNull(4_000L) { jsResult.await() } ?: "{}"
                    val decoded = decodeEvaluateJsString(rawWrapped)
                    val obj = runCatching { JSONObject(decoded) }.getOrDefault(JSONObject())
                    val finalUrl = obj.optString("finalUrl").ifBlank { url }
                    val title = obj.optString("title")
                    val text = obj.optString("text")
                    val links = obj.optJSONArray("links")?.toString() ?: "[]"
                    val looks404 = mainFrameHttpError == 404 ||
                        (title.contains("404", ignoreCase = true) && text.length < 400)

                    WebViewReadResult(
                        ok = !looks404 && mainFrameHttpError < 400 && text.isNotBlank(),
                        finalUrl = finalUrl,
                        title = title,
                        text = text,
                        linksJson = links,
                        httpStatusHint = if (looks404) 404 else mainFrameHttpError,
                    )
                } catch (e: Exception) {
                    WebViewReadResult(false, url, "", "", httpStatusHint = 500, error = e.message ?: "WebView read failed")
                } finally {
                    runCatching {
                        wv?.stopLoading()
                        wv?.destroy()
                    }
                }
            }
        } ?: WebViewReadResult(false, url, "", "", httpStatusHint = 408, error = "WebView read timed out")

    private fun decodeEvaluateJsString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return runCatching { JSONObject("{\"v\":$trimmed}").getString("v") }.getOrDefault(trimmed)
        }
        return trimmed
    }
}
