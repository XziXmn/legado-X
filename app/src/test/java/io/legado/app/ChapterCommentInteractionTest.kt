package io.legado.app

import io.legado.app.model.chapterComment.ChapterCommentActionParser
import io.legado.app.model.chapterComment.SourceScopedDns
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
