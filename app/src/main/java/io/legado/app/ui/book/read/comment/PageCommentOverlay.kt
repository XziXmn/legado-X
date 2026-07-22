package io.legado.app.ui.book.read.comment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.chapterComment.ChapterCommentPageProjector
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import kotlin.math.max

/** Draws a source-neutral page comment hint for each visible page pane. */
object PageCommentOverlay {

    data class Entry(
        val visualBounds: RectF,
        val label: String,
        val leftPage: Boolean?,
    )

    fun entries(page: TextPage): List<Entry> {
        val rule = page.textChapter.chapterCommentRule?.display?.page
        if (rule?.enabled != true || rule.preset == PRESET_NONE || page.lines.isEmpty()) {
            return emptyList()
        }
        val panes = if (page.doublePage) listOf(true, false) else listOf(null)
        return panes.mapNotNull { leftPage ->
            val hasLines = page.lines.any { line ->
                !page.doublePage || line.isLeftLine == leftPage
            }
            if (!hasLines) return@mapNotNull null
            val projection = page.textChapter.pageCommentProjection(page, leftPage)
            if (projection.isEmpty) return@mapNotNull null
            val count = ChapterCommentPageProjector.count(projection, rule.countField)
            val prefix = rule.label?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_LABEL
            val label = "$prefix $count"
            val visualWidth = max(MIN_VISUAL_WIDTH, TEXT_PAINT.measureText(label) + HORIZONTAL_PADDING * 2)
            val right = if (page.doublePage && leftPage == true) {
                ChapterProvider.viewWidth / 2f - ChapterProvider.paddingRight
            } else {
                ChapterProvider.visibleRight.toFloat()
            }
            val leftBoundary = if (page.doublePage && leftPage == false) {
                ChapterProvider.viewWidth / 2f + ChapterProvider.paddingLeft
            } else {
                ChapterProvider.paddingLeft.toFloat()
            }
            val left = max(leftBoundary, right - visualWidth)
            val top = page.paddingTop.toFloat() + TOP_OFFSET
            Entry(RectF(left, top, right, top + VISUAL_HEIGHT), label, leftPage)
        }
    }

    fun draw(page: TextPage, canvas: Canvas) {
        val textColor = ReadBookConfig.textColor
        BACKGROUND_PAINT.color = ColorUtils.setAlphaComponent(textColor, 34)
        TEXT_PAINT.color = ColorUtils.setAlphaComponent(textColor, 190)
        entries(page).forEach { entry ->
            canvas.drawRoundRect(entry.visualBounds, CORNER_RADIUS, CORNER_RADIUS, BACKGROUND_PAINT)
            val baseline = entry.visualBounds.centerY() - (TEXT_PAINT.ascent() + TEXT_PAINT.descent()) / 2f
            canvas.drawText(entry.label, entry.visualBounds.centerX(), baseline, TEXT_PAINT)
        }
    }

    private const val PRESET_NONE = "none"
    private const val DEFAULT_LABEL = "热评"
    private val VISUAL_HEIGHT = 28.dpToPx().toFloat()
    private val MIN_VISUAL_WIDTH = 72.dpToPx().toFloat()
    private val HORIZONTAL_PADDING = 10.dpToPx().toFloat()
    private val TOP_OFFSET = 6.dpToPx().toFloat()
    private val CORNER_RADIUS = 5.dpToPx().toFloat()
    private val BACKGROUND_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val TEXT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13.dpToPx().toFloat()
    }
}
