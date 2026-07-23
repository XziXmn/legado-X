package io.legado.app.model.chapterComment

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppConst
import io.legado.app.help.http.CookieManager as SourceCookieManager
import io.legado.app.help.http.CookieStore
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * SOURCE_SCOPED WebView wiring for chapter-comment pages.
 * No JS bridges; auth headers only on the pinned origin.
 */
class SourceScopedWebController {

    private var webView: WebView? = null
    private var authenticatedOrigin: HttpOrigin? = null
    private var httpClient: OkHttpClient? = null
    private var sourceHeaders: Map<String, String> = emptyMap()
    private val initialDocumentGate = SourceScopedInitialDocumentGate()
    private var needClearHistory = true

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        this.webView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.removeJavascriptInterface("basic")
        webView.removeJavascriptInterface("java")
        webView.removeJavascriptInterface("source")
        webView.removeJavascriptInterface("cache")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = Client()
    }

    fun load(
        finalUrl: String,
        html: String,
        headers: Map<String, String>,
        networkContext: SourceScopedNetworkContext,
    ) {
        val view = webView ?: return
        authenticatedOrigin = networkContext.origin
        httpClient = newSourceScopedHttpClient(networkContext)
        sourceHeaders = LinkedHashMap(headers)
        needClearHistory = true
        headers.entries.firstOrNull { it.key.equals(AppConst.UA_NAME, true) }
            ?.value
            ?.let { view.settings.userAgentString = it }
        val body = injectSourceScopedCsp(html)
        initialDocumentGate.arm(finalUrl)
        view.loadDataWithBaseURL(finalUrl, body, "text/html", "utf-8", finalUrl)
    }

    fun loadErrorPlaceholder(baseUrl: String) {
        val view = webView ?: return
        initialDocumentGate.arm(baseUrl)
        view.loadDataWithBaseURL(
            baseUrl,
            "<html><body style=\"font-family:sans-serif;padding:24px;color:#666\">" +
                "评论页面加载失败</body></html>",
            "text/html",
            "utf-8",
            baseUrl,
        )
    }

    /** @return true if the back event was consumed (history pop). */
    fun goBackOrFalse(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        val list = view.copyBackForwardList()
        if (list.size <= 1) return false
        view.goBack()
        return true
    }

    fun detach() {
        webView?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("about:blank")
        }
        webView = null
        authenticatedOrigin = null
        httpClient = null
        sourceHeaders = emptyMap()
    }

    private fun injectSourceScopedCsp(html: String): String {
        val policy = "<meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'none'; script-src 'self' 'unsafe-inline'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; " +
            "font-src 'self' https: data:; media-src 'self' https:; connect-src 'self'; " +
            "frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'self'\">"
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head == null) {
            policy + html
        } else {
            html.substring(0, head.range.last + 1) + policy + html.substring(head.range.last + 1)
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                webView?.clearHistory()
            }
            super.onPageStarted(view, url, favicon)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val url = request?.url ?: return true
            val origin = authenticatedOrigin ?: return true
            return when (url.scheme?.lowercase()) {
                "http", "https" -> !SourceScopedRequestPolicy.canNavigate(url.toString(), origin)
                else -> true
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            handler?.cancel()
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (initialDocumentGate.consume(url, request.method, request.isForMainFrame)) {
                return super.shouldInterceptRequest(view, request)
            }
            return runBlocking(IO) { getSourceScopedResponse(request) }
        }

        private fun getSourceScopedResponse(request: WebResourceRequest): WebResourceResponse {
            val origin = authenticatedOrigin
                ?: return blockedResponse(403, "Missing authenticated origin")
            if (request.method != "GET" && request.method != "HEAD") {
                return blockedResponse(405, "Unsupported request method")
            }
            return runCatching {
                loadSourceScopedResponse(request, request.url.toString(), origin, 0)
            }.getOrElse {
                blockedResponse(502, "Source-scoped request failed")
            }
        }

        private fun loadSourceScopedResponse(
            webRequest: WebResourceRequest,
            url: String,
            origin: HttpOrigin,
            redirectCount: Int,
        ): WebResourceResponse {
            val allowed = if (webRequest.isForMainFrame) {
                SourceScopedRequestPolicy.canNavigate(url, origin)
            } else {
                SourceScopedRequestPolicy.canLoadSubresource(url, origin)
            }
            if (!allowed) return blockedResponse(403, "Blocked source-scoped request")
            val client = httpClient
                ?: return blockedResponse(403, "Missing source-scoped client")
            client.newCall(buildRequest(webRequest, url, origin)).execute().use { response ->
                if (response.code in 300..399) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        return blockedResponse(508, "Too many source-scoped redirects")
                    }
                    val location = response.header("Location")
                        ?: return blockedResponse(502, "Source-scoped redirect has no Location")
                    val nextUrl = URI(url).resolve(location).toString()
                    return loadSourceScopedResponse(webRequest, nextUrl, origin, redirectCount + 1)
                }
                return response.toWebResourceResponse(webRequest.method == "HEAD")
            }
        }

        private fun buildRequest(
            webRequest: WebResourceRequest,
            url: String,
            origin: HttpOrigin,
        ): Request {
            val builder = Request.Builder().url(url)
            SAFE_WEB_HEADERS.forEach { safeName ->
                webRequest.requestHeaders.entries.firstOrNull { it.key.equals(safeName, true) }
                    ?.value?.let { builder.header(safeName, it) }
            }
            if (SourceScopedRequestPolicy.isSameOrigin(url, origin)) {
                sourceHeaders.forEach { (name, value) ->
                    if (name.lowercase() !in BLOCKED_SOURCE_HEADERS) {
                        builder.header(name, value)
                    }
                }
                SourceCookieManager.mergeCookies(
                    CookieStore.getCookie(url),
                    sourceHeaders.entries.firstOrNull { it.key.equals("Cookie", true) }?.value,
                )?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            } else {
                builder.removeHeader("Authorization")
                builder.removeHeader("Cookie")
            }
            return if (webRequest.method == "HEAD") builder.head().build() else builder.get().build()
        }

        private fun okhttp3.Response.toWebResourceResponse(headOnly: Boolean): WebResourceResponse {
            val contentType = body.contentType()
            val mimeType = contentType?.toString()?.substringBefore(';') ?: "application/octet-stream"
            val charset = contentType?.charset()?.name() ?: "utf-8"
            var bytes = if (headOnly) ByteArray(0) else body.bytes()
            require(bytes.size <= MAX_RESOURCE_BYTES) { "Source-scoped resource is too large" }
            if (!headOnly && mimeType.equals("text/html", true)) {
                val bodyCharset = Charset.forName(charset)
                bytes = injectSourceScopedCsp(bytes.toString(bodyCharset)).toByteArray(bodyCharset)
            }
            val responseHeaders = linkedMapOf<String, String>()
            headers.names().forEach { name ->
                if (!name.equals("Set-Cookie", true) && !name.equals("Content-Length", true)) {
                    responseHeaders[name] = headers.values(name).joinToString(", ")
                }
            }
            return WebResourceResponse(
                mimeType,
                charset,
                code,
                message.ifBlank { "HTTP" },
                responseHeaders,
                ByteArrayInputStream(bytes),
            )
        }

        private fun blockedResponse(status: Int, reason: String): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                status,
                reason,
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    companion object {
        private const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val SAFE_WEB_HEADERS = setOf(
            "Accept",
            "Accept-Language",
            "If-Modified-Since",
            "If-None-Match",
            "Range",
            "User-Agent",
        )
        private val BLOCKED_SOURCE_HEADERS = setOf(
            "connection",
            "content-length",
            "cookiejar",
            "host",
            "transfer-encoding",
        )
    }
}
