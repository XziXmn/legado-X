package io.legado.app.ui.book.read.page.entities

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.chapterComment.ChapterCommentParser
import io.legado.app.model.chapterComment.ChapterCommentSummary
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import java.text.BreakIterator
import kotlin.math.floor
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

        val labelPaint = newTitlePaint(textColor)
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
        if (previews.isEmpty()) return

        // Must match preferredHeight(): same typeface/size/width → same wrap lines.
        val previewPaint = newPreviewPaint(ColorUtils.setAlphaComponent(textColor, 175))
        val maxWidth = contentMaxWidth()
        val firstBaseline = titleBaseline + TITLE_PREVIEW_GAP - previewPaint.ascent()
        val lineHeight = previewLineHeight(previewPaint)
        val titleHeight = labelPaint.descent() - labelPaint.ascent()
        val availablePreviewHeight = max(
            0f,
            height - TOP_PADDING - titleHeight - TITLE_PREVIEW_GAP - BOTTOM_PADDING,
        )
        val maxLines = max(1, floor(availablePreviewHeight / lineHeight).toInt())
        val lines = fitPreviewLines(
            layoutPreviewLines(previews, previewPaint, maxWidth),
            maxLines,
            previewPaint,
            maxWidth,
        )
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, contentLeft, firstBaseline + index * lineHeight, previewPaint)
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
        /** Hard safety against pathological wrap loops (512-char previews). */
        private const val MAX_WRAP_LINES = 256
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun preferredHeight(summary: ChapterCommentSummary): Float {
            val previews = previewItems(summary)
            if (previews.isEmpty()) return DEFAULT_HEIGHT
            // Same metrics as draw(): typeface + size + content width.
            val titlePaint = newTitlePaint()
            val previewPaint = newPreviewPaint()
            val titleHeight = titlePaint.descent() - titlePaint.ascent()
            val lineHeight = previewLineHeight(previewPaint)
            val maxWidth = contentMaxWidth()
            val wrapped = layoutPreviewLines(previews, previewPaint, maxWidth)
            val lineCount = max(1, wrapped.size)
            val natural = TOP_PADDING + titleHeight + TITLE_PREVIEW_GAP +
                    lineHeight * lineCount + BOTTOM_PADDING
            return natural.coerceIn(PREVIEW_MIN_HEIGHT, maxBlockHeight())
        }

        private fun newTitlePaint(color: Int = ReadBookConfig.textColor): TextPaint {
            return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = titleTextSize()
                typeface = ChapterProvider.typeface
            }
        }

        private fun newPreviewPaint(color: Int = ReadBookConfig.textColor): TextPaint {
            return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = previewTextSize()
                typeface = ChapterProvider.typeface
            }
        }

        private fun previewLineHeight(paint: TextPaint): Float {
            return (paint.descent() - paint.ascent()) * PREVIEW_LINE_SPACING
        }

        private fun maxBlockHeight(): Float {
            val pageBudget = ChapterProvider.visibleHeight - TOP_GAP - END_PADDING
            return max(PREVIEW_MIN_HEIGHT, pageBudget.toFloat())
        }

        private fun contentMaxWidth(): Float {
            val fromEdges = ChapterProvider.visibleRight - ChapterProvider.paddingLeft -
                    HORIZONTAL_PADDING * 2
            if (fromEdges > 1f) return fromEdges
            // Fallback when edge fields are not ready yet during layout.
            val fromVisible = ChapterProvider.visibleWidth - HORIZONTAL_PADDING * 2
            return max(0f, fromVisible.toFloat())
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

        /**
         * Wrap every preview fully (no 2-line / 1-line ellipsize cap).
         * Author notes and chapter-comment snippets share this path.
         */
        private fun layoutPreviewLines(
            previews: List<String>,
            paint: TextPaint,
            maxWidth: Float,
        ): List<String> {
            if (maxWidth <= 0f) return emptyList()
            return previews.flatMap { wrapText(it, paint, maxWidth) }
        }

        private fun wrapText(text: String, paint: TextPaint, maxWidth: Float): List<String> {
            if (text.isEmpty() || maxWidth <= 0f) return emptyList()
            val lines = ArrayList<String>()
            var remaining = text
            var guard = 0
            while (remaining.isNotEmpty() && guard++ < MAX_WRAP_LINES) {
                val measuredLength = paint.breakText(remaining, true, maxWidth, null)
                var cut = safeCharacterBoundary(remaining, measuredLength)
                if (cut <= 0) {
                    // Force progress so a zero-width break cannot stall the loop.
                    cut = remaining.offsetByCodePoints(0, 1).coerceAtMost(remaining.length)
                    val piece = remaining.substring(0, cut)
                    val fallback = TextUtils.ellipsize(
                        piece,
                        paint,
                        maxWidth,
                        TextUtils.TruncateAt.END,
                    ).toString()
                    if (fallback.isNotEmpty()) lines.add(fallback)
                    remaining = remaining.substring(cut).trimStart()
                    continue
                }
                if (cut >= remaining.length) {
                    lines.add(remaining)
                    break
                }
                val line = remaining.substring(0, cut).trimEnd()
                if (line.isNotEmpty()) {
                    lines.add(line)
                } else {
                    // Whitespace-only slice: skip without stalling.
                    remaining = remaining.substring(cut).trimStart()
                    continue
                }
                remaining = remaining.substring(cut).trimStart()
            }
            return lines
        }

        /**
         * Prefer full text; only ellipsize when content exceeds the allocated block
         * (typically a full reading page after [maxBlockHeight] clamping).
         */
        private fun fitPreviewLines(
            lines: List<String>,
            maxLines: Int,
            paint: TextPaint,
            maxWidth: Float,
        ): List<String> {
            if (lines.isEmpty() || maxLines <= 0) return emptyList()
            if (lines.size <= maxLines) return lines
            if (maxLines == 1) {
                return listOf(
                    TextUtils.ellipsize(
                        lines.joinToString(""),
                        paint,
                        maxWidth,
                        TextUtils.TruncateAt.END,
                    ).toString(),
                ).filter(String::isNotEmpty)
            }
            val head = lines.take(maxLines - 1)
            val rest = lines.drop(maxLines - 1).joinToString("")
            val last = TextUtils.ellipsize(
                rest,
                paint,
                maxWidth,
                TextUtils.TruncateAt.END,
            ).toString()
            return (head + last).filter(String::isNotEmpty)
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
