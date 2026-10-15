package com.editor.veyro.feature.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.audio.WaveformSource
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
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
import com.editor.veyro.core.timeline.HistoryStep
import com.editor.veyro.core.timeline.MediaBounds
import com.editor.veyro.core.timeline.Snap
import com.editor.veyro.core.timeline.Snapper
import com.editor.veyro.core.timeline.TimelineEditor
import com.editor.veyro.core.timeline.export.ExportOptions
import com.editor.veyro.core.timeline.export.ExportPlanResult
import com.editor.veyro.core.timeline.export.ExportPlanner
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.data.project.ProjectAutosave
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.media.playback.PreviewController
import com.editor.veyro.media.playback.PreviewPlan
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
    /** Whether the media has an audio stream. Audio controls show only then. */
    val hasAudio: Boolean = false,
    val audio: ClipAudio = ClipAudio.DEFAULT,
    /** Measured peaks of the media, once analysed. Null while analysing, or when there's no audio. */
    val waveform: Waveform? = null,
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
    val audio: TrackAudio = TrackAudio.DEFAULT,
    /** Music or voiceover, for audio tracks. */
    val role: AudioRole? = null,
)

/** Which part of a clip a drag edits. */
public enum class DragHandle { START, END, BODY }

/** One-off messages for edits that couldn't happen, shown in a snackbar. */
public enum class EditorMessage {
    NOTHING_TO_SPLIT,
    SPLIT_TOO_CLOSE,
    NO_GAPS,

    /** The timeline changed outside the editor's history (for example, media was added), so older steps can't be undone or redone. */
    HISTORY_OUT_OF_DATE,

    /** An audio change on a clip with no sound (a photo, or a video without an audio stream). */
    CLIP_HAS_NO_AUDIO,
}

/** Autosave state shown in the editor's top bar. */
public enum class SaveStatus { SAVED, PENDING, SAVING, FAILED }

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
        /** VIDEO when the preview shows pictures, AUDIO for an audio-only timeline, null when there's nothing to play. */
        val previewTrackKind: TrackKind?,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        /** While dragging, the edge or playhead the drag snapped to. The timeline draws a guide line there. */
        val snapGuideUs: Long? = null,
        val isDragging: Boolean = false,
        val message: EditorMessage? = null,
        /** Canvas width / height from the project settings. The preview frame uses this shape. */
        val canvasAspectRatio: Float = 9f / 16f,
        val saveStatus: SaveStatus = SaveStatus.SAVED,
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
 * on every frame.
 *
 * Undo history keeps a small patch per edit (see [EditHistory]), not
 * timeline copies, and lasts as long as this ViewModel.
 *
 * Saving goes through [autosave]:
 * - edits show immediately, and are written after a short pause
 * - [flush] writes at once (when the editor goes to the background)
 * - closing the editor flushes in the app scope, so nothing is lost
 */
