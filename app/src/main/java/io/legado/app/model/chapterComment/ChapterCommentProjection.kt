package io.legado.app.model.chapterComment

import java.text.Normalizer
import kotlin.math.min

enum class ChapterCommentAnchorConfidence {
    EXACT,
    LOCAL,
    GLOBAL,
    INDEX_ONLY,
}

data class ResolvedChapterCommentSegment(
    val segment: ChapterCommentSegment,
    val paragraphNumbers: IntRange,
    val confidence: ChapterCommentAnchorConfidence,
)

data class PageChapterCommentProjection(
    val segments: List<ResolvedChapterCommentSegment>,
    val totalCount: Int,
    val hotCount: Int,
) {
    val segmentIds: List<String> get() = segments.map { it.segment.id }
    val isEmpty: Boolean get() = segments.isEmpty()
}

/** Resolves source hints against the same processed paragraphs used by layout. */
object ChapterCommentAnchorResolver {

    private const val LOCAL_WINDOW = 3
    private const val MIN_FUZZY_LENGTH = 4
    private const val FUZZY_THRESHOLD = 0.72

    fun resolve(
        payload: ChapterCommentPayload,
        paragraphs: List<String>,
    ): List<ResolvedChapterCommentSegment> {
        if (paragraphs.isEmpty()) return emptyList()
        val normalized = paragraphs.map(::normalize)
        val resolved = linkedMapOf<String, ResolvedChapterCommentSegment>()
        payload.segments.forEach { segment ->
            if (segment.id in resolved) return@forEach
            resolveSegment(segment, normalized)?.let { resolved[segment.id] = it }
        }
        return resolved.values.toList()
    }

    private fun resolveSegment(
        segment: ChapterCommentSegment,
        paragraphs: List<String>,
    ): ResolvedChapterCommentSegment? {
        val maxStart = paragraphs.size - segment.paragraphCount
        if (maxStart < 0) return null
        val excerpt = segment.excerpt?.let(::normalize)?.takeIf(String::isNotEmpty)
        if (excerpt == null) {
            return segment.paragraphIndex.takeIf { it in 0..maxStart }?.let {
                resolved(segment, it, ChapterCommentAnchorConfidence.INDEX_ONLY)
            }
        }

        val hintedIndex = segment.paragraphIndex
        if (hintedIndex in 0..maxStart && isExact(paragraphs.joinAt(hintedIndex, segment.paragraphCount), excerpt)) {
            return resolved(segment, hintedIndex, ChapterCommentAnchorConfidence.EXACT)
        }

        val localStart = (hintedIndex - LOCAL_WINDOW).coerceAtLeast(0)
        val localEnd = (hintedIndex + LOCAL_WINDOW).coerceAtMost(maxStart)
        if (localStart <= localEnd) {
            val localMatches = (localStart..localEnd).filter { index ->
                isConfidentMatch(paragraphs.joinAt(index, segment.paragraphCount), excerpt)
            }
            if (localMatches.size == 1) {
                return resolved(segment, localMatches.single(), ChapterCommentAnchorConfidence.LOCAL)
            }
            if (localMatches.size > 1) return null
        }

        val globalMatches = (0..maxStart).filter { index ->
            isConfidentMatch(paragraphs.joinAt(index, segment.paragraphCount), excerpt)
        }
        return if (globalMatches.size == 1) {
            resolved(segment, globalMatches.single(), ChapterCommentAnchorConfidence.GLOBAL)
        } else {
            null
        }
    }

    private fun resolved(
        segment: ChapterCommentSegment,
        paragraphIndex: Int,
        confidence: ChapterCommentAnchorConfidence,
    ): ResolvedChapterCommentSegment {
        val firstParagraphNumber = paragraphIndex + 1
        return ResolvedChapterCommentSegment(
            segment = segment,
            paragraphNumbers = firstParagraphNumber until
                    firstParagraphNumber + segment.paragraphCount,
            confidence = confidence,
        )
    }

    private fun List<String>.joinAt(start: Int, count: Int): String {
        return subList(start, start + count).joinToString("")
    }

    private fun isExact(paragraph: String, excerpt: String): Boolean {
        return paragraph == excerpt
    }

    private fun isConfidentMatch(paragraph: String, excerpt: String): Boolean {
        if (paragraph == excerpt) return true
        if (excerpt.length >= MIN_FUZZY_LENGTH && paragraph.contains(excerpt)) return true
        if (paragraph.length >= MIN_FUZZY_LENGTH && excerpt.contains(paragraph)) return true
        return diceSimilarity(paragraph, excerpt) >= FUZZY_THRESHOLD
    }

    private fun diceSimilarity(left: String, right: String): Double {
        if (left.length < 2 || right.length < 2) return 0.0
        val leftPairs = hashMapOf<String, Int>()
        left.zipWithNext().forEach { pair ->
            val value = "${pair.first}${pair.second}"
            leftPairs[value] = (leftPairs[value] ?: 0) + 1
        }
        var matches = 0
        right.zipWithNext().forEach { pair ->
            val value = "${pair.first}${pair.second}"
            val available = leftPairs[value] ?: 0
            if (available > 0) {
                matches++
                if (available == 1) leftPairs.remove(value) else leftPairs[value] = available - 1
            }
        }
        return 2.0 * matches / (left.length - 1 + right.length - 1)
    }

    internal fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val result = StringBuilder(normalized.length)
        normalized.forEach { char ->
            if (!char.isWhitespace() && !isCommonPunctuation(char)) {
                result.append(char.lowercaseChar())
            }
        }
        return result.toString()
    }

    private fun isCommonPunctuation(char: Char): Boolean {
        return when (Character.getType(char)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}

/** Projects resolved anchors onto paragraph numbers visible on one real page. */
object ChapterCommentPageProjector {

    fun project(
        paragraphNumbers: Iterable<Int>,
        anchors: List<ResolvedChapterCommentSegment>,
    ): PageChapterCommentProjection {
        val visible = paragraphNumbers.asSequence().filter { it > 0 }.toSet()
        if (visible.isEmpty()) return PageChapterCommentProjection(emptyList(), 0, 0)
        val matched = anchors.asSequence()
            .filter { it.segment.pageEligible }
            .filter { anchor -> anchor.paragraphNumbers.any(visible::contains) }
            .distinctBy { it.segment.id }
            .toList()
        return PageChapterCommentProjection(
            segments = matched,
            totalCount = matched.saturatedSum { it.segment.counts.total },
            hotCount = matched.saturatedSum { it.segment.counts.hot },
        )
    }

    private inline fun <T> Iterable<T>.saturatedSum(value: (T) -> Int): Int {
        var total = 0L
        forEach { total = min(Int.MAX_VALUE.toLong(), total + value(it)) }
        return total.toInt()
    }
}
