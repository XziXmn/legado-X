package io.legado.app.ui.book.read.comment

import io.legado.app.model.chapterComment.ChapterCommentPageProjector
import io.legado.app.ui.book.read.page.entities.TextPage

/** Resolves the source-neutral page comment label shown in the reader header. */
object PageCommentOverlay {

    fun label(page: TextPage): String? {
        val rule = page.textChapter.chapterCommentRule?.display?.page
        if (rule?.enabled != true || rule.preset == PRESET_NONE || page.lines.isEmpty()) {
            return null
        }
        val projection = ChapterCommentPageProjector.project(
            page.lines.asSequence().map { it.paragraphNum }.asIterable(),
            page.textChapter.chapterCommentAnchors,
        )
        if (projection.isEmpty) return null
        val count = ChapterCommentPageProjector.count(projection, rule.countField)
        val prefix = rule.label?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_LABEL
        return "$prefix $count"
    }

    private const val PRESET_NONE = "none"
    private const val DEFAULT_LABEL = "热评"
}
