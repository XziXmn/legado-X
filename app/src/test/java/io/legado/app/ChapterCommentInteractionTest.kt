package io.legado.app

import com.google.gson.JsonParser
import io.legado.app.model.chapterComment.ChapterCommentActionParser
import io.legado.app.model.chapterComment.ChapterCommentEvent
import io.legado.app.model.chapterComment.SourceScopedDns
import io.legado.app.model.chapterComment.SourceScopedInitialDocumentGate
import io.legado.app.model.chapterComment.SourceScopedRequestPolicy
import io.legado.app.ui.book.read.comment.PageCommentPullController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ChapterCommentInteractionTest {

    @Test
    fun pullArmsAtExactThresholdAndOpensOnce() {
        val controller = PageCommentPullController(52f)
        controller.start(10f, 10f, enabled = true)

        val before = controller.move(10f, 61f, touchSlop = 1f, maxOffset = 200f)
        val armed = controller.move(10f, 62f, touchSlop = 1f, maxOffset = 200f)
        val stillArmed = controller.move(10f, 80f, touchSlop = 1f, maxOffset = 200f)
        val end = controller.end(cancelled = false)

        assertFalse(before.feedback)
        assertTrue(armed.claimed)
        assertTrue(armed.feedback)
        assertFalse(stillArmed.feedback)
        assertTrue(end.open)
    }

    @Test
    fun horizontalOrUpwardMovementNeverClaimsPageGesture() {
        val horizontal = PageCommentPullController(52f)
        horizontal.start(0f, 0f, enabled = true)
        assertFalse(horizontal.move(30f, 3f, 1f, 100f).claimed)

        val upward = PageCommentPullController(52f)
        upward.start(0f, 20f, enabled = true)
        assertFalse(upward.move(0f, 0f, 1f, 100f).claimed)
    }

    @Test
    fun pullingBackBelowThresholdCancelsOpenButKeepsOwnership() {
        val controller = PageCommentPullController(52f)
        controller.start(0f, 0f, enabled = true)
        assertTrue(controller.move(0f, 60f, 1f, 100f).feedback)

        val pulledBack = controller.move(0f, 30f, 1f, 100f)

        assertTrue(pulledBack.claimed)
        assertFalse(controller.end(cancelled = false).open)
    }

    @Test
    fun actionParserRestrictsTypeAndOwnsSheetHeight() {
        val action = ChapterCommentActionParser.parse(
            """{"type":"sourceWebView","url":"https://example.test/comments","title":"热评","heightRatio":1}"""
        )

        assertEquals("sourceWebView", action.type)
        assertEquals(0.78f, action.heightRatio)
        assertFails { ChapterCommentActionParser.parse("""{"type":"native","url":"https://example.test"}""") }
    }

    @Test
    fun chapterCommentEventContractJsonUsesStableLiteralKeys() {
        val segment = ChapterCommentEvent(
            scope = ChapterCommentEvent.SCOPE_SEGMENT,
            chapterIndex = 3,
            pageIndex = 1,
            segmentId = "p-10",
            segmentIds = listOf("p-10"),
            count = 4,
        )
        val page = ChapterCommentEvent(
            scope = ChapterCommentEvent.SCOPE_PAGE,
            chapterIndex = 3,
            pageIndex = 1,
            segmentIds = listOf("p-10", "p-11"),
            count = 7,
        )
        val chapter = ChapterCommentEvent(
            scope = ChapterCommentEvent.SCOPE_CHAPTER,
            chapterIndex = 3,
            pageIndex = 2,
            count = 12,
        )

        val segmentJson = JsonParser.parseString(segment.toContractJson()).asJsonObject
        val pageJson = JsonParser.parseString(page.toContractJson()).asJsonObject
        val chapterJson = JsonParser.parseString(chapter.toContractJson()).asJsonObject

        assertEquals("segment", segmentJson.get("scope").asString)
        assertEquals("p-10", segmentJson.get("segmentId").asString)
        assertEquals(3, segmentJson.get("chapterIndex").asInt)
        assertEquals(1, segmentJson.get("pageIndex").asInt)
        assertEquals(4, segmentJson.get("count").asInt)
        assertTrue(segmentJson.getAsJsonArray("segmentIds").any { it.asString == "p-10" })

        assertEquals("page", pageJson.get("scope").asString)
        assertEquals(2, pageJson.getAsJsonArray("segmentIds").size())
        assertFalse(pageJson.has("segmentId"))

        assertEquals("chapter", chapterJson.get("scope").asString)
        assertEquals(12, chapterJson.get("count").asInt)
        // Book-source action scripts key on these exact property names.
        assertTrue(segment.toContractJson().contains("\"scope\""))
        assertTrue(page.toContractJson().contains("\"segmentIds\""))
    }

    @Test
    fun sourceScopedPolicyKeepsAuthenticationOnOneOriginOnly() {
        val origin = SourceScopedRequestPolicy.validateActionUrl("http://192.168.1.10:8765/comments")

        assertTrue(SourceScopedRequestPolicy.canNavigate("http://192.168.1.10:8765/next", origin))
        assertFalse(SourceScopedRequestPolicy.canNavigate("http://192.168.1.11:8765/next", origin))
        assertFalse(
            SourceScopedRequestPolicy.canLoadSubresource(
                "http://192.168.1.11/avatar",
                origin,
            ) { listOf(InetAddress.getByName("192.168.1.11")) }
        )
        assertTrue(
            SourceScopedRequestPolicy.canLoadSubresource(
                "https://cdn.example.test/avatar.png",
                origin,
            ) { listOf(InetAddress.getByName("203.0.113.10")) }
        )
        assertFalse(
            SourceScopedRequestPolicy.canLoadSubresource(
                "http://cdn.example.test/avatar.png",
                origin,
            ) { listOf(InetAddress.getByName("203.0.113.10")) }
        )
    }

    @Test
    fun sourceScopedInlineDocumentIsAllowedOnlyOnceForTheArmedMainFrame() {
        val gate = SourceScopedInitialDocumentGate()
        val inlineDocument = "data:text/html;charset=utf-8;base64,PGh0bWw+"
        val baseUrl = "https://example.test/comments"

        gate.arm(baseUrl)
        assertFalse(gate.consume(inlineDocument, "GET", isForMainFrame = false))
        assertTrue(gate.consume(inlineDocument, "GET", isForMainFrame = true))
        assertFalse(gate.consume(inlineDocument, "GET", isForMainFrame = true))

        gate.arm(baseUrl)
        assertTrue(gate.consume(baseUrl, "GET", isForMainFrame = true))
        assertFalse(gate.consume(baseUrl, "GET", isForMainFrame = true))

        gate.arm(baseUrl)
        assertFalse(gate.consume("https://example.test/other", "GET", isForMainFrame = true))
        assertFalse(gate.consume(inlineDocument, "GET", isForMainFrame = true))

        gate.arm(baseUrl)
        assertFalse(gate.consume(inlineDocument, "POST", isForMainFrame = true))
    }

    @Test
    fun sourceScopedPolicyPinsSummaryOriginAndRejectsLoopback() {
        val expected = SourceScopedRequestPolicy.validateActionUrl("http://192.168.1.10:8765/reviews")
        val context = SourceScopedRequestPolicy.pinActionUrl(
            "http://192.168.1.10:8765/reviews/view",
            expected,
        ) { listOf(InetAddress.getByName("192.168.1.10")) }

        assertEquals(expected, context.origin)
        assertFails {
            SourceScopedRequestPolicy.pinActionUrl(
                "http://192.168.1.11:8765/reviews/view",
                expected,
            ) { listOf(InetAddress.getByName("192.168.1.11")) }
        }
        assertFails {
            SourceScopedRequestPolicy.pinActionUrl(
                "http://192.168.1.10:8765/reviews/view",
                expected,
            ) { listOf(InetAddress.getLoopbackAddress()) }
        }

        val dns = SourceScopedDns(context) { hostname ->
            if (hostname == expected.host) {
                listOf(InetAddress.getByName("10.0.0.99"))
            } else {
                listOf(InetAddress.getByName("10.0.0.10"))
            }
        }
        assertEquals("192.168.1.10", dns.lookup(expected.host).single().hostAddress)
        var privateLookupBlocked = false
        try {
            dns.lookup("cdn.example.test")
        } catch (_: java.net.UnknownHostException) {
            privateLookupBlocked = true
        }
        assertTrue(privateLookupBlocked)
    }

    @Test
    fun sourceScopedPolicyRejectsMetadataAndCredentialQueries() {
        assertFails {
            SourceScopedRequestPolicy.validateActionUrl("http://169.254.169.254/latest/meta-data")
        }
        assertFails {
            SourceScopedRequestPolicy.validateActionUrl("https://example.test/comments?token=secret")
        }
        assertFails {
            SourceScopedRequestPolicy.validateActionUrl("file:///tmp/comments.html")
        }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
