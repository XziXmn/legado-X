package io.legado.app

import io.legado.app.model.chapterComment.ChapterCommentCache
import io.legado.app.model.chapterComment.ChapterCommentCacheRecord
import io.legado.app.model.chapterComment.ChapterCommentLoadResult
import io.legado.app.model.chapterComment.ChapterCommentPriority
import io.legado.app.model.chapterComment.ChapterCommentRepository
import io.legado.app.model.chapterComment.ChapterCommentRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ChapterCommentRepositoryTest {

    @Test
    fun cacheKeySeparatesSourceRuleBookChapterAndVersion() {
        val base = request()
        val keys = listOf(
            base,
            base.copy(sourceUrl = "source-b"),
            base.copy(ruleFingerprint = "rule-b"),
            base.copy(bookUrl = "book-b"),
            base.copy(chapterUrl = "chapter-b"),
            base.copy(protocolVersion = 2),
        ).map { it.cacheKey }

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun concurrentLoadsShareOneFetch() = runBlocking {
        val fetchCount = AtomicInteger()
        val repository = ChapterCommentRepository(MemoryCache())
        val request = request()

        val first = async {
            repository.load(request, ChapterCommentPriority.CURRENT) {
                fetchCount.incrementAndGet()
                delay(100)
                VALID_PAYLOAD
            }
        }
        val second = async {
            repository.load(request, ChapterCommentPriority.CURRENT) {
                fetchCount.incrementAndGet()
                VALID_PAYLOAD
            }
        }

        assertEquals(1, first.await()?.payload?.version)
        assertEquals(1, second.await()?.payload?.version)
        assertEquals(1, fetchCount.get())
    }

    @Test
    fun freshCacheSkipsNetworkAndRuleChangeMissesCache() = runBlocking {
        var now = 1_000L
        val cache = MemoryCache()
        val repository = ChapterCommentRepository(cache, nowMillis = { now })
        val fetchCount = AtomicInteger()

        val first = repository.load(request(), ChapterCommentPriority.CURRENT) {
            fetchCount.incrementAndGet()
            VALID_PAYLOAD
        }
        now += 1_000
        val cached = repository.load(request(), ChapterCommentPriority.CURRENT) {
            fetchCount.incrementAndGet()
            VALID_PAYLOAD
        }
        val changed = repository.load(
            request().copy(ruleFingerprint = "changed"),
            ChapterCommentPriority.CURRENT,
        ) {
            fetchCount.incrementAndGet()
            VALID_PAYLOAD
        }

        assertFalse(first?.fromCache == true)
        assertTrue(cached?.fromCache == true)
        assertFalse(changed?.fromCache == true)
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun staleValueIsPublishedAndRetainedWhenRefreshFails() = runBlocking {
        var now = 1_000L
        val cache = MemoryCache()
        val repository = ChapterCommentRepository(cache, nowMillis = { now })
        val request = request(cacheTtlSeconds = 30)
        repository.load(request, ChapterCommentPriority.CURRENT) { VALID_PAYLOAD }
        now += 31_000
        var publishedStale: ChapterCommentLoadResult? = null

        val result = repository.load(
            request,
            ChapterCommentPriority.CURRENT,
            fetch = { error("offline") },
            onStale = { publishedStale = it },
        )

        assertTrue(publishedStale?.stale == true)
        assertTrue(result?.stale == true)
        assertTrue(result?.fromCache == true)
    }

    @Test
    fun malformedNetworkPayloadFailsClosedWithoutPoisoningCache() = runBlocking {
        val cache = MemoryCache()
        val repository = ChapterCommentRepository(cache)
        val request = request()

        val result = repository.load(request, ChapterCommentPriority.CURRENT) { "not-json" }

        assertNull(result)
        assertNull(cache.read(request.cacheKey))
    }

    private fun request(cacheTtlSeconds: Int = 300) = ChapterCommentRequest(
        sourceUrl = "source-a",
        ruleFingerprint = "rule-a",
        bookUrl = "book-a",
        chapterUrl = "chapter-a",
        protocolVersion = 1,
        cacheTtlSeconds = cacheTtlSeconds,
    )

    private class MemoryCache : ChapterCommentCache {
        private val records = ConcurrentHashMap<String, ChapterCommentCacheRecord>()

        override fun read(cacheKey: String): ChapterCommentCacheRecord? = records[cacheKey]

        override fun write(record: ChapterCommentCacheRecord) {
            records[record.cacheKey] = record
        }

        override fun remove(cacheKey: String) {
            records.remove(cacheKey)
        }
    }

    companion object {
        private const val VALID_PAYLOAD = """{"version":1,"segments":[]}"""
    }
}
