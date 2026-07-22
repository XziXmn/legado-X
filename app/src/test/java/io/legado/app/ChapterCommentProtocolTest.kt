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
        assertTrue(decoded.chapterComment?.display?.page?.enabled == true)
    }

    @Test
    fun parserAcceptsAndNormalizesValidPayload() {
        val payload = ChapterCommentParser.parse(
            """
            {
              "version": 1,
              "segments": [{
                "id": "p-1",
                "paragraphIndex": 0,
                "counts": {"total": 36, "hot": 3},
                "pageEligible": true,
                "actionData": {"paragraphId": "10"}
              }],
              "chapter": {
                "counts": {"total": 227},
                "preview": "这是一条章末热评摘要"
              }
            }
            """.trimIndent()
        )

        assertEquals(1, payload.version)
        assertEquals(0, payload.segments.single().paragraphIndex)
        assertEquals(1, payload.segments.single().paragraphCount)
        assertEquals(36, payload.segments.single().counts.total)
        assertEquals("本章说", payload.chapter?.label)
        assertEquals("这是一条章末热评摘要", payload.chapter?.preview)
        assertNotNull(payload.segments.single().actionData)
    }

    @Test
    fun parserRejectsMalformedOrOversizedFields() {
        assertInvalid("""{"version":2,"segments":[]}""")
        assertInvalid("""{"version":1,"segments":[{"id":"x","paragraphIndex":-1}]}""")
        assertInvalid(
            """{"version":1,"segments":[{"id":"${"x".repeat(257)}","paragraphIndex":0}]}"""
        )
        assertInvalid(
            """{"version":1,"segments":[],"chapter":{"preview":"${"热".repeat(513)}"}}"""
        )
        val segments = (0..ChapterCommentParser.MAX_SEGMENTS).joinToString(",") {
            """{"id":"$it","paragraphIndex":$it}"""
        }
        assertInvalid("""{"version":1,"segments":[$segments]}""")
    }

    @Test
    fun countsSaturateButNegativeCountsFail() {
        val payload = ChapterCommentParser.parse(
            """{"version":1,"segments":[],"chapter":{"counts":{"total":999999999999}}}"""
        )
        assertEquals(Int.MAX_VALUE, payload.chapter?.counts?.total)
        assertInvalid("""{"version":1,"segments":[],"chapter":{"counts":{"total":-1}}}""")
    }

    @Test
    fun analyzeRuleExposesOnlyKnownCapabilityVersion() {
        val analyzeRule = AnalyzeRule()

        assertEquals(true, analyzeRule.evalJS("java.hasReaderCapability('chapter-comments', 1)"))
        assertEquals(false, analyzeRule.evalJS("java.hasReaderCapability('chapter-comments', 2)"))
        assertEquals(false, analyzeRule.evalJS("java.hasReaderCapability('unknown', 1)"))
    }

    private fun assertInvalid(json: String) {
        val result = runCatching { ChapterCommentParser.parse(json) }
        assertFalse("Expected invalid payload: $json", result.isSuccess)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