public class EditorViewModel(
    private val savedState: SavedStateHandle,
    private val projectId: ProjectId,
    repository: ProjectRepository,
    createController: (CoroutineScope) -> PreviewController,
    private val autosave: ProjectAutosave,
    private val waveforms: WaveformSource = WaveformSource.NONE,
    private val newClipId: () -> ClipId = { ClipId(UUID.randomUUID().toString()) },
) : ViewModel() {

    public val controller: PreviewController = createController(viewModelScope)

    private val selectedId = savedState.getStateFlow<String?>(KEY_SELECTED, null)

    private data class Drag(val base: Timeline, val clipId: ClipId, val handle: DragHandle, val originUs: Long)
    private data class Draft(val timeline: Timeline, val guideUs: Long?, val command: EditCommand? = null)

    private val draft = MutableStateFlow<Draft?>(null)
    private val history = MutableStateFlow(EditHistory())
    private val message = MutableStateFlow<EditorMessage?>(null)

    /**
     * The latest edit, shown until the repository holds it. Autosave writes
     * it a moment later. Until then, this is what the user sees and edits.
     */
    private val pendingSave = MutableStateFlow<Timeline?>(null)

    private var drag: Drag? = null
    private var latestProject: Project? = null

    /** Waveforms measured so far. Each one arriving updates the timeline drawing and the preview's mix headroom. */
    private val analysed = MutableStateFlow<Map<MediaId, Waveform>>(emptyMap())
    private val analysisRequested = HashSet<MediaId>()

    /** UI state plus the matching preview plan. The plan goes to the controller, not the UI. */
    private class Built(val state: EditorUiState, val plan: PreviewPlan?, val persisted: Timeline? = null)

    private val projectAndSelection = combine(repository.project(projectId), selectedId, analysed) { project, selected, peaks ->
        ProjectView(project, selected, peaks)
    }

    private data class ProjectView(val project: Project?, val selected: String?, val waveforms: Map<MediaId, Waveform>)
    private val editingState = combine(draft, pendingSave, history, message, autosave.status) { d, p, h, m, a ->
        EditingState(d, p, h, m, a)
    }

    private data class EditingState(
        val draft: Draft?,
        val pending: Timeline?,
        val history: EditHistory,
        val message: EditorMessage?,
        val autosave: ProjectAutosave.Status,
    )

    public val uiState: StateFlow<EditorUiState> = combine(projectAndSelection, editingState) { (project, selected, peaks), editing ->
        latestProject = project
        if (project == null) {
            Built(EditorUiState.NotFound, null)
        } else {
            requestWaveforms(project)
            val persisted = DefaultArrangement.timelineFor(project)
            val saved = editing.pending ?: persisted
            val shown = editing.draft?.timeline ?: saved
            val mediaById = project.media.associateBy { it.id }
            val preview = previewPlanFor(saved, project, peaks)
            val tracks = tracksUi(shown, mediaById, peaks)
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
                    canvasAspectRatio = project.settings.aspectRatio,
                    saveStatus = when {
                        editing.autosave is ProjectAutosave.Status.Failed -> SaveStatus.FAILED
                        editing.autosave is ProjectAutosave.Status.Saving -> SaveStatus.SAVING
                        editing.pending != null && editing.pending != persisted -> SaveStatus.PENDING
                        else -> SaveStatus.SAVED
                    },
                ),
                preview?.second,
                persisted,
            )
        }
    }
        .catch { emit(Built(EditorUiState.LoadFailed, null)) }
        // setPlan is a no-op for an unchanged plan, so selection changes and drags don't reload the player.
        // An edit keeps the playhead where it is.
        .onEach { built ->
            built.plan?.let { controller.setPlan(it, keepPosition = true) }
            // Once the repository holds the pending edit, show the stored copy again (it then reflects outside changes too).
            val pending = pendingSave.value
            if (pending != null && pending == built.persisted) pendingSave.compareAndSet(pending, null)
        }
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
        onHistoryStep(history.value.undo(current))
    }

    public fun redo() {
        val current = currentTimeline() ?: return
        onHistoryStep(history.value.redo(current))
    }

    private fun onHistoryStep(step: HistoryStep) {
        when (step) {
            is HistoryStep.Moved -> {
                history.value = step.history
                save(step.timeline)
                // Keep the selection only if the clip still exists (undoing a split removes the right part).
                val selected = selectedId.value?.let(::ClipId)
                if (selected != null && step.timeline.findClip(selected) == null) selectClip(null)
            }
            is HistoryStep.Conflict -> {
                history.value = step.history
                message.value = EditorMessage.HISTORY_OUT_OF_DATE
            }
            HistoryStep.Empty -> Unit
        }
    }

    // ---- Audio ----

    /** Sets the clip's volume (0..1), mute and fades as one undoable edit. */
    public fun setClipAudio(clipId: ClipId, audio: ClipAudio) {
        execute(EditCommand.SetClipAudio(clipId, audio))
    }

    /** Sets a whole track's volume (0..1) and mute as one undoable edit. */
    public fun setTrackAudio(trackId: TrackId, audio: TrackAudio) {
        execute(EditCommand.SetTrackAudio(trackId, audio))
    }

    /** Moves a clip to another audio track (Music or Voiceover), as close to its current time as fits. */
    public fun moveClipToTrack(clipId: ClipId, trackId: TrackId) {
        val clip = currentTimeline()?.findClip(clipId) ?: return
        execute(EditCommand.MoveToTrack(clipId, trackId, clip.startUs))
    }

    /** Starts measuring every audio-bearing file once. Results arrive through [analysed]. */
    private fun requestWaveforms(project: Project) {
        project.media.filter { it.hasAudio && it.id !in analysisRequested }.forEach { media ->
            analysisRequested += media.id
            val cached = waveforms.cached(media)
            if (cached != null) {
                analysed.value = analysed.value + (media.id to cached)
            } else {
                viewModelScope.launch {
                    val waveform = waveforms.load(media) ?: return@launch
                    analysed.value = analysed.value + (media.id to waveform)
                }
            }
        }
    }

    public fun messageShown() {
        message.value = null
    }

    /** Writes pending edits now. The editor calls this when it goes to the background. */
    public fun flush() {
        viewModelScope.launch { autosave.flush() }
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
     * is now [deltaUs] away from where it began.
     *
     * It snaps to other clips' edges and the playhead when within the
     * project's snap distance on screen, or else to the frame grid. The
     * screen passes its zoom ([pxPerSecond]) and [pxPerDp], so the snap
     * distance is the same number of pixels at every zoom level.
     */
    public fun updateDrag(deltaUs: Long, pxPerSecond: Float, pxPerDp: Float, playheadUs: Long) {
        val current = drag ?: return
        val base = current.base
        val clip = base.findClip(current.clipId) ?: return
        val magneticMove = current.handle == DragHandle.BODY && base.trackOf(current.clipId)?.let { base.isMagnetic(it.id) } == true
        val settings = latestProject?.settings
        // With edge snapping off, only the frame grid applies.
        val targets = if (settings?.snapToEdges != false) Snapper.targets(base, excluding = current.clipId, playheadUs = playheadUs) else emptySet()
        val distanceDp = settings?.snapDistanceDp ?: ProjectSettings.DEFAULT_SNAP_DISTANCE_DP
        val thresholdUs = Snapper.thresholdUs(distanceDp * pxPerDp, pxPerSecond, base.frameRate)
        val timeUs = current.originUs + deltaUs
        val snap = when {
            // Reordering on the magnetic track goes by position, so snapping would only make it feel sticky.
            magneticMove -> Snap(timeUs, null)
            // A moved clip snaps by whichever edge is closer to a target.
            current.handle == DragHandle.BODY -> Snapper.snapClip(timeUs, clip.durationUs, targets, thresholdUs, base.frameRate)
            else -> Snapper.snap(timeUs, targets, thresholdUs, base.frameRate)
        }
        val command = when (current.handle) {
            DragHandle.START -> EditCommand.TrimStart(current.clipId, snap.timeUs)
            DragHandle.END -> EditCommand.TrimEnd(current.clipId, snap.timeUs)
            DragHandle.BODY -> EditCommand.Move(current.clipId, snap.timeUs)
        }
        val result = TimelineEditor.apply(base, command, editContext() ?: return)
        if (result is EditResult.Applied) draft.value = Draft(result.timeline, snap.guideUs, command)
    }

    /** Commits the drag's result as one undoable edit. */
    public fun endDrag() {
        val current = drag ?: return
        val finished = draft.value
        drag = null
        draft.value = null
        val result = finished?.timeline
        val command = finished?.command
        if (result != null && command != null && result != current.base) {
            history.value = history.value.record(command, current.base, result)
            save(result)
        }
    }

    public fun cancelDrag() {
        drag = null
        draft.value = null
    }

    override fun onCleared() {
        controller.release()
        // viewModelScope is already cancelled here. Autosave's own app-wide scope finishes the write.
        autosave.flushInBackground()
    }

    // ---- Internals ----

    private fun currentTimeline(): Timeline? =
        pendingSave.value ?: latestProject?.let(DefaultArrangement::timelineFor)

    private fun editContext(): EditContext? {
        val project = latestProject ?: return null
        return EditContext(
            media = project.media.associate { it.id to MediaBounds(it.kind, it.durationUs, it.hasAudio) },
            newClipId = newClipId,
        )
    }

    private fun execute(command: EditCommand) {
        val timeline = currentTimeline() ?: return
        val context = editContext() ?: return
        when (val result = TimelineEditor.apply(timeline, command, context)) {
            is EditResult.Applied -> {
                history.value = history.value.record(command, timeline, result.timeline)
                save(result.timeline)
                if (command is EditCommand.Delete) selectClip(null) else result.affectedClipId?.let(::selectClip)
            }
            is EditResult.Rejected -> message.value = when (result.reason) {
                EditRejection.TOO_CLOSE_TO_EDGE -> EditorMessage.SPLIT_TOO_CLOSE
                EditRejection.OUTSIDE_CLIP -> EditorMessage.NOTHING_TO_SPLIT
                EditRejection.NO_CHANGE -> if (command is EditCommand.CloseGaps) EditorMessage.NO_GAPS else null
                EditRejection.NO_AUDIO -> EditorMessage.CLIP_HAS_NO_AUDIO
                else -> null
            }
        }
    }

    /** Shows [timeline] at once and hands it to autosave, which writes it after a short pause. */
    private fun save(timeline: Timeline) {
        pendingSave.value = timeline
        autosave.schedule(projectId, timeline)
    }

    private fun tracksUi(timeline: Timeline, mediaById: Map<MediaId, MediaRef>, peaks: Map<MediaId, Waveform>): List<TimelineTrackUi> =
        timeline.tracks.map { track ->
            TimelineTrackUi(
                id = track.id,
                kind = track.kind,
                magnetic = timeline.isMagnetic(track.id),
                hasGaps = TimelineEditor.hasGaps(track),
                audio = track.audio,
                role = track.role,
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
                        hasAudio = media.hasAudio,
                        audio = clip.audio,
                        waveform = peaks[media.id],
                    )
                },
            )
        }

    private companion object {
        const val KEY_SELECTED = "selectedClipId"
    }
}

