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
 * Preview body is a single [StaticLayout] built once from the full preview
 * strings. Height comes from [StaticLayout.getHeight] — no secondary
 * ellipsize / line-budget pass that previously cut short author notes.
 */
data class ChapterCommentPageBlock(
    val summary: ChapterCommentSummary,
    override val top: Float,
    override val height: Float,
    /** Full preview text drawn via StaticLayout (may be empty). */
    private val previewText: String,
    private val previewLayoutWidth: Int,
) : TextPageBlock {

    val isInteractive: Boolean
        get() = summary.actionData != null

    override fun draw(canvas: Canvas) {
        val left = ChapterProvider.paddingLeft.toFloat()
        val right = left + ChapterProvider.visibleWidth.toFloat().coerceAtLeast(1f)
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

        if (previewText.isEmpty() || previewLayoutWidth <= 0) return
        val previewPaint = newPreviewPaint(ColorUtils.setAlphaComponent(textColor, 175))
        val titleHeight = labelPaint.descent() - labelPaint.ascent()
        val previewTop = top + TOP_PADDING + titleHeight + TITLE_PREVIEW_GAP
        val maxPreviewHeight = max(
            0f,
            height - TOP_PADDING - titleHeight - TITLE_PREVIEW_GAP - BOTTOM_PADDING,
        )
        // Full text; only apply END ellipsize when the page budget cannot hold it.
        val layout = buildStaticLayout(
            text = previewText,
            paint = previewPaint,
            widthPx = previewLayoutWidth,
            maxHeight = maxPreviewHeight,
        )
        canvas.save()
        canvas.translate(contentLeft, previewTop)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun contains(x: Float, y: Float, relativeOffset: Float): Boolean {
        if (!isInteractive) return false
        val localY = y - relativeOffset
        val left = ChapterProvider.paddingLeft.toFloat()
        val right = left + ChapterProvider.visibleWidth.toFloat().coerceAtLeast(1f)
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
        /** Fallback content width when ChapterProvider has not been sized yet. */
        private val FALLBACK_CONTENT_WIDTH = 300.dpToPx()
        private val WHITESPACE_REGEX = Regex("[ \\t\\x0B\\f\\r]+")

        private data class MeasuredPreview(
            val text: String,
            val widthPx: Int,
            val height: Float,
        )

        fun create(summary: ChapterCommentSummary, top: Float): ChapterCommentPageBlock {
            val measured = measure(summary)
            return ChapterCommentPageBlock(
                summary = summary,
                top = top,
                height = measured.height,
                previewText = measured.text,
                previewLayoutWidth = measured.widthPx,
            )
        }

        fun preferredHeight(summary: ChapterCommentSummary): Float = measure(summary).height

        private fun measure(summary: ChapterCommentSummary): MeasuredPreview {
            val text = joinPreviews(previewItems(summary))
            if (text.isEmpty()) {
                return MeasuredPreview("", 0, DEFAULT_HEIGHT)
            }
            val titlePaint = newTitlePaint()
            val previewPaint = newPreviewPaint()
            val titleHeight = titlePaint.descent() - titlePaint.ascent()
            val widthPx = contentMaxWidthPx()
            val fullLayout = buildStaticLayout(
                text = text,
                paint = previewPaint,
                widthPx = widthPx,
                maxHeight = Float.POSITIVE_INFINITY,
            )
            val natural = TOP_PADDING + titleHeight + TITLE_PREVIEW_GAP +
                    fullLayout.height + BOTTOM_PADDING
            val height = natural
                .coerceAtLeast(PREVIEW_MIN_HEIGHT)
                .coerceAtMost(maxBlockHeight())
            return MeasuredPreview(text, widthPx, height)
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

        private fun maxBlockHeight(): Float {
            val pageBudget = ChapterProvider.visibleHeight - TOP_GAP - END_PADDING
            return max(PREVIEW_MIN_HEIGHT, pageBudget.toFloat())
        }

        private fun contentMaxWidthPx(): Int {
            val fromColumn = ChapterProvider.visibleWidth - HORIZONTAL_PADDING * 2
            val width = if (fromColumn > 1f) fromColumn else FALLBACK_CONTENT_WIDTH.toFloat()
            return max(1, width.toInt())
        }

        private fun previewItems(summary: ChapterCommentSummary): List<String> {
            return summary.previews.asSequence()
                .map { raw ->
                    raw.replace(WHITESPACE_REGEX, " ")
                        .replace(Regex("\\n{3,}"), "\n\n")
                        .trim()
                }
                .filter(String::isNotEmpty)
                .take(ChapterCommentParser.MAX_PREVIEWS)
                .toList()
        }

        private fun joinPreviews(previews: List<String>): String {
            return previews.joinToString("\n")
        }

        private fun titleTextSize(): Float = (ChapterProvider.contentPaint.textSize * 0.74f)
            .coerceIn(MIN_TITLE_TEXT_SIZE, MAX_TITLE_TEXT_SIZE)

        private fun previewTextSize(): Float = (ChapterProvider.contentPaint.textSize * 0.62f)
            .coerceIn(MIN_PREVIEW_TEXT_SIZE, MAX_PREVIEW_TEXT_SIZE)

        /**
         * @param maxHeight when finite and content overflows, cap lines with END ellipsis.
         *                  Use [Float.POSITIVE_INFINITY] for full unclipped measure.
         */
        private fun buildStaticLayout(
            text: String,
            paint: TextPaint,
            widthPx: Int,
            maxHeight: Float,
        ): StaticLayout {
            val width = max(1, widthPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                if (maxHeight.isFinite() && maxHeight > 0f) {
                    // Probe full height first without ellipsize.
                    val probe = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1f)
                        .setIncludePad(false)
                        .build()
                    if (probe.height > maxHeight) {
                        val lineHeight = max(1, probe.height / max(1, probe.lineCount))
                        val maxLines = max(1, (maxHeight / lineHeight).toInt())
                        builder.setMaxLines(maxLines)
                            .setEllipsize(TextUtils.TruncateAt.END)
                    }
                }
                return builder.build()
            }
            @Suppress("DEPRECATION")
            val full = StaticLayout(
                text,
                paint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1f,
                0f,
                false,
            )
            if (!maxHeight.isFinite() || maxHeight <= 0f || full.height <= maxHeight) {
                return full
            }
            val lineHeight = max(1, full.height / max(1, full.lineCount))
            val maxLines = max(1, (maxHeight / lineHeight).toInt())
            // Pre-API 23: approximate by truncating string to visible lines.
            val end = full.getLineEnd((maxLines - 1).coerceAtMost(full.lineCount - 1))
            val clipped = text.substring(0, end).trimEnd() + "…"
            @Suppress("DEPRECATION")
            return StaticLayout(
                clipped,
                paint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1f,
                0f,
                false,
            )
        }
    }
}
