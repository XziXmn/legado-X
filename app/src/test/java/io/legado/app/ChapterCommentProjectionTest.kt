package io.legado.app

import io.legado.app.model.chapterComment.ChapterCommentAnchorConfidence
import io.legado.app.model.chapterComment.ChapterCommentAnchorResolver
import io.legado.app.model.chapterComment.ChapterCommentCounts
import io.legado.app.model.chapterComment.ChapterCommentPageProjector
import io.legado.app.model.chapterComment.ChapterCommentPayload
import io.legado.app.model.chapterComment.ChapterCommentSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterCommentProjectionTest {

    @Test
    fun firstProtocolParagraphMapsFromZeroToOne() {
        val resolved = ChapterCommentAnchorResolver.resolve(
            payload(segment(index = 0, excerpt = "第一段")),
            listOf("第一段", "第二段"),
        ).single()

        assertEquals(1..1, resolved.paragraphNumbers)
        assertEquals(ChapterCommentAnchorConfidence.EXACT, resolved.confidence)
    }

    @Test
    fun localMatchSurvivesInsertedParagraphAndPunctuationReplacement() {
        val resolved = ChapterCommentAnchorResolver.resolve(
            payload(segment(index = 1, excerpt = "风起，云涌！")),
            listOf("新增前言", "另一段", "风起 云涌", "结尾"),
        ).single()

        assertEquals(3..3, resolved.paragraphNumbers)
        assertEquals(ChapterCommentAnchorConfidence.LOCAL, resolved.confidence)
    }

    @Test
    fun paragraphCountMatchesMergedExcerptAndProjectsAcrossPages() {
        val resolved = ChapterCommentAnchorResolver.resolve(
            payload(segment(index = 0, excerpt = "甲乙", paragraphCount = 2)),
            listOf("甲", "乙", "丙"),
        ).single()

        assertEquals(1..2, resolved.paragraphNumbers)
        assertTrue(ChapterCommentPageProjector.project(listOf(1), listOf(resolved)).segments.isNotEmpty())
        assertTrue(ChapterCommentPageProjector.project(listOf(2), listOf(resolved)).segments.isNotEmpty())
    }

    @Test
    fun duplicateGlobalExcerptIsRejectedAsAmbiguous() {
        val resolved = ChapterCommentAnchorResolver.resolve(
            payload(segment(index = 99, excerpt = "重复段落")),
            listOf("重复段落", "中间", "重复段落"),
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun pageProjectionFiltersEligibilityDeduplicatesAndSaturatesCounts() {
        val first = segment(id = "a", index = 0, excerpt = null, total = Int.MAX_VALUE)
        val duplicate = first.copy(counts = ChapterCommentCounts(total = 10, hot = 10))
        val hidden = segment(id = "b", index = 0, excerpt = null, eligible = false, total = 5)
        val anchors = ChapterCommentAnchorResolver.resolve(
            ChapterCommentPayload(1, listOf(first, duplicate, hidden), null),
            listOf("正文"),
        )

        val projection = ChapterCommentPageProjector.project(listOf(0, 1, 1), anchors)

        assertEquals(listOf("a"), projection.segmentIds)
        assertEquals(Int.MAX_VALUE, projection.totalCount)
        assertEquals(1, projection.hotCount)
        assertEquals(Int.MAX_VALUE, ChapterCommentPageProjector.count(projection, "total"))
        assertEquals(1, ChapterCommentPageProjector.count(projection, "hot"))
        assertTrue(ChapterCommentPageProjector.project(emptyList(), anchors).isEmpty)
    }

    private fun payload(segment: ChapterCommentSegment) =
        ChapterCommentPayload(1, listOf(segment), null)

    private fun segment(
        id: String = "s-1",
        index: Int,
        excerpt: String?,
        paragraphCount: Int = 1,
        eligible: Boolean = true,
        total: Int = 3,
    ) = ChapterCommentSegment(
        id = id,
        paragraphIndex = index,
        paragraphCount = paragraphCount,
        excerpt = excerpt,
        counts = ChapterCommentCounts(total = total, hot = 1),
        pageEligible = eligible,
        actionData = null,
    )
}
