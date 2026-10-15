package com.editor.veyro.feature.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.timeline.EditCommand
import com.editor.veyro.core.timeline.EditContext
import com.editor.veyro.core.timeline.EditHistory
import com.editor.veyro.core.timeline.EditRejection
import com.editor.veyro.core.timeline.EditResult
import com.editor.veyro.core.timeline.MediaBounds
import com.editor.veyro.core.timeline.Snapper
import com.editor.veyro.core.timeline.TimelineEditor
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.media.playback.PreviewController
import com.editor.veyro.media.playback.PreviewPlan
import com.editor.veyro.media.playback.PreviewSegment
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A clip as the timeline UI draws it. Stable and equality-comparable, so unchanged clips skip recomposition. */
@Immutable
public data class TimelineClipUi(
    val id: ClipId,
    val trackId: TrackId,
    val startUs: Long,
    val durationUs: Long,
    val sourceInUs: Long,
    val uri: String,
    val mediaKind: MediaKind,
    val title: String,
) {
    val endUs: Long get() = startUs + durationUs
    val sourceOutUs: Long get() = sourceInUs + durationUs
}

@Immutable
public data class TimelineTrackUi(
    val id: TrackId,
    val kind: TrackKind,
    val clips: List<TimelineClipUi>,
    /** The main track keeps clips packed. Moves reorder, and deletes close gaps. */
    val magnetic: Boolean,
    val hasGaps: Boolean,
)

/** Which part of a clip a drag edits. */
public enum class DragHandle { START, END, BODY }

/** One-off messages for edits that couldn't happen, shown in a snackbar. */
public enum class EditorMessage { NOTHING_TO_SPLIT, SPLIT_TOO_CLOSE, NO_GAPS, SAVE_FAILED }

@Immutable
public sealed interface EditorUiState {
    public data object Loading : EditorUiState
    public data object NotFound : EditorUiState
    public data object LoadFailed : EditorUiState
    public data class Ready(
        val projectName: String,
        val tracks: List<TimelineTrackUi>,
        val durationUs: Long,
        val selectedClip: TimelineClipUi?,
        /** Which track the preview plays, or null when there's nothing to play. */
        val previewTrackKind: TrackKind?,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        /** While dragging, the edge or playhead the drag snapped to. The timeline draws a guide line there. */
        val snapGuideUs: Long? = null,
        val isDragging: Boolean = false,
        val message: EditorMessage? = null,
    ) : EditorUiState {
        val selectedTrack: TimelineTrackUi? get() = selectedClip?.let { clip -> tracks.firstOrNull { it.id == clip.trackId } }
    }
}

/**
 * The editor: the project's timeline, clip selection and edits, and a
 * preview player following the playhead.
 *
 * The editing rules live in `:core:timeline` ([TimelineEditor], [Snapper],
 * [EditHistory]). This class only turns UI intents into commands and
 * persists the results:
 * - Buttons ([split], [deleteSelected], [closeGaps], [undo], [redo]) apply
 *   one command and save.
 * - Drags ([beginDrag], [updateDrag], [endDrag]) re-apply a command to the
 *   timeline as it was when the drag began, and show the result as a draft.
 *   Nothing is saved until the finger lifts.
 *
 * The preview plays the saved timeline, so a drag doesn't reload the player
 * on every frame. Undo history lasts as long as this ViewModel.
 */
