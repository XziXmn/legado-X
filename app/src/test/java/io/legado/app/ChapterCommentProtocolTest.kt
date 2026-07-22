package io.legado.app

import io.legado.app.data.entities.rule.ChapterCommentDisplayRule
import io.legado.app.data.entities.rule.ChapterCommentEntryRule
import io.legado.app.data.entities.rule.ChapterCommentRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.chapterComment.ChapterCommentParser
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterCommentProtocolTest {

    @Test
    fun contentRuleJsonRoundTripKeepsChapterComment() {
        val rule = ContentRule(
            content = "body",
            chapterComment = ChapterCommentRule(
                url = "https://example.test/summary",
                display = ChapterCommentDisplayRule(
                    page = ChapterCommentEntryRule(enabled = true, preset = "pull"),
                ),
            ),
        )

        val decoded = GSON.fromJsonObject<ContentRule>(GSON.toJson(rule)).getOrThrow()

        assertEquals("body", decoded.content)
        assertEquals("https://example.test/summary", decoded.chapterComment?.url)
        assertEquals(1, decoded.chapterComment?.protocolVersion)
        assertTrue(decoded.chapterComment?.display?.page?.enabled == true)
    }

    @Test
    fun parserAcceptsV1PayloadWithPreviewField() {
        val payload = ChapterCommentParser.parse(
            """
            {
              "version": 1,
              "segments": [{
                "id": "p-1",
                "paragraphIndex": 0,
                "counts": {"total": 12, "hot": 1},
                "pageEligible": true
              }],
              "chapter": {
                "label": "本章说",
                "counts": {"total": 50},
                "preview": "一条热评预览"
              }
            }
            """.trimIndent()
        )

        assertEquals(1, payload.version)
        assertEquals(12, payload.segments.single().counts.total)
        assertEquals(listOf("一条热评预览"), payload.chapter?.previews)
    }

    @Test
    fun parserAcceptsAndNormalizesValidPayload() {
        val payload = ChapterCommentParser.parse(
            """
            {
              "version": 2,
              "segments": [{
                "id": "p-1",
                "paragraphIndex": 0,
                "counts": {"total": 36, "hot": 3},
                "pageEligible": true,
                "actionData": {"paragraphId": "10"}
              }],
              "author": {
                "label": "作者甲",
                "badge": "作家说",
                "previews": ["这是作者留在章末的补充"],
                "actionData": null
              },
              "chapter": {
                "counts": {"total": 227},
                "previews": [
                  "书友甲：第一条章末热评",
                  "书友乙：第二条章末热评",
                  "书友丙：第三条章末热评"
                ],
                "actionData": {}
              }
            }
            """.trimIndent()
        )

        assertEquals(2, payload.version)
        assertEquals(0, payload.segments.single().paragraphIndex)
        assertEquals(1, payload.segments.single().paragraphCount)
        assertEquals(36, payload.segments.single().counts.total)
        assertEquals("作者甲", payload.author?.label)
        assertEquals("作家说", payload.author?.badge)
        assertEquals("这是作者留在章末的补充", payload.author?.previews?.single())
        assertTrue(payload.author?.actionData == null)
        assertEquals("本章说", payload.chapter?.label)
        assertEquals(3, payload.chapter?.previews?.size)
        assertEquals("书友丙：第三条章末热评", payload.chapter?.previews?.last())
        assertNotNull(payload.chapter?.actionData)
        assertNotNull(payload.segments.single().actionData)
    }

    @Test
    fun parserRejectsMalformedOrOversizedFields() {
        assertInvalid("""{"version":0,"segments":[]}""")
        assertInvalid("""{"version":3,"segments":[]}""")
        assertInvalid("""{"version":2,"segments":[{"id":"x","paragraphIndex":-1}]}""")
        assertInvalid(
            """{"version":2,"segments":[{"id":"${"x".repeat(257)}","paragraphIndex":0}]}"""
        )
        assertInvalid(
            """{"version":2,"segments":[],"chapter":{"previews":["1","2","3","4"]}}"""
        )
        assertInvalid(
            """{"version":2,"segments":[],"author":{"previews":["${"热".repeat(513)}"]}}"""
        )
        val segments = (0..ChapterCommentParser.MAX_SEGMENTS).joinToString(",") {
            """{"id":"$it","paragraphIndex":$it}"""
        }
        assertInvalid("""{"version":2,"segments":[$segments]}""")
    }

    @Test
    fun countsSaturateButNegativeCountsFail() {
        val payload = ChapterCommentParser.parse(
            """{"version":2,"segments":[],"chapter":{"counts":{"total":999999999999}}}"""
        )
        assertEquals(Int.MAX_VALUE, payload.chapter?.counts?.total)
        assertInvalid("""{"version":2,"segments":[],"chapter":{"counts":{"total":-1}}}""")
    }

    @Test
    fun analyzeRuleExposesSupportedCapabilityVersions() {
        val analyzeRule = AnalyzeRule()

        // version argument is the minimum protocol the source needs
        assertEquals(true, analyzeRule.evalJS("java.hasReaderCapability('chapter-comments', 1)"))
        assertEquals(true, analyzeRule.evalJS("java.hasReaderCapability('chapter-comments', 2)"))
        assertEquals(false, analyzeRule.evalJS("java.hasReaderCapability('chapter-comments', 3)"))
        assertEquals(false, analyzeRule.evalJS("java.hasReaderCapability('unknown', 1)"))
    }

    private fun assertInvalid(json: String) {
        val result = runCatching { ChapterCommentParser.parse(json) }
        assertFalse("Expected invalid payload: $json", result.isSuccess)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
