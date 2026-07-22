package io.legado.app.data.entities.rule

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Source-defined chapter comment rules.
 *
 * The reader only understands the normalized protocol. Site-specific URLs and
 * response conversion stay in [url], [data], and [action].
 */
@Parcelize
data class ChapterCommentRule(
    var protocolVersion: Int = 1,
    var url: String? = null,
    var data: String? = null,
    var action: String? = null,
    var display: ChapterCommentDisplayRule? = null,
    var cacheTtlSeconds: Int = 300,
) : Parcelable

/** Reader-owned display presets for the three supported comment scopes. */
@Parcelize
data class ChapterCommentDisplayRule(
    var segment: ChapterCommentEntryRule? = null,
    var page: ChapterCommentEntryRule? = null,
    var chapter: ChapterCommentEntryRule? = null,
) : Parcelable

/** A bounded display declaration; arbitrary native layouts are not supported. */
@Parcelize
data class ChapterCommentEntryRule(
    var enabled: Boolean = false,
    var preset: String? = null,
    var countField: String = "total",
    var label: String? = null,
) : Parcelable
