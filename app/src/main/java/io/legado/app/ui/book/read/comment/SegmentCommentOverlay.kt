package io.legado.app.ui.book.read.comment

import android.graphics.Canvas
import android.graphics.Paint
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
    )

    fun entries(page: TextPage): List<Entry> {
        val rule = page.textChapter.chapterCommentRule?.display?.segment
        if (rule?.enabled != true) return emptyList()
        val preset = rule.preset?.takeIf { it in SUPPORTED_PRESETS } ?: PRESET_COUNT
        if (preset == PRESET_NONE) return emptyList()
        val anchors = page.textChapter.chapterCommentAnchors
        if (anchors.isEmpty() || page.lines.isEmpty()) return emptyList()

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
                PRESET_LABEL_COUNT -> listOfNotNull(rule.label?.trim()?.takeIf(String::isNotEmpty), count.toString())
                    .joinToString(" ")
                else -> count.toString()
            }
            val visualWidth = when (preset) {
                PRESET_DOT -> DOT_SIZE
                else -> max(MIN_VISUAL_WIDTH, TEXT_PAINT.measureText(label) + HORIZONTAL_PADDING * 2)
            }
            val halfRight = if (page.doublePage && line.isLeftLine) {
                ChapterProvider.viewWidth / 2f - ChapterProvider.paddingRight
            } else {
                ChapterProvider.visibleRight.toFloat()
            }
            val leftBoundary = if (page.doublePage && !line.isLeftLine) {
                ChapterProvider.viewWidth / 2f + ChapterProvider.paddingLeft
            } else {
                ChapterProvider.paddingLeft.toFloat()
            }
            val left = min(
                max(line.lineEnd + ENTRY_GAP, leftBoundary),
                halfRight - visualWidth,
            )
            val top = line.lineTop + (line.height - VISUAL_HEIGHT) / 2f
            val visual = RectF(left, top, left + visualWidth, top + VISUAL_HEIGHT)
            val hit = RectF(visual).apply {
                inset(
                    -max(0f, (MIN_TOUCH_SIZE - width()) / 2f),
                    -max(0f, (MIN_TOUCH_SIZE - height()) / 2f),
                )
            }
            Entry(anchor, visual, hit, label, preset)
        }
    }

    fun draw(page: TextPage, canvas: Canvas) {
        val textColor = ReadBookConfig.textColor
        entries(page).forEach { entry ->
            if (entry.preset == PRESET_DOT) {
                DOT_PAINT.color = ColorUtils.setAlphaComponent(textColor, 170)
                canvas.drawCircle(
                    entry.visualBounds.centerX(),
                    entry.visualBounds.centerY(),
                    DOT_SIZE / 2f,
                    DOT_PAINT,
                )
            } else {
                BACKGROUND_PAINT.color = ColorUtils.setAlphaComponent(textColor, 24)
                TEXT_PAINT.color = ColorUtils.setAlphaComponent(textColor, 180)
                canvas.drawRoundRect(entry.visualBounds, CORNER_RADIUS, CORNER_RADIUS, BACKGROUND_PAINT)
                val baseline = entry.visualBounds.centerY() - (TEXT_PAINT.ascent() + TEXT_PAINT.descent()) / 2f
                canvas.drawText(entry.label, entry.visualBounds.centerX(), baseline, TEXT_PAINT)
            }
        }
    }

    fun hitTest(page: TextPage, x: Float, y: Float, relativeOffset: Float): Entry? {
        val localY = y - relativeOffset
        return entries(page).firstOrNull { it.hitBounds.contains(x, localY) }
    }

    private const val PRESET_DOT = "dot"
    private const val PRESET_COUNT = "count"
    private const val PRESET_LABEL_COUNT = "labelCount"
    private const val PRESET_NONE = "none"
    private val SUPPORTED_PRESETS = setOf(PRESET_DOT, PRESET_COUNT, PRESET_LABEL_COUNT, PRESET_NONE)
    private val DOT_SIZE = 8.dpToPx().toFloat()
    private val VISUAL_HEIGHT = 24.dpToPx().toFloat()
    private val MIN_VISUAL_WIDTH = 28.dpToPx().toFloat()
    private val MIN_TOUCH_SIZE = 44.dpToPx().toFloat()
    private val ENTRY_GAP = 4.dpToPx().toFloat()
    private val HORIZONTAL_PADDING = 7.dpToPx().toFloat()
    private val CORNER_RADIUS = 6.dpToPx().toFloat()
    private val BACKGROUND_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val DOT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val TEXT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12.dpToPx().toFloat()
    }
}
