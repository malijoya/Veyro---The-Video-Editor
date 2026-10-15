package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.timeline.Timeline

/**
 * One undoable step: the [command] the user gave and the exact [patch] it
 * produced.
 *
 * Undo applies the patch's inverse, and redo applies the patch again. Neither
 * re-runs the engine, so redo restores exactly what the edit did, including
 * the IDs of clips a split created. Later steps may refer to those IDs.
 */
public data class RecordedEdit(val command: EditCommand, val patch: TimelinePatch)

/** What [EditHistory.undo] and [EditHistory.redo] did. */
public sealed interface HistoryStep {
    /** The step was applied. [edit] is the step that was undone or redone. */
    public data class Moved(val timeline: Timeline, val history: EditHistory, val edit: RecordedEdit) : HistoryStep

    /** There is nothing to undo, or nothing to redo. */
    public data object Empty : HistoryStep

    /**
     * The timeline no longer matches the step (it was changed outside the
     * history, for example by an import). The timeline is left as it is, and
     * [history] has the stale steps in that direction dropped.
     */
    public data class Conflict(val history: EditHistory) : HistoryStep
}

/**
 * Undo and redo, as an immutable value.
 *
 * Each entry is a [RecordedEdit]: a small patch of the clips that changed,
 * not a copy of the timeline. Memory is bounded two ways, and the oldest
 * steps are dropped first:
 * - at most [limit] undo steps
 * - at most [maxClipRecords] clip records across undo and redo
 */
public data class EditHistory(
    val past: List<RecordedEdit> = emptyList(),
    val future: List<RecordedEdit> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
    val maxClipRecords: Int = DEFAULT_MAX_CLIP_RECORDS,
) {
    init {
        require(limit > 0) { "limit must be positive" }
        require(maxClipRecords > 0) { "maxClipRecords must be positive" }
    }

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** The clip records kept by every step, undo and redo. */
    val clipRecordCount: Int get() = past.sumOf { it.patch.clipRecordCount } + future.sumOf { it.patch.clipRecordCount }

    /**
     * Records that [command] turned [before] into [after]. A new edit clears
     * the redo stack. An edit that changed nothing isn't recorded.
     */
    public fun record(command: EditCommand, before: Timeline, after: Timeline): EditHistory {
        val patch = TimelinePatch.between(before, after)
        if (patch.isEmpty) return this
        return copy(past = past + RecordedEdit(command, patch), future = emptyList()).trimmed()
    }

    /** Undoes the latest step on [current], the timeline as it is now. */
    public fun undo(current: Timeline): HistoryStep {
        val edit = past.lastOrNull() ?: return HistoryStep.Empty
        val restored = edit.patch.inverse().applyTo(current) ?: return HistoryStep.Conflict(copy(past = emptyList()))
        return HistoryStep.Moved(restored, copy(past = past.dropLast(1), future = listOf(edit) + future), edit)
    }

    /** Redoes the most recently undone step on [current]. */
    public fun redo(current: Timeline): HistoryStep {
        val edit = future.firstOrNull() ?: return HistoryStep.Empty
        val reapplied = edit.patch.applyTo(current) ?: return HistoryStep.Conflict(copy(future = emptyList()))
        return HistoryStep.Moved(reapplied, copy(past = past + edit, future = future.drop(1)).trimmed(), edit)
    }

    /** Drops the oldest undo steps until both limits hold. Redo steps are dropped only if undo steps alone can't make room. */
    private fun trimmed(): EditHistory {
        var newPast = if (past.size > limit) past.takeLast(limit) else past
        var newFuture = future
        var records = newPast.sumOf { it.patch.clipRecordCount } + newFuture.sumOf { it.patch.clipRecordCount }
        while (records > maxClipRecords && newPast.size > 1) {
            records -= newPast.first().patch.clipRecordCount
            newPast = newPast.drop(1)
        }
        while (records > maxClipRecords && newFuture.isNotEmpty()) {
            records -= newFuture.last().patch.clipRecordCount
            newFuture = newFuture.dropLast(1)
        }
        // The latest step is always kept, even if it alone is over budget, so the edit just made can be undone.
        return if (newPast === past && newFuture === future) this else copy(past = newPast, future = newFuture)
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 200

        /**
         * About 1–2 MB of clip records at most. Typical edits store a
         * handful of records, so this only matters for repeated edits on
         * very long main tracks, where each trim repacks many clips.
         */
        public const val DEFAULT_MAX_CLIP_RECORDS: Int = 20_000
    }
}