/**
 * The preview plan for [timeline]: the same render plan export uses
 * ([ExportPlanner]), played with Media3's CompositionPlayer. Every audio
 * track is mixed at its timeline position, with volume, mute, fades and the
 * mix headroom, so what you hear is what export writes.
 *
 * - Rendered at the canvas's shape, at most [PREVIEW_SHORT_SIDE_PX] on the
 *   short side, to keep the preview light. Export uses the size chosen there.
 * - An audio-only timeline still plays (export would refuse it).
 * - Returns null when there's nothing to play, or a file is unavailable.
 */
internal fun previewPlanFor(timeline: Timeline, project: Project, waveforms: Map<MediaId, Waveform>): Pair<TrackKind, PreviewPlan>? {
    val settings = project.settings
    val size = ExportOptions.resolutionsFor(settings.canvasWidthPx, settings.canvasHeightPx)
        .firstOrNull { it.shortSidePx <= PREVIEW_SHORT_SIDE_PX }
        ?: ExportOptions.resolutionsFor(settings.canvasWidthPx, settings.canvasHeightPx).last()
    val result = ExportPlanner.plan(
        timeline = timeline,
        media = project.media,
        settings = ExportSettings(size.widthPx, size.heightPx, settings.frameRate),
        waveforms = waveforms::get,
        requireVideo = false,
    )
    val plan = (result as? ExportPlanResult.Ready)?.plan ?: return null
    return (if (plan.video.isNotEmpty()) TrackKind.VIDEO else TrackKind.AUDIO) to PreviewPlan.forTimeline(plan)
}

/** The preview renders at most this many pixels on its short side. */
internal const val PREVIEW_SHORT_SIDE_PX = 720
