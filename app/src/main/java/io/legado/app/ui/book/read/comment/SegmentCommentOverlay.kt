package io.legado.app.ui.book.read.comment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.chapterComment.ResolvedChapterCommentSegment
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import kotlin.math.max
import kotlin.math.min

/** Reader-owned rendering and hit testing for source-neutral segment entries. */
object SegmentCommentOverlay {

    data class Entry(
        val anchor: ResolvedChapterCommentSegment,
        val visualBounds: RectF,
        val hitBounds: RectF,
        val label: String,
        val preset: String,
        val textSize: Float,
        val tailWidth: Float,
    )

    fun entries(page: TextPage): List<Entry> {
        val rule = page.textChapter.chapterCommentRule?.display?.segment
        if (rule?.enabled != true) return emptyList()
        val preset = rule.preset?.takeIf { it in SUPPORTED_PRESETS } ?: PRESET_COUNT
        if (preset == PRESET_NONE) return emptyList()
        val anchors = page.textChapter.chapterCommentAnchors
        if (anchors.isEmpty() || page.lines.isEmpty()) return emptyList()
        val textSize = commentTextSize()
        val visualHeight = visualHeight(textSize)
        val tailWidth = tailWidth(textSize)
        val horizontalPadding = horizontalPadding(textSize)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize }

        return anchors.mapNotNull { anchor ->
            val line = page.lines.lastOrNull { it.paragraphNum in anchor.paragraphNumbers }
                ?: return@mapNotNull null
            val count = if (rule.countField == "hot") {
                anchor.segment.counts.hot
            } else {
                anchor.segment.counts.total
            }
            val label = when (preset) {
                PRESET_DOT -> ""
                PRESET_LABEL_COUNT -> listOfNotNull(
                    rule.label?.trim()?.takeIf(String::isNotEmpty),
                    displayCount(count),
                )
                    .joinToString(" ")
                else -> displayCount(count)
            }
            val visualWidth = when (preset) {
                PRESET_DOT -> DOT_SIZE
                else -> max(
                    MIN_VISUAL_WIDTH,
                    textPaint.measureText(label) + horizontalPadding * 2 + tailWidth,
                )
            }
            val rightBoundary = if (page.doublePage && line.isLeftLine) {
                ChapterProvider.viewWidth / 2f - OUTER_MARGIN
            } else {
                ChapterProvider.visibleRight.toFloat() - OUTER_MARGIN
            }
            val leftBoundary = if (page.doublePage && !line.isLeftLine) {
                ChapterProvider.viewWidth / 2f + ChapterProvider.paddingLeft
            } else {
                ChapterProvider.paddingLeft.toFloat()
            }
            val maxLeft = max(leftBoundary, rightBoundary - visualWidth)
            val left = min(max(line.lineEnd + ENTRY_GAP, leftBoundary), maxLeft)
            val top = line.lineTop + (line.height - visualHeight) / 2f
            val visual = RectF(left, top, left + visualWidth, top + visualHeight)
            val hit = RectF(visual).apply {
                inset(
                    -max(0f, (MIN_TOUCH_SIZE - width()) / 2f),
                    -max(0f, (MIN_TOUCH_SIZE - height()) / 2f),
                )
            }
            Entry(anchor, visual, hit, label, preset, textSize, tailWidth)
        }
    }

    fun draw(page: TextPage, canvas: Canvas) {
        val textColor = ReadBookConfig.textColor
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ColorUtils.setAlphaComponent(textColor, 170)
        }
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = ColorUtils.setAlphaComponent(textColor, 125)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = ColorUtils.setAlphaComponent(textColor, 180)
        }
        entries(page).forEach { entry ->
            if (entry.preset == PRESET_DOT) {
                canvas.drawCircle(
                    entry.visualBounds.centerX(),
                    entry.visualBounds.centerY(),
                    DOT_SIZE / 2f,
                    dotPaint,
                )
            } else {
                outlinePaint.strokeWidth = max(MIN_STROKE_WIDTH, entry.textSize * 0.06f)
                textPaint.textSize = entry.textSize
                drawBubble(canvas, entry.visualBounds, entry.tailWidth, outlinePaint)
                val textCenterX = (entry.visualBounds.left + entry.tailWidth + entry.visualBounds.right) / 2f
                val baseline = entry.visualBounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(entry.label, textCenterX, baseline, textPaint)
            }
        }
    }

    fun hitTest(page: TextPage, x: Float, y: Float, relativeOffset: Float): Entry? {
        val localY = y - relativeOffset
        return entries(page).firstOrNull { it.hitBounds.contains(x, localY) }
    }

    private fun displayCount(count: Int): String = if (count > 999) "999+" else count.toString()

    private fun commentTextSize(): Float = (ChapterProvider.contentPaint.textSize * TEXT_SIZE_SCALE)
        .coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)

    private fun visualHeight(textSize: Float): Float = (textSize * HEIGHT_SCALE)
        .coerceIn(MIN_VISUAL_HEIGHT, MAX_VISUAL_HEIGHT)

    private fun tailWidth(textSize: Float): Float = (textSize * TAIL_SCALE)
        .coerceIn(MIN_TAIL_WIDTH, MAX_TAIL_WIDTH)

    private fun horizontalPadding(textSize: Float): Float = (textSize * HORIZONTAL_PADDING_SCALE)
        .coerceIn(MIN_HORIZONTAL_PADDING, MAX_HORIZONTAL_PADDING)

    private fun drawBubble(canvas: Canvas, bounds: RectF, tailWidth: Float, paint: Paint) {
        val bodyLeft = bounds.left + tailWidth
        val radius = min(CORNER_RADIUS, bounds.height() / 3f)
        val centerY = bounds.centerY()
        val tailHalfHeight = min(TAIL_HALF_HEIGHT, bounds.height() / 5f)
        val path = Path().apply {
            moveTo(bodyLeft + radius, bounds.top)
            lineTo(bounds.right - radius, bounds.top)
            quadTo(bounds.right, bounds.top, bounds.right, bounds.top + radius)
            lineTo(bounds.right, bounds.bottom - radius)
            quadTo(bounds.right, bounds.bottom, bounds.right - radius, bounds.bottom)
            lineTo(bodyLeft + radius, bounds.bottom)
            quadTo(bodyLeft, bounds.bottom, bodyLeft, bounds.bottom - radius)
            lineTo(bodyLeft, centerY + tailHalfHeight)
            lineTo(bounds.left, centerY)
            lineTo(bodyLeft, centerY - tailHalfHeight)
            lineTo(bodyLeft, bounds.top + radius)
            quadTo(bodyLeft, bounds.top, bodyLeft + radius, bounds.top)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private const val PRESET_DOT = "dot"
    private const val PRESET_COUNT = "count"
    private const val PRESET_LABEL_COUNT = "labelCount"
    private const val PRESET_NONE = "none"
    private const val TEXT_SIZE_SCALE = 0.42f
    private const val HEIGHT_SCALE = 1.65f
    private const val TAIL_SCALE = 0.30f
    private const val HORIZONTAL_PADDING_SCALE = 0.34f
    private val SUPPORTED_PRESETS = setOf(PRESET_DOT, PRESET_COUNT, PRESET_LABEL_COUNT, PRESET_NONE)
    private val DOT_SIZE = 8.dpToPx().toFloat()
    private val MIN_TEXT_SIZE = 9.dpToPx().toFloat()
    private val MAX_TEXT_SIZE = 12.dpToPx().toFloat()
    private val MIN_VISUAL_HEIGHT = 17.dpToPx().toFloat()
    private val MAX_VISUAL_HEIGHT = 22.dpToPx().toFloat()
    private val MIN_VISUAL_WIDTH = 20.dpToPx().toFloat()
    private val MIN_TOUCH_SIZE = 44.dpToPx().toFloat()
    private val ENTRY_GAP = 3.dpToPx().toFloat()
    private val OUTER_MARGIN = 2.dpToPx().toFloat()
    private val MIN_TAIL_WIDTH = 3.dpToPx().toFloat()
    private val MAX_TAIL_WIDTH = 5.dpToPx().toFloat()
    private val MIN_HORIZONTAL_PADDING = 3.dpToPx().toFloat()
    private val MAX_HORIZONTAL_PADDING = 5.dpToPx().toFloat()
    private val CORNER_RADIUS = 4.dpToPx().toFloat()
    private val TAIL_HALF_HEIGHT = 3.dpToPx().toFloat()
    private val MIN_STROKE_WIDTH = 0.75f.dpToPx()
}
