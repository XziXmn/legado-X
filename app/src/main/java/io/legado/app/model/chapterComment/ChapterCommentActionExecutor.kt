package io.legado.app.model.chapterComment

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ChapterCommentRule
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieStore
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI

data class ChapterCommentWebPage(
    val action: ChapterCommentAction,
    val finalUrl: String,
    val html: String,
    val networkContext: SourceScopedNetworkContext,
    /** Book-source request headers used for the first-page fetch (and subsequent scoped loads). */
    val headers: Map<String, String> = emptyMap(),
)

/** Executes a source-owned action and fetches its first page exactly once. */
class ChapterCommentActionExecutor {

    suspend fun resolveAndLoad(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        rule: ChapterCommentRule,
        event: ChapterCommentEvent,
    ): ChapterCommentWebPage = withContext(IO) {
        val actionRule = rule.action?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Chapter comment action rule is empty")
        // Literal contract keys; never GSON.toJson(event) under R8 field renaming.
        val eventJson = event.toContractJson()
        val actionJson = if (actionRule.startsWith('{')) {
            actionRule
        } else {
            source.evalJS(normalizeScript(actionRule)) {
                put("book", book)
                put("chapter", chapter)
                put("title", chapter.title)
                put("baseUrl", chapter.getAbsoluteURL())
                put("event", eventJson)
                put("result", eventJson)
            }?.toString().orEmpty()
        }
        val action = ChapterCommentActionParser.parse(actionJson)
        val summaryUrlRule = rule.url?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Chapter comment URL rule is empty")
        val summaryUrl = AnalyzeUrl(
            mUrl = summaryUrlRule,
            baseUrl = chapter.getAbsoluteURL(),
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = currentCoroutineContext(),
        ).url
        val summaryOrigin = SourceScopedRequestPolicy.validateActionUrl(summaryUrl)
        val analyzeUrl = AnalyzeUrl(
            mUrl = action.url,
            baseUrl = chapter.getAbsoluteURL(),
            source = source,
            ruleData = book,
            chapter = chapter,
        )
        val initialUrl = analyzeUrl.url
        val networkContext = SourceScopedRequestPolicy.pinActionUrl(initialUrl, summaryOrigin)
        val sourceHeaders = LinkedHashMap(analyzeUrl.headerMap)
        val page = fetchHtml(sourceHeaders, initialUrl, networkContext)
        ChapterCommentWebPage(
            action = action,
            finalUrl = page.first,
            html = page.second,
            networkContext = networkContext,
            headers = sourceHeaders,
        )
    }

    private fun normalizeScript(rule: String): String {
        val trimmed = rule.trim()
        return when {
            trimmed.startsWith("@js:") -> trimmed.substring(4)
            trimmed.startsWith("<js>", ignoreCase = true) && trimmed.endsWith("</js>", ignoreCase = true) ->
                trimmed.substring(4, trimmed.length - 5)
            else -> trimmed
        }
    }

    private fun fetchHtml(
        sourceHeaders: Map<String, String>,
        initialUrl: String,
        networkContext: SourceScopedNetworkContext,
    ): Pair<String, String> {
        val client = newSourceScopedHttpClient(networkContext)
        val authenticatedOrigin = networkContext.origin
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            require(SourceScopedRequestPolicy.canNavigate(currentUrl, authenticatedOrigin)) {
                "Cross-origin chapter comment redirect is not allowed"
            }
            val request = Request.Builder().url(currentUrl).get().apply {
                sourceHeaders.forEach { (name, value) ->
                    if (name.lowercase() !in BLOCKED_REQUEST_HEADERS && !name.equals(AppConst.UA_NAME, true)) {
                        header(name, value)
                    }
                }
                sourceHeaders.entries.firstOrNull { it.key.equals(AppConst.UA_NAME, true) }
                    ?.value?.let { header("User-Agent", it) }
                CookieManager.mergeCookies(
                    CookieStore.getCookie(currentUrl),
                    sourceHeaders.entries.firstOrNull { it.key.equals("Cookie", true) }?.value,
                )?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    require(redirectIndex < MAX_REDIRECTS) { "Too many chapter comment redirects" }
                    val location = response.header("Location")
                        ?: throw IllegalArgumentException("Chapter comment redirect has no Location")
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    return@repeat
                }
                require(response.isSuccessful) { "Chapter comment page HTTP ${response.code}" }
                val body = response.body
                val bytes = body.bytes()
                require(bytes.size <= MAX_HTML_BYTES) { "Chapter comment page is too large" }
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                return response.request.url.toString() to bytes.toString(charset)
            }
        }
        throw IllegalArgumentException("Unable to load chapter comment page")
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_HTML_BYTES = 2 * 1024 * 1024
        private val BLOCKED_REQUEST_HEADERS = setOf(
            "cookiejar",
            "host",
            "content-length",
            "connection",
            "transfer-encoding",
        )
    }
}
