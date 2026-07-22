package io.legado.app.ui.book.read.page.entities

import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.chapterComment.ChapterCommentSummary
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx

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
    override val height: Float = DEFAULT_HEIGHT,
) : TextPageBlock {

    override fun draw(canvas: Canvas) {
        val left = ChapterProvider.paddingLeft.toFloat()
        val right = ChapterProvider.visibleRight.toFloat()
        val textColor = ReadBookConfig.textColor
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(textColor, 20)
        }
        canvas.drawRoundRect(left, top, right, bottom, 8.dpToPx().toFloat(), 8.dpToPx().toFloat(), backgroundPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = ChapterProvider.contentPaint.textSize * 0.88f
        }
        val countPaint = Paint(labelPaint).apply {
            color = ColorUtils.setAlphaComponent(textColor, 150)
            textSize = labelPaint.textSize * 0.88f
        }
        val centerY = top + height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(summary.label, left + 16.dpToPx(), centerY, labelPaint)
        val count = summary.counts.total
        if (count > 0) {
            val countText = count.toString()
            canvas.drawText(
                countText,
                right - 30.dpToPx() - countPaint.measureText(countText),
                centerY,
                countPaint,
            )
        }
        canvas.drawText(">", right - 20.dpToPx(), centerY, countPaint)
    }

    override fun contains(x: Float, y: Float, relativeOffset: Float): Boolean {
        val localY = y - relativeOffset
        return x in ChapterProvider.paddingLeft.toFloat()..ChapterProvider.visibleRight.toFloat() &&
                localY in top..bottom
    }

    companion object {
        val DEFAULT_HEIGHT = 56.dpToPx().toFloat()
        val TOP_GAP = 12.dpToPx().toFloat()
        val END_PADDING = 20.dpToPx().toFloat()
    }
}
