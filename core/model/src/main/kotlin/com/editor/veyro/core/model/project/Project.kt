package com.editor.veyro.core.model.project

/** Stable identifier of a project. It is generated once and never reused. */
@JvmInline
public value class ProjectId(public val value: String) {
    init {
        require(value.isNotBlank()) { "ProjectId must not be blank" }
    }
}

/**
 * A saved editing project.
 *
 * So far a project holds its media bin, meaning the source files the user
 * imported. Tracks, clips and edits referencing these media are added by the
 * timeline milestone (see docs/ROADMAP.md, M1). Source files are never
 * modified.
 */
public data class Project(
    val id: ProjectId,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val media: List<MediaRef>,
    /**
     * The edited arrangement, or null if the user hasn't edited yet. In that
     * case the editor starts from
     * [com.editor.veyro.core.model.timeline.DefaultArrangement].
     */
    val timeline: com.editor.veyro.core.model.timeline.Timeline? = null,
) {
    /** The media that represents the project in lists: the first visual item, else the first item. */
    val coverMedia: MediaRef? get() = media.firstOrNull { it.kind != MediaKind.AUDIO } ?: media.firstOrNull()

    /** Combined duration of the imported video media. Images and audio are excluded. */
    val mediaDurationUs: Long get() = media.filter { it.kind == MediaKind.VIDEO }.sumOf { it.durationUs }

    /** Whether a media item for the same source URI is already in the project. */
    public fun containsUri(uri: String): Boolean = media.any { it.uri == uri }
}
