package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.timeline.Timeline

/**
 * Undo and redo, as an immutable value.
 *
 * Each entry is a whole timeline snapshot. Timelines are immutable and share
 * their unchanged tracks and clips, so snapshots are cheap. Only the latest
 * [limit] steps are kept.
 */
public data class EditHistory(
    val past: List<Timeline> = emptyList(),
    val future: List<Timeline> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records that [before] was replaced by a new edit. A new edit clears the redo stack. */
    public fun record(before: Timeline): EditHistory =
        copy(past = (past + before).takeLast(limit), future = emptyList())

    /** The timeline to restore and the updated history, or null if there is nothing to undo. */
    public fun undo(current: Timeline): Pair<Timeline, EditHistory>? {
        val previous = past.lastOrNull() ?: return null
        return previous to copy(past = past.dropLast(1), future = listOf(current) + future)
    }

    public fun redo(current: Timeline): Pair<Timeline, EditHistory>? {
        val next = future.firstOrNull() ?: return null
        return next to copy(past = (past + current).takeLast(limit), future = future.drop(1))
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 100
    }
}
