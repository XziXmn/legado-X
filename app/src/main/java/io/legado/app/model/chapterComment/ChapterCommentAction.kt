package io.legado.app.model.chapterComment

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.utils.GSONStrict

/** Source-neutral event sent to a chapter-comment action rule. */
data class ChapterCommentEvent(
    val scope: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val segmentId: String? = null,
    val segmentIds: List<String> = emptyList(),
    val count: Int = 0,
    val actionData: JsonElement? = null,
) {
    init {
        require(scope in SUPPORTED_SCOPES) { "Unsupported chapter comment scope: $scope" }
        require(chapterIndex >= 0) { "chapterIndex must not be negative" }
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        require(count >= 0) { "count must not be negative" }
        require(segmentIds.size <= ChapterCommentParser.MAX_SEGMENTS) { "Too many segmentIds" }
    }

    /**
     * Stable contract JSON for book-source action JS.
     *
     * Must use literal property names — do not serialize this data class via
     * reflective GSON under R8 minify, or field names may be renamed and the
     * source will throw `unsupported chapter comment scope`.
     */
    fun toContractJson(): String {
        val root = JsonObject().apply {
            addProperty("scope", scope)
            addProperty("chapterIndex", chapterIndex)
            addProperty("pageIndex", pageIndex)
            if (segmentId != null) {
                addProperty("segmentId", segmentId)
            }
            add("segmentIds", JsonArray().apply {
                segmentIds.forEach { add(it) }
            })
            addProperty("count", count)
            if (actionData != null && !actionData.isJsonNull) {
                add("actionData", actionData.deepCopy())
            }
        }
        return GSONStrict.toJson(root)
    }

    companion object {
        const val SCOPE_SEGMENT = "segment"
        const val SCOPE_PAGE = "page"
        const val SCOPE_CHAPTER = "chapter"
        val SUPPORTED_SCOPES = setOf(SCOPE_SEGMENT, SCOPE_PAGE, SCOPE_CHAPTER)

        fun segment(page: TextPage, anchor: ResolvedChapterCommentSegment): ChapterCommentEvent {
            return ChapterCommentEvent(
                scope = SCOPE_SEGMENT,
                chapterIndex = page.chapterIndex,
                pageIndex = page.index,
                segmentId = anchor.segment.id,
                segmentIds = listOf(anchor.segment.id),
                count = anchor.segment.counts.total,
                actionData = anchor.segment.actionData?.deepCopy(),
            )
        }

        fun page(
            page: TextPage,
            projection: PageChapterCommentProjection,
            count: Int = projection.totalCount,
        ): ChapterCommentEvent {
            val actionData = JsonArray().apply {
                projection.segments.forEach { anchor ->
                    add(anchor.segment.actionData?.deepCopy())
                }
            }
            return ChapterCommentEvent(
                scope = SCOPE_PAGE,
                chapterIndex = page.chapterIndex,
                pageIndex = page.index,
                segmentIds = projection.segmentIds,
                count = count.coerceAtLeast(0),
                actionData = actionData,
            )
        }

        fun chapter(page: TextPage, summary: ChapterCommentSummary): ChapterCommentEvent {
            return ChapterCommentEvent(
                scope = SCOPE_CHAPTER,
                chapterIndex = page.chapterIndex,
                pageIndex = page.index,
                count = summary.counts.total,
                actionData = summary.actionData?.deepCopy(),
            )
        }
    }
}

data class ChapterCommentAction(
    val type: String,
    val url: String,
    val title: String,
    val presentation: String,
    val heightRatio: Float,
)

/** Strict parser for the bounded action returned by a source rule. */
object ChapterCommentActionParser {

    const val TYPE_SOURCE_WEB_VIEW = "sourceWebView"
    const val PRESENTATION_BOTTOM_SHEET = "bottomSheet"
    const val FIXED_HEIGHT_RATIO = 0.78f
    private const val MAX_ACTION_BYTES = 32 * 1024
    private const val MAX_URL_LENGTH = 4_096
    private const val MAX_TITLE_LENGTH = 64

    fun parse(json: String): ChapterCommentAction {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_ACTION_BYTES) {
            "Chapter comment action is too large"
        }
        val element = runCatching { GSONStrict.fromJson(json, JsonElement::class.java) }
            .getOrElse { throw IllegalArgumentException("Invalid chapter comment action JSON", it) }
        require(element?.isJsonObject == true) { "Chapter comment action must be an object" }
        val data = element.asJsonObject
        val type = data.requiredString("type", 32)
        require(type == TYPE_SOURCE_WEB_VIEW) { "Unsupported chapter comment action type: $type" }
        val presentation = data.optionalString("presentation", 32) ?: PRESENTATION_BOTTOM_SHEET
        require(presentation == PRESENTATION_BOTTOM_SHEET) {
            "Unsupported chapter comment presentation: $presentation"
        }
        return ChapterCommentAction(
            type = type,
            url = data.requiredString("url", MAX_URL_LENGTH),
            title = data.optionalString("title", MAX_TITLE_LENGTH) ?: "评论",
            presentation = presentation,
            // v2 owns the sheet geometry; a source cannot expand it over the reader.
            heightRatio = FIXED_HEIGHT_RATIO,
        )
    }

    private fun JsonObject.requiredString(name: String, maxLength: Int): String {
        return optionalString(name, maxLength)
            ?: throw IllegalArgumentException("$name is required")
    }

    private fun JsonObject.optionalString(name: String, maxLength: Int): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
        return value.asString.trim().also {
            require(it.isNotEmpty()) { "$name must not be empty" }
            require(it.length <= maxLength) { "$name exceeds $maxLength characters" }
        }
    }
}
