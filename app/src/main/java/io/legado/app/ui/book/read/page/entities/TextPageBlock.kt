package io.legado.app.ui.book.read.page.entities

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.chapterComment.ChapterCommentParser
import io.legado.app.model.chapterComment.ChapterCommentSummary
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import kotlin.math.floor
import kotlin.math.max

sealed interface TextPageBlock {
    val top: Float
    val height: Float
    val bottom: Float get() = top + height
    fun draw(canvas: Canvas)
    fun contains(x: Float, y: Float, relativeOffset: Float): Boolean
}

/**
 * Chapter-end author / chapter-comment card.
 *
 * Preview lines are laid out **once** at construction ([create] / [measure]) so
 * height and draw always share the same wrap result. Re-wrapping in [draw] with
 * a different paint/width previously produced a short card + ellipsis for long
 * single-preview author notes.
 */
data class ChapterCommentPageBlock(
    val summary: ChapterCommentSummary,
    override val top: Float,
    override val height: Float,
    private val previewLines: List<String>,
) : TextPageBlock {

    val isInteractive: Boolean
        get() = summary.actionData != null

    override fun draw(canvas: Canvas) {
        val left = ChapterProvider.paddingLeft.toFloat()
        // Match body text column (visibleWidth), not full-screen visibleRight.
        val right = left + ChapterProvider.visibleWidth
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

        if (previewLines.isEmpty()) return
        val previewPaint = newPreviewPaint(ColorUtils.setAlphaComponent(textColor, 175))
        val firstBaseline = titleBaseline + TITLE_PREVIEW_GAP - previewPaint.ascent()
        val lineHeight = previewLineHeight(previewPaint)
        previewLines.forEachIndexed { index, line ->
            canvas.drawText(line, contentLeft, firstBaseline + index * lineHeight, previewPaint)
        }
    }

    override fun contains(x: Float, y: Float, relativeOffset: Float): Boolean {
        if (!isInteractive) return false
        val localY = y - relativeOffset
        val left = ChapterProvider.paddingLeft.toFloat()
        val right = left + ChapterProvider.visibleWidth
        return x in left..right && localY in top..bottom
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
        private val WHITESPACE_REGEX = Regex("[ \\t\\x0B\\f\\r]+")

        private data class MeasuredPreview(
            val lines: List<String>,
            val height: Float,
        )

        fun create(summary: ChapterCommentSummary, top: Float): ChapterCommentPageBlock {
            val measured = measure(summary)
            return ChapterCommentPageBlock(summary, top, measured.height, measured.lines)
        }

        fun preferredHeight(summary: ChapterCommentSummary): Float = measure(summary).height

        private fun measure(summary: ChapterCommentSummary): MeasuredPreview {
            val previews = previewItems(summary)
            if (previews.isEmpty()) {
                return MeasuredPreview(emptyList(), DEFAULT_HEIGHT)
            }
            val titlePaint = newTitlePaint()
            val previewPaint = newPreviewPaint()
            val titleHeight = titlePaint.descent() - titlePaint.ascent()
            val lineHeight = previewLineHeight(previewPaint)
            val maxWidth = contentMaxWidth()
            val fullLines = layoutPreviewLines(previews, previewPaint, maxWidth)
            val lineCount = max(1, fullLines.size)
            val natural = TOP_PADDING + titleHeight + TITLE_PREVIEW_GAP +
                    lineHeight * lineCount + BOTTOM_PADDING
            val height = natural.coerceIn(PREVIEW_MIN_HEIGHT, maxBlockHeight())
            val chrome = TOP_PADDING + titleHeight + TITLE_PREVIEW_GAP + BOTTOM_PADDING
            val available = max(0f, height - chrome)
            val maxLines = max(1, floor(available / lineHeight).toInt())
            val lines = fitPreviewLines(fullLines, maxLines, previewPaint, maxWidth)
            return MeasuredPreview(lines, height)
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

        /**
         * Body text column width. Do **not** use visibleRight - paddingLeft:
         * on double-page, visibleRight is the full screen edge while content
         * only uses half ([ChapterProvider.visibleWidth]).
         */
        private fun contentMaxWidth(): Float {
            return max(0f, ChapterProvider.visibleWidth - HORIZONTAL_PADDING * 2)
        }

        private fun previewItems(summary: ChapterCommentSummary): List<String> {
            return summary.previews.asSequence()
                // Keep paragraph breaks; only collapse horizontal whitespace.
                .map { raw ->
                    raw.replace(WHITESPACE_REGEX, " ")
                        .replace(Regex("\\n{3,}"), "\n\n")
                        .trim()
                }
                .filter(String::isNotEmpty)
                .take(ChapterCommentParser.MAX_PREVIEWS)
                .toList()
        }

        private fun titleTextSize(): Float = (ChapterProvider.contentPaint.textSize * 0.74f)
            .coerceIn(MIN_TITLE_TEXT_SIZE, MAX_TITLE_TEXT_SIZE)

        private fun previewTextSize(): Float = (ChapterProvider.contentPaint.textSize * 0.62f)
            .coerceIn(MIN_PREVIEW_TEXT_SIZE, MAX_PREVIEW_TEXT_SIZE)

        private fun layoutPreviewLines(
            previews: List<String>,
            paint: TextPaint,
            maxWidth: Float,
        ): List<String> {
            if (maxWidth <= 0f) return emptyList()
            return previews.flatMap { preview ->
                // Wrap each paragraph separately so newlines from author notes survive.
                preview.split('\n').flatMap { paragraph ->
                    val text = paragraph.trim()
                    if (text.isEmpty()) emptyList() else wrapText(text, paint, maxWidth)
                }
            }
        }

        private fun wrapText(text: String, paint: TextPaint, maxWidth: Float): List<String> {
            if (text.isEmpty() || maxWidth <= 0f) return emptyList()
            val widthPx = max(1, maxWidth.toInt())
            val layout = buildStaticLayout(text, paint, widthPx)
            return (0 until layout.lineCount).mapNotNull { index ->
                val start = layout.getLineStart(index)
                val end = layout.getLineEnd(index)
                if (start >= end) return@mapNotNull null
                text.substring(start, end).trimEnd().takeIf(String::isNotEmpty)
            }
        }

        private fun buildStaticLayout(text: String, paint: TextPaint, widthPx: Int): StaticLayout {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(
                    text,
                    paint,
                    widthPx,
                    Layout.Alignment.ALIGN_NORMAL,
                    1f,
                    0f,
                    false,
                )
            }
        }

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
    }
}
