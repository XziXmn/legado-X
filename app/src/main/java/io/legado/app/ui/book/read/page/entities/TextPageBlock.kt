package io.legado.app.ui.book.read.page.entities

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.chapterComment.ChapterCommentParser
import io.legado.app.model.chapterComment.ChapterCommentSummary
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import java.text.BreakIterator
import kotlin.math.max

sealed interface TextPageBlock {
    val top: Float
    val height: Float
    val bottom: Float get() = top + height
    fun draw(canvas: Canvas)
    fun contains(x: Float, y: Float, relativeOffset: Float): Boolean
}

data class ChapterCommentPageBlock(
    val summary: ChapterCommentSummary,
    override val top: Float,
    override val height: Float = preferredHeight(summary),
) : TextPageBlock {

    val isInteractive: Boolean
        get() = summary.actionData != null

    override fun draw(canvas: Canvas) {
        val left = ChapterProvider.paddingLeft.toFloat()
        val right = ChapterProvider.visibleRight.toFloat()
        val textColor = ReadBookConfig.textColor
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(textColor, 18)
        }
        canvas.drawRoundRect(
            left,
            top,
            right,
            bottom,
            CORNER_RADIUS,
            CORNER_RADIUS,
            backgroundPaint,
        )

        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = titleTextSize()
            typeface = ChapterProvider.typeface
        }
        val countPaint = Paint(labelPaint).apply {
            color = ColorUtils.setAlphaComponent(textColor, 150)
            textSize = labelPaint.textSize * 0.82f
            textAlign = Paint.Align.RIGHT
        }
        val contentLeft = left + HORIZONTAL_PADDING
        val contentRight = right - HORIZONTAL_PADDING
        val titleBaseline = top + TOP_PADDING - labelPaint.ascent()
        val count = summary.counts.total
        val countText = if (count > 0) "$count 条评论" else ""
        val arrowWidth = if (isInteractive) ARROW_RESERVED_WIDTH else 0f
        val titleRight = contentRight - arrowWidth - if (countText.isNotEmpty()) {
            countPaint.measureText(countText) + TITLE_COUNT_GAP
        } else {
            0f
        }
        val badge = summary.badge.orEmpty()
        val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(textColor, 210)
            textSize = labelPaint.textSize * 0.62f
            typeface = ChapterProvider.typeface
        }
        val badgeWidth = if (badge.isNotEmpty()) {
            badgePaint.measureText(badge) + BADGE_HORIZONTAL_PADDING * 2
        } else {
            0f
        }
        val badgeReservedWidth = if (badgeWidth > 0f) badgeWidth + TITLE_BADGE_GAP else 0f
        val title = TextUtils.ellipsize(
            summary.label,
            labelPaint,
            max(0f, titleRight - contentLeft - badgeReservedWidth),
            TextUtils.TruncateAt.END,
        ).toString()
        if (title.isNotEmpty()) {
            canvas.drawText(title, contentLeft, titleBaseline, labelPaint)
        }
        if (badgeWidth > 0f) {
            val badgeLeft = contentLeft + labelPaint.measureText(title) + TITLE_BADGE_GAP
            val badgeRight = minOf(titleRight, badgeLeft + badgeWidth)
            if (badgeRight - badgeLeft >= badgePaint.measureText(badge) + BADGE_HORIZONTAL_PADDING * 2) {
                val labelHeight = labelPaint.descent() - labelPaint.ascent()
                val badgeHeight = badgePaint.descent() - badgePaint.ascent() + BADGE_VERTICAL_PADDING * 2
                val badgeTop = titleBaseline + labelPaint.ascent() + (labelHeight - badgeHeight) / 2
                val badgeBottom = badgeTop + badgeHeight
                val badgeBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ColorUtils.setAlphaComponent(textColor, 24)
                }
                canvas.drawRoundRect(
                    badgeLeft,
                    badgeTop,
                    badgeRight,
                    badgeBottom,
                    BADGE_CORNER_RADIUS,
                    BADGE_CORNER_RADIUS,
                    badgeBackground,
                )
                val badgeBaseline = badgeTop + BADGE_VERTICAL_PADDING - badgePaint.ascent()
                canvas.drawText(badge, badgeLeft + BADGE_HORIZONTAL_PADDING, badgeBaseline, badgePaint)
            }
        }
        if (count > 0) {
            canvas.drawText(
                countText,
                contentRight - arrowWidth,
                titleBaseline,
                countPaint,
            )
        }
        if (isInteractive) {
            canvas.drawText(">", contentRight, titleBaseline, countPaint)
        }

        val previews = previewItems(summary)
        if (previews.isNotEmpty()) {
            val previewPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(textColor, 175)
                textSize = previewTextSize()
                typeface = ChapterProvider.typeface
            }
            val maxWidth = max(0f, contentRight - contentLeft)
            val firstBaseline = titleBaseline + TITLE_PREVIEW_GAP - previewPaint.ascent()
            val lineHeight = (previewPaint.descent() - previewPaint.ascent()) * PREVIEW_LINE_SPACING
            val lines = if (previews.size == 1) {
                previewLines(previews.single(), previewPaint, maxWidth)
            } else {
                previews.map { preview ->
                    TextUtils.ellipsize(
                        preview,
                        previewPaint,
                        maxWidth,
                        TextUtils.TruncateAt.END,
                    ).toString()
                }.filter(String::isNotEmpty)
            }
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, contentLeft, firstBaseline + index * lineHeight, previewPaint)
            }
        }
    }

    override fun contains(x: Float, y: Float, relativeOffset: Float): Boolean {
        if (!isInteractive) return false
        val localY = y - relativeOffset
        return x in ChapterProvider.paddingLeft.toFloat()..ChapterProvider.visibleRight.toFloat() &&
                localY in top..bottom
    }

    companion object {
        val DEFAULT_HEIGHT = 56.dpToPx().toFloat()
        val TOP_GAP = 12.dpToPx().toFloat()
        val END_PADDING = 20.dpToPx().toFloat()
        private val PREVIEW_MIN_HEIGHT = 84.dpToPx().toFloat()
        private val PREVIEW_MAX_HEIGHT = 128.dpToPx().toFloat()
        private val MIN_TITLE_TEXT_SIZE = 13.dpToPx().toFloat()
        private val MAX_TITLE_TEXT_SIZE = 18.dpToPx().toFloat()
        private val MIN_PREVIEW_TEXT_SIZE = 11.dpToPx().toFloat()
        private val MAX_PREVIEW_TEXT_SIZE = 15.dpToPx().toFloat()
        private val HORIZONTAL_PADDING = 14.dpToPx().toFloat()
        private val TOP_PADDING = 12.dpToPx().toFloat()
        private val BOTTOM_PADDING = 12.dpToPx().toFloat()
        private val TITLE_PREVIEW_GAP = 7.dpToPx().toFloat()
        private val TITLE_COUNT_GAP = 8.dpToPx().toFloat()
        private val TITLE_BADGE_GAP = 7.dpToPx().toFloat()
        private val BADGE_HORIZONTAL_PADDING = 6.dpToPx().toFloat()
        private val BADGE_VERTICAL_PADDING = 2.dpToPx().toFloat()
        private val ARROW_RESERVED_WIDTH = 14.dpToPx().toFloat()
        private val CORNER_RADIUS = 7.dpToPx().toFloat()
        private val BADGE_CORNER_RADIUS = 4.dpToPx().toFloat()
        private const val PREVIEW_LINE_SPACING = 1.12f
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun preferredHeight(summary: ChapterCommentSummary): Float {
            val previews = previewItems(summary)
            if (previews.isEmpty()) return DEFAULT_HEIGHT
            val titlePaint = Paint().apply { textSize = titleTextSize() }
            val previewPaint = Paint().apply { textSize = previewTextSize() }
            val titleHeight = titlePaint.descent() - titlePaint.ascent()
            val previewLineHeight = (previewPaint.descent() - previewPaint.ascent()) * PREVIEW_LINE_SPACING
            val previewLineCount = if (previews.size == 1) 2 else previews.size
            return (TOP_PADDING + titleHeight + TITLE_PREVIEW_GAP +
                    previewLineHeight * previewLineCount + BOTTOM_PADDING)
                .coerceIn(PREVIEW_MIN_HEIGHT, PREVIEW_MAX_HEIGHT)
        }

        private fun previewItems(summary: ChapterCommentSummary): List<String> {
            return summary.previews.asSequence()
                .map { it.replace(WHITESPACE_REGEX, " ").trim() }
                .filter(String::isNotEmpty)
                .take(ChapterCommentParser.MAX_PREVIEWS)
                .toList()
        }

        private fun titleTextSize(): Float = (ChapterProvider.contentPaint.textSize * 0.74f)
            .coerceIn(MIN_TITLE_TEXT_SIZE, MAX_TITLE_TEXT_SIZE)

        private fun previewTextSize(): Float = (ChapterProvider.contentPaint.textSize * 0.62f)
            .coerceIn(MIN_PREVIEW_TEXT_SIZE, MAX_PREVIEW_TEXT_SIZE)

        private fun previewLines(text: String, paint: TextPaint, maxWidth: Float): List<String> {
            if (text.isEmpty() || maxWidth <= 0f) return emptyList()
            val measuredLength = paint.breakText(text, true, maxWidth, null)
            val firstLength = safeCharacterBoundary(text, measuredLength)
            if (firstLength <= 0) {
                return listOf(
                    TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
                ).filter(String::isNotEmpty)
            }
            if (firstLength >= text.length) return listOf(text)
            val first = text.substring(0, firstLength).trimEnd()
            val remaining = text.substring(firstLength).trimStart()
            val second = TextUtils.ellipsize(
                remaining,
                paint,
                maxWidth,
                TextUtils.TruncateAt.END,
            ).toString()
            return listOf(first, second).filter(String::isNotEmpty)
        }

        private fun safeCharacterBoundary(text: String, measuredLength: Int): Int {
            if (measuredLength <= 0 || measuredLength >= text.length) {
                return measuredLength.coerceIn(0, text.length)
            }
            val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
            return if (iterator.isBoundary(measuredLength)) {
                measuredLength
            } else {
                iterator.preceding(measuredLength).coerceAtLeast(0)
            }
        }
    }
}
