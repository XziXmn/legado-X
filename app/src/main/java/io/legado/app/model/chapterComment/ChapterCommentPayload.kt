package io.legado.app.model.chapterComment

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.utils.GSONStrict
import java.math.BigInteger

data class ChapterCommentCounts(
    val total: Int = 0,
    val hot: Int = 0,
)

data class ChapterCommentSegment(
    val id: String,
    val paragraphIndex: Int,
    val paragraphCount: Int,
    val excerpt: String?,
    val counts: ChapterCommentCounts,
    val pageEligible: Boolean,
    val actionData: JsonElement?,
)

data class ChapterCommentSummary(
    val label: String,
    val counts: ChapterCommentCounts,
    val actionData: JsonElement?,
    val badge: String? = null,
    val previews: List<String> = emptyList(),
)

data class ChapterCommentPayload(
    val version: Int,
    val segments: List<ChapterCommentSegment>,
    val chapter: ChapterCommentSummary?,
    val author: ChapterCommentSummary? = null,
)

/** Strict parser for the source-neutral chapter comment protocol. */
object ChapterCommentParser {

    const val PROTOCOL_VERSION = 2
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    const val MAX_SEGMENTS = 200
    const val MAX_ID_LENGTH = 256
    const val MAX_EXCERPT_LENGTH = 512
    const val MAX_PREVIEW_LENGTH = 512
    const val MAX_PREVIEWS = 3

    /**
     * Parse and validate a normalized summary payload.
     *
     * @throws IllegalArgumentException when the payload is malformed or exceeds a protocol limit.
     */
    fun parse(json: String): ChapterCommentPayload {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Chapter comment payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        val root = runCatching {
            GSONStrict.fromJson(json, JsonElement::class.java)
        }.getOrElse {
            throw IllegalArgumentException("Invalid chapter comment JSON", it)
        }
        require(root?.isJsonObject == true) { "Chapter comment payload must be an object" }
        val data = root.asJsonObject
        val version = data.requiredInt("version", min = 1)
        require(version in 1..PROTOCOL_VERSION) {
            "Unsupported chapter comment version: $version"
        }

        val segmentArray = data.get("segments")?.let {
            require(it.isJsonArray) { "segments must be an array" }
            it.asJsonArray
        }
        require((segmentArray?.size() ?: 0) <= MAX_SEGMENTS) {
            "Too many chapter comment segments"
        }
        val segments = segmentArray.orEmpty().mapIndexed { index, item ->
            require(item.isJsonObject) { "segments[$index] must be an object" }
            parseSegment(item.asJsonObject, index)
        }
        val author = parseSummary(data.get("author"), "author", "作家说")
        val chapter = parseSummary(data.get("chapter"), "chapter", "本章说")
        return ChapterCommentPayload(
            version = version,
            segments = segments,
            chapter = chapter,
            author = author,
        )
    }

    private fun parseSegment(data: JsonObject, index: Int): ChapterCommentSegment {
        val id = data.requiredString("id", MAX_ID_LENGTH)
        val paragraphIndex = data.requiredInt("paragraphIndex", min = 0)
        val paragraphCount = data.optionalInt("paragraphCount", default = 1, min = 1)
        require(paragraphCount <= MAX_SEGMENTS) {
            "segments[$index].paragraphCount is too large"
        }
        val excerpt = data.optionalString("excerpt", MAX_EXCERPT_LENGTH)
        return ChapterCommentSegment(
            id = id,
            paragraphIndex = paragraphIndex,
            paragraphCount = paragraphCount,
            excerpt = excerpt,
            counts = parseCounts(data.get("counts"), "segments[$index].counts"),
            pageEligible = data.optionalBoolean("pageEligible", false),
            actionData = data.get("actionData")?.takeUnless(JsonElement::isJsonNull)?.deepCopy(),
        )
    }

    private fun parseSummary(
        value: JsonElement?,
        field: String,
        defaultLabel: String,
    ): ChapterCommentSummary? {
        if (value == null || value.isJsonNull) return null
        require(value.isJsonObject) { "$field must be an object" }
        val data = value.asJsonObject
        val previews = when {
            data.has("previews") && !data.get("previews").isJsonNull ->
                parsePreviews(data.get("previews"), field)
            // v1 used a single string field "preview"
            else -> data.optionalString("preview", MAX_PREVIEW_LENGTH)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { listOf(it) }
                ?: emptyList()
        }
        return ChapterCommentSummary(
            label = data.optionalString("label", MAX_ID_LENGTH)?.trim()?.takeIf(String::isNotEmpty)
                ?: defaultLabel,
            counts = parseCounts(data.get("counts"), "$field.counts"),
            actionData = data.get("actionData")?.takeUnless(JsonElement::isJsonNull)?.deepCopy(),
            badge = data.optionalString("badge", MAX_ID_LENGTH)
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            previews = previews,
        )
    }

    private fun parsePreviews(value: JsonElement?, field: String): List<String> {
        if (value == null || value.isJsonNull) return emptyList()
        require(value.isJsonArray) { "$field.previews must be an array" }
        val array = value.asJsonArray
        require(array.size() <= MAX_PREVIEWS) { "$field.previews has too many items" }
        return array.mapIndexedNotNull { index, item ->
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                "$field.previews[$index] must be a string"
            }
            item.asString.also {
                require(it.length <= MAX_PREVIEW_LENGTH) {
                    "$field.previews[$index] exceeds $MAX_PREVIEW_LENGTH characters"
                }
            }.trim().takeIf(String::isNotEmpty)
        }
    }

    private fun parseCounts(value: JsonElement?, field: String): ChapterCommentCounts {
        if (value == null || value.isJsonNull) return ChapterCommentCounts()
        require(value.isJsonObject) { "$field must be an object" }
        val counts = value.asJsonObject
        return ChapterCommentCounts(
            total = counts.optionalInt("total", default = 0, min = 0),
            hot = counts.optionalInt("hot", default = 0, min = 0),
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
        return value.asString.also {
            require(it.length <= maxLength) { "$name exceeds $maxLength characters" }
        }
    }

    private fun JsonObject.requiredInt(name: String, min: Int): Int {
        val value = get(name) ?: throw IllegalArgumentException("$name is required")
        return value.toBoundedInt(name, min)
    }

    private fun JsonObject.optionalInt(name: String, default: Int, min: Int): Int {
        return get(name)?.toBoundedInt(name, min) ?: default
    }

    private fun JsonElement.toBoundedInt(name: String, min: Int): Int {
        require(isJsonPrimitive && asJsonPrimitive.isNumber) { "$name must be an integer" }
        val number = asString.toBigIntegerOrNull()
            ?: throw IllegalArgumentException("$name must be an integer")
        require(number >= BigInteger.valueOf(min.toLong())) { "$name must be at least $min" }
        return number.min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }

    private fun JsonObject.optionalBoolean(name: String, default: Boolean): Boolean {
        val value = get(name) ?: return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
        return value.asBoolean
    }

    private fun Iterable<JsonElement>?.orEmpty(): Iterable<JsonElement> = this ?: emptyList()
}
