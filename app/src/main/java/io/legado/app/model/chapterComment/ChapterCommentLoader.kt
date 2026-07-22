package io.legado.app.model.chapterComment

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ChapterCommentRule
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.currentCoroutineContext
import java.security.MessageDigest

/** Runtime state attached to a laid-out chapter; it is never persisted with正文. */
sealed class ChapterCommentState {
    data object Disabled : ChapterCommentState()
    data object Loading : ChapterCommentState()
    data class Ready(
        val payload: ChapterCommentPayload,
        val stale: Boolean,
    ) : ChapterCommentState()
    data object Unavailable : ChapterCommentState()
}

/** Executes source-defined summary rules and normalizes them through the repository. */
class ChapterCommentLoader(
    private val repository: ChapterCommentRepository = ChapterCommentRepository(
        AndroidChapterCommentCache()
    ),
) {

    suspend fun load(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        rule: ChapterCommentRule,
        priority: ChapterCommentPriority,
        onStale: suspend (ChapterCommentLoadResult) -> Unit = {},
    ): ChapterCommentLoadResult? {
        val request = ChapterCommentRequest(
            sourceUrl = source.bookSourceUrl,
            ruleFingerprint = fingerprint(rule),
            bookUrl = book.bookUrl,
            chapterUrl = chapter.getAbsoluteURL(),
            protocolVersion = rule.protocolVersion,
            cacheTtlSeconds = rule.cacheTtlSeconds,
        )
        return repository.load(request, priority, fetch = {
            executeRule(source, book, chapter, rule)
        }, onStale = onStale)
    }

    private suspend fun executeRule(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        rule: ChapterCommentRule,
    ): String {
        require(rule.protocolVersion in 1..ChapterCommentParser.PROTOCOL_VERSION) {
            "Unsupported chapter comment protocol: ${rule.protocolVersion}"
        }
        val urlRule = rule.url?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Chapter comment URL rule is empty")
        val context = currentCoroutineContext()
        val response = AnalyzeUrl(
            mUrl = urlRule,
            baseUrl = chapter.getAbsoluteURL(),
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = context,
        ).getStrResponseAwait()
        val body = response.body ?: ""
        val dataRule = rule.data?.takeIf(String::isNotBlank) ?: return body
        return AnalyzeRule(book, source)
            .setCoroutineContext(context)
            .setChapter(chapter)
            .setContent(body, response.url)
            .also { it.setRedirectUrl(response.url) }
            .getString(dataRule)
    }

    private fun fingerprint(rule: ChapterCommentRule): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(GSON.toJson(rule).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/** Dedicated 20 MiB cache. Records verify the full key after ACache lookup. */
private class AndroidChapterCommentCache : ChapterCommentCache {

    private val cache by lazy {
        ACache.get(
            cacheName = "chapterComments",
            maxSize = 20L * 1024 * 1024,
            maxCount = 2_000,
        )
    }

    override fun read(cacheKey: String): ChapterCommentCacheRecord? {
        val json = cache.getAsString(cacheKey) ?: return null
        return GSON.fromJsonObject<ChapterCommentCacheRecord>(json).getOrNull()
    }

    override fun write(record: ChapterCommentCacheRecord) {
        cache.put(record.cacheKey, GSON.toJson(record))
    }

    override fun remove(cacheKey: String) {
        cache.remove(cacheKey)
    }
}