public class EditorViewModel(
    private val savedState: SavedStateHandle,
    private val projectId: ProjectId,
    private val repository: ProjectRepository,
    createController: (CoroutineScope) -> PreviewController,
    private val newClipId: () -> ClipId = { ClipId(UUID.randomUUID().toString()) },
) : ViewModel() {

    public val controller: PreviewController = createController(viewModelScope)

    private val selectedId = savedState.getStateFlow<String?>(KEY_SELECTED, null)

    private data class Drag(val base: Timeline, val clipId: ClipId, val handle: DragHandle, val originUs: Long)
    private data class Draft(val timeline: Timeline, val guideUs: Long?)

    private val draft = MutableStateFlow<Draft?>(null)
    private val history = MutableStateFlow(EditHistory())
    private val message = MutableStateFlow<EditorMessage?>(null)

    /**
     * The latest edit, shown until the repository reports it back. Without
     * it, the view would flash the old timeline for the duration of the
     * save.
     */
    private val pendingSave = MutableStateFlow<Timeline?>(null)
    private val saveMutex = Mutex()

    private var drag: Drag? = null
    private var latestProject: Project? = null

    /** UI state plus the matching preview plan. The plan goes to the controller, not the UI. */
    private class Built(val state: EditorUiState, val plan: PreviewPlan?)

    private val projectAndSelection = combine(repository.project(projectId), selectedId) { project, selected -> project to selected }
    private val editingState = combine(draft, pendingSave, history, message) { d, p, h, m -> EditingState(d, p, h, m) }

    private data class EditingState(val draft: Draft?, val pending: Timeline?, val history: EditHistory, val message: EditorMessage?)

    public val uiState: StateFlow<EditorUiState> = combine(projectAndSelection, editingState) { (project, selected), editing ->
        latestProject = project
        if (project == null) {
            Built(EditorUiState.NotFound, null)
        } else {
            val saved = editing.pending ?: DefaultArrangement.timelineFor(project)
            val shown = editing.draft?.timeline ?: saved
            val mediaById = project.media.associateBy { it.id }
            val preview = previewPlanFor(saved, mediaById)
            val tracks = tracksUi(shown, mediaById)
            Built(
                EditorUiState.Ready(
                    projectName = project.name,
                    tracks = tracks,
                    durationUs = shown.durationUs,
                    selectedClip = selected?.let { id -> tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id.value == id } } },
                    previewTrackKind = preview?.first,
                    canUndo = editing.history.canUndo,
                    canRedo = editing.history.canRedo,
                    snapGuideUs = editing.draft?.guideUs,
                    isDragging = editing.draft != null,
                    message = editing.message,
                ),
                preview?.second,
            )
        }
    }
        .catch { emit(Built(EditorUiState.LoadFailed, null)) }
        // setPlan is a no-op for an unchanged plan, so selection changes and drags don't reload the player.
        .onEach { built -> built.plan?.let(controller::setPlan) }
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState.Loading)

    // ---- Selection ----

    public fun selectClip(id: ClipId?) {
        savedState[KEY_SELECTED] = id?.value
    }

    // ---- One-shot edits ----

    /**
     * Splits the selected clip at [playheadUs], snapped to the nearest frame.
     * With no selection, it splits the main-track clip under the playhead.
     */
    public fun split(playheadUs: Long) {
        val timeline = currentTimeline() ?: return
        val at = Snapper.snap(playheadUs, emptySet(), 0, timeline.frameRate).timeUs
        val target = selectedId.value?.let { ClipId(it) }?.takeIf { timeline.findClip(it)?.let { c -> at in c.startUs until c.endUs } == true }
            ?: timeline.mainTrack?.clipAt(at)?.id
            ?: run {
                message.value = EditorMessage.NOTHING_TO_SPLIT
                return
            }
        execute(EditCommand.Split(target, at))
    }

    public fun deleteSelected(closeGap: Boolean) {
        val id = selectedId.value ?: return
        execute(EditCommand.Delete(ClipId(id), closeGap))
    }

    public fun closeGaps(trackId: TrackId) {
        execute(EditCommand.CloseGaps(trackId))
    }

    public fun undo() {
        val current = currentTimeline() ?: return
        val (previous, updated) = history.value.undo(current) ?: return
        history.value = updated
        save(previous)
    }

    public fun redo() {
        val current = currentTimeline() ?: return
        val (next, updated) = history.value.redo(current) ?: return
        history.value = updated
        save(next)
    }

    public fun messageShown() {
        message.value = null
    }

    // ---- Drags (trim handles and moving clips) ----

    /** Starts editing [clipId] with [handle]. It also selects the clip. */
    public fun beginDrag(clipId: ClipId, handle: DragHandle) {
        val timeline = currentTimeline() ?: return
        val clip = timeline.findClip(clipId) ?: return
        selectClip(clipId)
        val origin = when (handle) {
            DragHandle.START, DragHandle.BODY -> clip.startUs
            DragHandle.END -> clip.endUs
        }
        drag = Drag(timeline, clipId, handle, origin)
        draft.value = Draft(timeline, null)
    }

    /**
     * Updates the drag. The dragged edge (or the clip's start, when moving)
     * is now [deltaUs] away from where it began. It snaps to other edges and
     * the playhead within [snapThresholdUs], or else to the frame grid.
     */
    public fun updateDrag(deltaUs: Long, snapThresholdUs: Long, playheadUs: Long) {
        val current = drag ?: return
        val base = current.base
        val magneticMove = current.handle == DragHandle.BODY && base.trackOf(current.clipId)?.let { base.isMagnetic(it.id) } == true
        // Reordering on the magnetic track goes by position, so snapping would only make it feel sticky.
        val snap = if (magneticMove) {
            com.editor.veyro.core.timeline.Snap(current.originUs + deltaUs, null)
        } else {
            Snapper.snap(
                timeUs = current.originUs + deltaUs,
                targets = Snapper.targets(base, excluding = current.clipId, playheadUs = playheadUs),
                thresholdUs = snapThresholdUs,
                frameRate = base.frameRate,
            )
        }
        val command = when (current.handle) {
            DragHandle.START -> EditCommand.TrimStart(current.clipId, snap.timeUs)
            DragHandle.END -> EditCommand.TrimEnd(current.clipId, snap.timeUs)
            DragHandle.BODY -> EditCommand.Move(current.clipId, snap.timeUs)
        }
        val result = TimelineEditor.apply(base, command, editContext() ?: return)
        if (result is EditResult.Applied) draft.value = Draft(result.timeline, snap.guideUs)
    }

    /** Commits the drag's result as one undoable edit. */
    public fun endDrag() {
        val current = drag ?: return
        val result = draft.value?.timeline
        drag = null
        draft.value = null
        if (result != null && result != current.base) {
            history.value = history.value.record(current.base)
            save(result)
        }
    }

    public fun cancelDrag() {
        drag = null
        draft.value = null
    }

    override fun onCleared() {
        controller.release()
    }

    // ---- Internals ----

    private fun currentTimeline(): Timeline? =
        pendingSave.value ?: latestProject?.let(DefaultArrangement::timelineFor)

    private fun editContext(): EditContext? {
        val project = latestProject ?: return null
        return EditContext(
            media = project.media.associate { it.id to MediaBounds(it.kind, it.durationUs) },
            newClipId = newClipId,
        )
    }

    private fun execute(command: EditCommand) {
        val timeline = currentTimeline() ?: return
        val context = editContext() ?: return
        when (val result = TimelineEditor.apply(timeline, command, context)) {
            is EditResult.Applied -> {
                history.value = history.value.record(timeline)
                save(result.timeline)
                if (command is EditCommand.Delete) selectClip(null) else result.affectedClipId?.let(::selectClip)
            }
            is EditResult.Rejected -> message.value = when (result.reason) {
                EditRejection.TOO_CLOSE_TO_EDGE -> EditorMessage.SPLIT_TOO_CLOSE
                EditRejection.OUTSIDE_CLIP -> EditorMessage.NOTHING_TO_SPLIT
                EditRejection.NO_CHANGE -> EditorMessage.NO_GAPS
                else -> null
            }
        }
    }

    /** Shows [timeline] at once, then writes it. Saves run one at a time, in order. */
    private fun save(timeline: Timeline) {
        pendingSave.value = timeline
        viewModelScope.launch {
            saveMutex.withLock {
                try {
                    repository.updateTimeline(projectId, timeline)
                } catch (e: IOException) {
                    message.value = EditorMessage.SAVE_FAILED
                } catch (e: IllegalArgumentException) {
                    message.value = EditorMessage.SAVE_FAILED
                }
            }
            // Clear the pending copy once the repository holds it (or a newer edit replaced it).
            if (pendingSave.value == timeline) pendingSave.value = null
        }
    }

    private fun tracksUi(timeline: Timeline, mediaById: Map<MediaId, MediaRef>): List<TimelineTrackUi> =
        timeline.tracks.map { track ->
            TimelineTrackUi(
                id = track.id,
                kind = track.kind,
                magnetic = timeline.isMagnetic(track.id),
                hasGaps = TimelineEditor.hasGaps(track),
                clips = track.clips.mapNotNull { clip ->
                    val media = mediaById[clip.mediaId] ?: return@mapNotNull null
                    TimelineClipUi(
                        id = clip.id,
                        trackId = track.id,
                        startUs = clip.startUs,
                        durationUs = clip.durationUs,
                        sourceInUs = clip.sourceInUs,
                        uri = media.uri,
                        mediaKind = media.kind,
                        title = media.tags?.title?.takeIf { media.kind == MediaKind.AUDIO } ?: media.displayName.orEmpty(),
                    )
                },
            )
        }

    private companion object {
        const val KEY_SELECTED = "selectedClipId"
    }
}

