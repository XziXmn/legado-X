package io.legado.app.model.chapterComment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Stable identity for one source-defined chapter comment summary. */
data class ChapterCommentRequest(
    val sourceUrl: String,
    val ruleFingerprint: String,
    val bookUrl: String,
    val chapterUrl: String,
    val protocolVersion: Int,
    val cacheTtlSeconds: Int,
) {
    val cacheKey: String = sha256(
        listOf(sourceUrl, ruleFingerprint, bookUrl, chapterUrl, protocolVersion.toString())
            .joinToString("\u0000")
    )

    companion object {
        private fun sha256(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}

enum class ChapterCommentPriority {
    CURRENT,
    PRELOAD,
}

data class ChapterCommentCacheRecord(
    val cacheKey: String,
    val payload: String,
    val storedAtMillis: Long,
    val expiresAtMillis: Long,
)

interface ChapterCommentCache {
    fun read(cacheKey: String): ChapterCommentCacheRecord?
    fun write(record: ChapterCommentCacheRecord)
    fun remove(cacheKey: String)
}

data class ChapterCommentLoadResult(
    val payload: ChapterCommentPayload,
    val fromCache: Boolean,
    val stale: Boolean,
)

/**
 * Coordinates bounded chapter-summary requests independently from正文 loading.
 *
 * The first caller owns the network request. Concurrent callers await the same
 * deferred result, while cancellation still reaches the underlying request.
 */
class ChapterCommentRepository(
    private val cache: ChapterCommentCache,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val maxStaleMillis: Long = DEFAULT_MAX_STALE_MILLIS,
) {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ChapterCommentPayload>>()
    private val preloadSemaphore = Semaphore(1)

    /**
     * Load one summary. A stale value is published before revalidation and is
     * retained only when the refresh fails.
     */
    suspend fun load(
        request: ChapterCommentRequest,
        priority: ChapterCommentPriority,
        onStale: suspend (ChapterCommentLoadResult) -> Unit = {},
        fetch: suspend () -> String,
    ): ChapterCommentLoadResult? {
        val now = nowMillis()
        val cached = readValidRecord(request, now)
        if (cached != null && cached.expiresAtMillis > now) {
            return parseCached(cached, stale = false)
        }

        val stale = cached?.let { parseCached(it, stale = true) }
        if (stale != null) onStale(stale)

        return try {
            val payload = awaitShared(request.cacheKey) {
                val json = withTimeout(requestTimeoutMillis) {
                    if (priority == ChapterCommentPriority.PRELOAD) {
                        preloadSemaphore.withPermit { fetch() }
                    } else {
                        fetch()
                    }
                }
                ChapterCommentParser.parse(json).also {
                    val storedAt = nowMillis()
                    val ttlMillis = request.cacheTtlSeconds
                        .coerceIn(MIN_CACHE_TTL_SECONDS, MAX_CACHE_TTL_SECONDS) * 1_000L
                    cache.write(
                        ChapterCommentCacheRecord(
                            cacheKey = request.cacheKey,
                            payload = json,
                            storedAtMillis = storedAt,
                            expiresAtMillis = storedAt + ttlMillis,
                        )
                    )
                }
            }
            ChapterCommentLoadResult(payload, fromCache = false, stale = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            stale
        }
    }

    private fun readValidRecord(
        request: ChapterCommentRequest,
        now: Long,
    ): ChapterCommentCacheRecord? {
        val record = cache.read(request.cacheKey) ?: return null
        if (
            record.cacheKey != request.cacheKey ||
            record.storedAtMillis > now ||
            now - record.storedAtMillis > maxStaleMillis
        ) {
            cache.remove(request.cacheKey)
            return null
        }
        return record
    }

    private fun parseCached(
        record: ChapterCommentCacheRecord,
        stale: Boolean,
    ): ChapterCommentLoadResult? {
        return runCatching {
            ChapterCommentLoadResult(
                payload = ChapterCommentParser.parse(record.payload),
                fromCache = true,
                stale = stale,
            )
        }.getOrElse {
            cache.remove(record.cacheKey)
            null
        }
    }

    private suspend fun awaitShared(
        key: String,
        fetch: suspend () -> ChapterCommentPayload,
    ): ChapterCommentPayload {
        val owner = CompletableDeferred<ChapterCommentPayload>()
        val shared = inFlight.putIfAbsent(key, owner)
        if (shared != null) return shared.await()

        try {
            return fetch().also(owner::complete)
        } catch (cancelled: CancellationException) {
            owner.cancel(cancelled)
            throw cancelled
        } catch (throwable: Throwable) {
            owner.completeExceptionally(throwable)
            throw throwable
        } finally {
            inFlight.remove(key, owner)
        }
    }

    companion object {
        const val MIN_CACHE_TTL_SECONDS = 30
        const val MAX_CACHE_TTL_SECONDS = 24 * 60 * 60
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_MAX_STALE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