/**
 * The preview plan for [timeline]. The preview plays a single track:
 * - the main video track when it has clips
 * - otherwise the first audio track
 *
 * Other tracks are not mixed in yet, because the preview has no multi-track
 * compositing or audio mixing. Photos become still segments.
 *
 * Returns null when there's nothing to play, or when the chosen track has
 * gaps. The magnetic main track never has gaps. An audio-only project whose
 * audio track has gaps can't be previewed until they're closed.
 */
internal fun previewPlanFor(timeline: Timeline, mediaById: Map<MediaId, MediaRef>): Pair<TrackKind, PreviewPlan>? {
    val track = timeline.mainTrack?.takeIf { it.clips.isNotEmpty() }
        ?: timeline.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.clips.isNotEmpty() }
        ?: return null
    var expectedStart = 0L
    val segments = track.clips.map { clip ->
        if (clip.startUs != expectedStart) return null
        expectedStart = clip.endUs
        val media = mediaById[clip.mediaId] ?: return null
        PreviewSegment(
            uri = media.uri,
            sourceStartUs = clip.sourceInUs,
            durationUs = clip.durationUs,
            clipped = media.kind != MediaKind.IMAGE,
            still = media.kind == MediaKind.IMAGE,
        )
    }
    return track.kind to PreviewPlan(segments)
}
