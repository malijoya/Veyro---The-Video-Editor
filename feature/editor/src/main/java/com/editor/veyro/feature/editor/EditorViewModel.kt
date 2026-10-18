package com.editor.veyro.feature.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.Keyframing
import com.editor.veyro.core.model.animation.TransformChannel
import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainInterpolation
import com.editor.veyro.core.model.audio.GainKeyframes
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.audio.WaveformSource
import com.editor.veyro.core.model.overlay.Argb
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.overlay.TextShadow
import com.editor.veyro.core.model.overlay.TextStyle
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.time.FrameRounding
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transform.ClipPlacement
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.FitMode
import com.editor.veyro.core.model.transition.Transition
import com.editor.veyro.core.model.transition.TransitionLimit
import com.editor.veyro.core.timeline.EditCommand
import com.editor.veyro.core.timeline.EditContext
import com.editor.veyro.core.timeline.EditHistory
import com.editor.veyro.core.timeline.EditRejection
import com.editor.veyro.core.timeline.EditResult
import com.editor.veyro.core.timeline.HistoryStep
import com.editor.veyro.core.timeline.LayerMove
import com.editor.veyro.core.timeline.MediaBounds
import com.editor.veyro.core.timeline.Snap
import com.editor.veyro.core.timeline.Snapper
import com.editor.veyro.core.timeline.TimelineEditor
import com.editor.veyro.core.timeline.TransitionLayout
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** The text or sticker, on overlay tracks. Text clips have no media: [uri] is empty and [title] is the text. */
    val overlay: Overlay? = null,
    /** Clip-local times of the clip's placement keyframes inside the clip, for the timeline's markers. */
    val keyframeTimesUs: List<Long> = emptyList(),
    /** Clip-local times of the clip's volume keyframes inside the clip (they are stored in source time). */
    val gainKeyframeTimesUs: List<Long> = emptyList(),
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

/**
 * An overlay as the preview canvas draws and edits it, in stacking order
 * (see [EditorUiState.Ready.overlays]).
 *
 * @property overlay the overlay as shown: an unsaved change being made
 *   (a drag, a slider) replaces the saved one here.
 * @property imageUri the sticker's image; null for text.
 * @property animation its keyframes (with the change in progress), in
 *   clip-local time. [overlay] holds the static values; [at] gives what is
 *   shown at a moment.
 */
@Immutable
public data class OverlayUi(
    val clipId: ClipId,
    val startUs: Long,
    val endUs: Long,
    val overlay: Overlay,
    val imageUri: String? = null,
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0,
    val imageRotationDegrees: Int = 0,
    val animation: ClipAnimation = ClipAnimation.NONE,
) {
    public operator fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs

    /** This overlay as shown at timeline time [timeUs]: its keyframes evaluated there, as the render evaluates them. */
    public fun at(timeUs: Long): OverlayUi =
        if (!animation.animatesTransform) this else copy(overlay = overlay.copy(transform = animation.overlayTransformAt(overlay.transform, timeUs - startUs)))
}

/**
 * A video or photo on the main track, as the preview canvas places and edits
 * it.
 *
 * @property transform the transform as shown: an unsaved change in progress
 *   (a drag, a slider) replaces the saved one here.
 * @property sourceWidthPx the source's upright width (0 if unknown), for the
 *   handles; the render measures the decoded frame itself.
 */
@Immutable
public data class PictureUi(
    val clipId: ClipId,
    val startUs: Long,
    val endUs: Long,
    val transform: ClipTransform,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    /** Its keyframes (with the change in progress), in clip-local time. [transform] holds the static values. */
    val animation: ClipAnimation = ClipAnimation.NONE,
) {
    public operator fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs

    /** This picture as shown at timeline time [timeUs]: its keyframes evaluated there, as the render evaluates them. */
    public fun at(timeUs: Long): PictureUi =
        if (!animation.animatesTransform) this else copy(transform = animation.transformAt(transform, timeUs - startUs))

    /** The picture's layout on a [canvasWidthPx] x [canvasHeightPx] canvas. */
    public fun placement(canvasWidthPx: Int, canvasHeightPx: Int, transform: ClipTransform = this.transform): ClipPlacement =
        ClipPlacement(transform, sourceWidthPx, sourceHeightPx, canvasWidthPx, canvasHeightPx)
}

/**
 * A cut between two clips on the main track, with its transition as it
 * plays ([TransitionLayout]): the timeline marks it, and the transition
 * panel edits it.
 *
 * @property startUs where the transition starts as it plays; equal to
 *   [endUs] when there is none or nothing fits.
 * @property frames frames the transition plays for, at the project's rate;
 *   [requestedFrames] is what its length asks for.
 * @property limit why it plays shorter than set, if it does.
 */
@Immutable
public data class CutUi(
    val outgoingClipId: ClipId,
    val incomingClipId: ClipId,
    val cutUs: Long,
    val transition: Transition?,
    val startUs: Long = cutUs,
    val endUs: Long = cutUs,
    val frames: Int = 0,
    val requestedFrames: Int = 0,
    val limit: TransitionLimit? = null,
)

/** An image of the project offered as a sticker. */
@Immutable
public data class StickerChoice(val mediaId: MediaId, val uri: String, val title: String)

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

    /** The picked image can't be used as a sticker (unreadable, not an image, or in a temporary location). */
    STICKER_UNAVAILABLE,

    /** "Bring forward" on an overlay nothing overlapping is above. */
    LAYER_ALREADY_FRONT,

    /** "Send backward" on an overlay nothing overlapping is below. */
    LAYER_ALREADY_BACK,
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
        /** Every text and sticker, bottom layer first, with unsaved changes applied. */
        val overlays: List<OverlayUi> = emptyList(),
        /** The project canvas in pixels. Overlays are laid out on it, then scaled to the preview. */
        val canvasWidthPx: Int = ProjectSettings.DEFAULT.canvasWidthPx,
        val canvasHeightPx: Int = ProjectSettings.DEFAULT.canvasHeightPx,
        /** The project's images, offered as stickers. */
        val stickerChoices: List<StickerChoice> = emptyList(),
        /** The main track's videos and photos, in order, with unsaved transform changes applied. */
        val pictures: List<PictureUi> = emptyList(),
        /** One frame of the project's rate, in µs. A keyframe within half of it from the playhead counts as the one at the playhead. */
        val frameDurationUs: Long = 33_334,
        /** Every cut on the main track, in order, with its transition. */
        val cuts: List<CutUi> = emptyList(),
        /** The cut whose transition is being edited. Never at the same time as [selectedClip]. */
        val selectedCut: CutUi? = null,
    ) : EditorUiState {
        val selectedTrack: TimelineTrackUi? get() = selectedClip?.let { clip -> tracks.firstOrNull { it.id == clip.trackId } }

        /** The selected picture, if the selection is one. The preview canvas draws its frame and handles. */
        val selectedPicture: PictureUi? get() = selectedClip?.let { clip -> pictures.firstOrNull { it.clipId == clip.id } }

        /** The selected overlay, if the selection is one. The preview canvas draws it with handles. */
        val selectedOverlay: OverlayUi? get() = selectedClip?.let { clip -> overlays.firstOrNull { it.clipId == clip.id } }
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
    private val repository: ProjectRepository,
    createController: (CoroutineScope) -> PreviewController,
    private val autosave: ProjectAutosave,
    private val waveforms: WaveformSource = WaveformSource.NONE,
    private val newClipId: () -> ClipId = { ClipId(UUID.randomUUID().toString()) },
    private val images: ImageImporter = ImageImporter.NONE,
) : ViewModel() {

    public val controller: PreviewController = createController(viewModelScope)

    private val selectedId = savedState.getStateFlow<String?>(KEY_SELECTED, null)

    /** The outgoing clip of the cut whose transition panel is open. */
    private val selectedCutId = savedState.getStateFlow<String?>(KEY_SELECTED_CUT, null)

    private data class Drag(val base: Timeline, val clipId: ClipId, val handle: DragHandle, val originUs: Long)
    private data class Draft(val timeline: Timeline, val guideUs: Long?, val command: EditCommand? = null)

    private val draft = MutableStateFlow<Draft?>(null)

    /** An overlay change in progress (a drag on the preview, a slider), shown before it's saved. */
    private data class OverlayDraft(val clipId: ClipId, val overlay: Overlay, val animation: ClipAnimation)
    private val overlayDraft = MutableStateFlow<OverlayDraft?>(null)

    /** A clip transform change in progress, shown (in the real render) before it's saved. */
    private data class TransformDraft(val clipId: ClipId, val transform: ClipTransform, val animation: ClipAnimation)
    private val transformDraft = MutableStateFlow<TransformDraft?>(null)

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

    private val selection = combine(selectedId, selectedCutId) { clip, cut -> Selection(clip, cut) }

    private data class Selection(val clip: String?, val cut: String?)

    private val projectAndSelection = combine(repository.project(projectId), selection, analysed, overlayDraft, transformDraft) { project, selected, peaks, overlay, transform ->
        ProjectView(project, selected, peaks, overlay, transform)
    }

    private data class ProjectView(
        val project: Project?,
        val selected: Selection,
        val waveforms: Map<MediaId, Waveform>,
        val overlayDraft: OverlayDraft?,
        val transformDraft: TransformDraft?,
    )
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

    public val uiState: StateFlow<EditorUiState> = combine(projectAndSelection, editingState) { (project, selected, peaks, overlayEdit, transformEdit), editing ->
        latestProject = project
        if (project == null) {
            Built(EditorUiState.NotFound, null)
        } else {
            requestWaveforms(project)
            val persisted = DefaultArrangement.timelineFor(project)
            val saved = editing.pending ?: persisted
            val shown = editing.draft?.timeline ?: saved
            val mediaById = project.media.associateBy { it.id }
            // A transform being edited is rendered for real: the player applies it in place, without reloading.
            // (The selected overlay is hidden from the player while paused, without a reload: see init.)
            val rendered = transformEdit?.let { saved.withTransform(it.clipId, it.transform, it.animation) } ?: saved
            val preview = previewPlanFor(rendered, project, peaks)
            val tracks = tracksUi(shown, mediaById, peaks)
            val cuts = cutsUi(shown, mediaById)
            Built(
                EditorUiState.Ready(
                    projectName = project.name,
                    tracks = tracks,
                    durationUs = shown.durationUs,
                    selectedClip = selected.clip?.let { id -> tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id.value == id } } },
                    previewTrackKind = preview?.first,
                    canUndo = editing.history.canUndo,
                    canRedo = editing.history.canRedo,
                    snapGuideUs = editing.draft?.guideUs,
                    isDragging = editing.draft != null,
                    message = editing.message,
                    canvasAspectRatio = project.settings.aspectRatio,
                    overlays = overlaysUi(shown, mediaById, overlayEdit),
                    canvasWidthPx = project.settings.canvasWidthPx,
                    canvasHeightPx = project.settings.canvasHeightPx,
                    stickerChoices = project.media.filter { it.kind == MediaKind.IMAGE }.map {
                        StickerChoice(it.id, it.uri, it.displayName.orEmpty())
                    },
                    pictures = picturesUi(transformEdit?.let { shown.withTransform(it.clipId, it.transform, it.animation) } ?: shown, mediaById),
                    frameDurationUs = shown.frameRate.frameToUs(1),
                    cuts = cuts,
                    selectedCut = selected.cut?.let { id -> cuts.firstOrNull { it.outgoingClipId.value == id } },
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

    init {
        // While paused, the canvas draws the selected overlay itself (live, with handles), so the player leaves it out.
        // While playing, the player draws it, so a keyframed overlay moves frame-exactly with the picture.
        viewModelScope.launch {
            combine(selectedId, controller.state.map { it.isPlaying }.distinctUntilChanged()) { selected, playing -> selected.takeUnless { playing } }
                .distinctUntilChanged()
                // An ID that isn't an overlay's matches no layer, so it hides nothing.
                .collect { selected -> controller.setHiddenOverlay(selected) }
        }
    }

    // ---- Selection ----

    public fun selectClip(id: ClipId?) {
        // Selecting something else saves an overlay or transform change still in progress.
        if (overlayDraft.value?.clipId != id) commitOverlay()
        if (transformDraft.value?.clipId != id) commitTransform()
        savedState[KEY_SELECTED] = id?.value
        savedState[KEY_SELECTED_CUT] = null
    }

    /**
     * Opens the transition panel for the cut after [outgoingClipId] (or
     * closes it with null). A cut and a clip are never selected together.
     */
    public fun selectCut(outgoingClipId: ClipId?) {
        if (outgoingClipId != null) selectClip(null)
        savedState[KEY_SELECTED_CUT] = outgoingClipId?.value
    }

    // ---- Transitions ----

    /** Sets the transition at the cut after [outgoingClipId], or removes it with null. One undoable edit. */
    public fun setTransition(outgoingClipId: ClipId, transition: Transition?) {
        execute(EditCommand.SetTransition(outgoingClipId, transition))
    }

    /** Trims the clips at the cut after [outgoingClipId] just enough for its transition to play in full. One undoable edit. */
    public fun fitTransition(outgoingClipId: ClipId) {
        execute(EditCommand.FitTransition(outgoingClipId))
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
        commitOverlay()
        commitTransform()
        val current = currentTimeline() ?: return
        onHistoryStep(history.value.undo(current))
    }

    public fun redo() {
        commitOverlay()
        commitTransform()
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

    // ---- Text and stickers ----

    /**
     * Adds [text] at [atUs] (snapped to the frame grid) for
     * [OVERLAY_DURATION_US], centred, above every overlay it overlaps, and
     * selects it. At the very end of the timeline it is placed so it ends
     * there instead.
     */
    public fun addText(text: String, atUs: Long) {
        addOverlay(Overlay(OverlayContent.Text(text.take(OverlayContent.Text.MAX_LENGTH), DEFAULT_TEXT_STYLE)), mediaId = null, atUs = atUs)
    }

    /** Adds one of the project's images as a sticker at [atUs], like [addText]. */
    public fun addSticker(mediaId: MediaId, atUs: Long) {
        addOverlay(Overlay(OverlayContent.Sticker), mediaId, atUs)
    }

    /**
     * Adds an image picked on the device as a sticker. The image joins the
     * project's media (with lasting read access) but not the main track.
     * An image already in the project is reused.
     */
    public fun importSticker(uri: String, atUs: Long) {
        viewModelScope.launch {
            val ref = images.import(uri)
            if (ref == null || ref.kind != MediaKind.IMAGE) {
                message.value = EditorMessage.STICKER_UNAVAILABLE
                return@launch
            }
            val project = latestProject ?: return@launch
            val media = project.media.firstOrNull { it.uri == ref.uri } ?: run {
                val added = try {
                    repository.addMedia(projectId, listOf(ref), arrange = false)
                } catch (e: IllegalArgumentException) {
                    null // A temporary location the project can't keep.
                }
                if (added == null) {
                    message.value = EditorMessage.STICKER_UNAVAILABLE
                    return@launch
                }
                ref
            }
            addOverlay(Overlay(OverlayContent.Sticker), media.id, atUs, extraMedia = listOf(media))
        }
    }

    private fun addOverlay(overlay: Overlay, mediaId: MediaId?, atUs: Long, extraMedia: List<MediaRef> = emptyList()) {
        commitOverlay()
        val timeline = currentTimeline() ?: return
        val videoEndUs = timeline.mainTrack?.endUs ?: 0
        var start = Snapper.snap(atUs.coerceAtLeast(0), emptySet(), 0, timeline.frameRate).timeUs
        if (videoEndUs > 0 && start >= videoEndUs) start = (videoEndUs - OVERLAY_DURATION_US).coerceAtLeast(0)
        execute(EditCommand.AddOverlay(overlay, mediaId, start, OVERLAY_DURATION_US), extraMedia)
    }

    /**
     * Shows [overlay] for [clipId] at once, without saving: for changes that
     * arrive many times a second (dragging on the preview, sliders).
     * [commitOverlay] saves the latest one as a single undo step.
     *
     * [overlay] is the overlay as shown at the playhead [atUs], changed. On a
     * keyframed overlay, placement changes become keyframes at the playhead
     * ([Keyframing.recordEdit]); otherwise they change it everywhere.
     */
    public fun previewOverlay(clipId: ClipId, overlay: Overlay, atUs: Long? = null) {
        val current = overlayDraft.value
        if (current != null && current.clipId != clipId) commitOverlay()
        val clip = currentTimeline()?.findClip(clipId) ?: return
        val (static, animation) = recordOverlay(clip, overlay, atUs) ?: return
        overlayDraft.value = OverlayDraft(clipId, static, animation)
    }

    /** Saves the change shown by [previewOverlay], as one undoable edit. */
    public fun commitOverlay() {
        val pending = overlayDraft.value ?: return
        overlayDraft.value = null
        execute(EditCommand.SetOverlay(pending.clipId, pending.overlay, pending.animation))
    }

    /** Drops the change shown by [previewOverlay]. */
    public fun discardOverlayDraft() {
        overlayDraft.value = null
    }

    /** Changes [clipId]'s overlay at once, as one undoable edit (a color tap, a toggle). Placement follows [previewOverlay]'s keyframe rule. */
    public fun setOverlay(clipId: ClipId, overlay: Overlay, atUs: Long? = null) {
        overlayDraft.value = null
        val clip = currentTimeline()?.findClip(clipId) ?: return
        val (static, animation) = recordOverlay(clip, overlay, atUs) ?: return
        execute(EditCommand.SetOverlay(clipId, static, animation))
    }

    /** The static overlay and keyframes after [edited] (the overlay as shown at [atUs], changed) is applied. */
    private fun recordOverlay(clip: Clip, edited: Overlay, atUs: Long?): Pair<Overlay, ClipAnimation>? {
        val base = clip.overlay ?: return null
        if (atUs == null) return edited to clip.animation
        val local = keyframeTime(clip, atUs) ?: return edited to clip.animation
        // The change is measured from what the canvas showed at the exact playhead, and keyed on the frame under it.
        val (transform, animation) = Keyframing.recordEdit(base.transform, clip.animation, edited.transform, local, clip.localTimeOf(atUs))
        return edited.copy(transform = transform) to animation
    }

    /** Moves an overlay up or down among the overlays it overlaps in time. */
    public fun arrangeLayer(clipId: ClipId, move: LayerMove) {
        commitOverlay()
        execute(EditCommand.ArrangeLayer(clipId, move))
    }

    /** A copy of the overlay on top of it, nudged so both can be seen. The copy is selected. */
    public fun duplicateOverlay(clipId: ClipId) {
        commitOverlay()
        val clip = currentTimeline()?.findClip(clipId) ?: return
        val overlay = clip.overlay ?: return
        val nudged = overlay.transform.let { OverlayTransform.coerced(it.centerX + DUPLICATE_OFFSET, it.centerY + DUPLICATE_OFFSET, it.scale, it.rotationDegrees, it.opacity) }
        // The copy keeps the animation, its path nudged the same way.
        val animation = Keyframing.offsetPosition(clip.animation, DUPLICATE_OFFSET, DUPLICATE_OFFSET)
        execute(EditCommand.AddOverlay(overlay.copy(transform = nudged), clip.mediaId, clip.startUs, clip.durationUs, animation))
    }

    // ---- Clip transforms ----

    /**
     * Shows [transform] for [clipId] at once, without saving: for changes
     * that arrive many times a second (gestures on the preview, sliders).
     * The player renders it in place. [commitTransform] saves the latest one
     * as a single undo step.
     */
    public fun previewTransform(clipId: ClipId, transform: ClipTransform, atUs: Long? = null) {
        val current = transformDraft.value
        if (current != null && current.clipId != clipId) commitTransform()
        val timeline = currentTimeline() ?: return
        if (timeline.trackOf(clipId)?.kind != TrackKind.VIDEO) return
        val clip = timeline.findClip(clipId) ?: return
        val (static, animation) = recordTransform(clip, transform, atUs)
        transformDraft.value = TransformDraft(clipId, static, animation)
    }

    /** Saves the change shown by [previewTransform], as one undoable edit. */
    public fun commitTransform() {
        val pending = transformDraft.value ?: return
        transformDraft.value = null
        execute(EditCommand.SetClipTransform(pending.clipId, pending.transform, pending.animation))
    }

    /** Drops the change shown by [previewTransform]. */
    public fun discardTransformDraft() {
        transformDraft.value = null
    }

    /**
     * Changes [clipId]'s transform at once, as one undoable edit (a button, a
     * preset). [transform] is the transform as shown at the playhead [atUs],
     * changed; on a keyframed clip, placement changes become keyframes there.
     */
    public fun setTransform(clipId: ClipId, transform: ClipTransform, atUs: Long? = null) {
        transformDraft.value = null
        val clip = currentTimeline()?.findClip(clipId) ?: return
        val (static, animation) = recordTransform(clip, transform, atUs)
        execute(EditCommand.SetClipTransform(clipId, static, animation))
    }

    /** Puts the clip back as it was imported: whole picture, fitted, centred, upright, opaque, and not animated. */
    public fun resetTransform(clipId: ClipId) {
        transformDraft.value = null
        val clip = currentTimeline()?.findClip(clipId) ?: return
        execute(EditCommand.SetClipTransform(clipId, ClipTransform.DEFAULT, clip.animation.withoutTransform()))
    }

    /**
     * Centres the clip and sizes it to fit inside the canvas, or to fill it,
     * at its current rotation and crop (as shown at the playhead [atUs]).
     */
    public fun fitTransform(clipId: ClipId, mode: FitMode, atUs: Long? = null) {
        val picture = pictureFor(clipId) ?: return
        val settings = latestProject?.settings ?: return
        val shown = if (atUs != null) picture.at(atUs) else picture
        setTransform(clipId, shown.placement(settings.canvasWidthPx, settings.canvasHeightPx).fitted(mode), atUs)
    }

    /** The static transform and keyframes after [edited] (the transform as shown at [atUs], changed) is applied. */
    private fun recordTransform(clip: Clip, edited: ClipTransform, atUs: Long?): Pair<ClipTransform, ClipAnimation> {
        if (atUs == null) return edited to clip.animation
        val local = keyframeTime(clip, atUs) ?: return edited to clip.animation
        // The change is measured from what the canvas showed at the exact playhead, and keyed on the frame under it.
        return Keyframing.recordEdit(clip.transform, clip.animation, edited, local, clip.localTimeOf(atUs))
    }

    // ---- Keyframes ----
    // Keyframe actions work on the "moment" at the playhead: all placement channels at one clip-local time.

    /**
     * Adds a keyframe to [clipId]'s placement at the playhead [atUs]: every
     * channel (position, scale, rotation, opacity) holds its current value
     * there, so nothing moves until the keyframe is edited. One undo step.
     */
    public fun addKeyframe(clipId: ClipId, atUs: Long) {
        val clip = settledClip(clipId) ?: return
        val local = keyframeTime(clip, atUs) ?: return
        val static = staticChannels(clip) ?: return
        execute(EditCommand.SetClipAnimation(clipId, Keyframing.addMoment(static, clip.animation, local)))
    }

    /**
     * Deletes the keyframe at the playhead [atUs] (within half a frame) from
     * every channel. A channel losing its last keyframe keeps that value.
     */
    public fun removeKeyframe(clipId: ClipId, atUs: Long) {
        val clip = settledClip(clipId) ?: return
        val local = momentAt(clip, atUs) ?: return
        val static = staticChannels(clip) ?: return
        val (values, animation) = Keyframing.removeMoment(static, clip.animation, local)
        execute(withStatic(clip, values, animation) ?: return)
    }

    /** Sets how the keyframe at the playhead [atUs] eases toward the next one. */
    public fun setKeyframeEasing(clipId: ClipId, atUs: Long, easing: Easing) {
        val clip = settledClip(clipId) ?: return
        val local = momentAt(clip, atUs) ?: return
        execute(EditCommand.SetClipAnimation(clipId, Keyframing.setEasing(clip.animation, local, easing)))
    }

    /**
     * Moves the keyframe at the playhead [atUs] by [frames] frames (negative
     * is earlier), within the clip, replacing any keyframe already there.
     * Returns its new timeline time, so the playhead can follow it, or null
     * if nothing moved.
     */
    public fun nudgeKeyframe(clipId: ClipId, atUs: Long, frames: Int): Long? {
        val clip = settledClip(clipId) ?: return null
        val local = momentAt(clip, atUs) ?: return null
        val rate = currentTimeline()?.frameRate ?: return null
        val target = clip.localTimeOf(rate.frameToUs(rate.usToFrame(clip.timelineTimeOf(local), FrameRounding.NEAREST) + frames))
        if (target < 0 || target >= clip.durationUs || target == local) return null
        execute(EditCommand.SetClipAnimation(clipId, Keyframing.moveMoment(clip.animation, local, target)))
        return clip.timelineTimeOf(target)
    }

    /** Removes every placement keyframe of [clipId], keeping the placement shown at the playhead [atUs]. */
    public fun clearKeyframes(clipId: ClipId, atUs: Long) {
        val clip = settledClip(clipId) ?: return
        if (!clip.animation.animatesTransform) return
        val static = staticChannels(clip) ?: return
        val local = clip.localTimeOf(atUs).coerceIn(0, clip.durationUs - 1)
        val shown = static.keys.associateWith { channel ->
            clip.animation.valueAt(channel, local)?.toFloat() ?: static.getValue(channel)
        }
        execute(withStatic(clip, shown, clip.animation.withoutTransform()) ?: return)
    }

    // ---- Volume keyframes ----
    // Volume keyframes live in source time (GainKeyframe): the playhead maps to sourceInUs + (playhead - startUs).
    // Each action is one SetClipAudio edit, refused for clips without sound.

    /** Adds a volume keyframe at the playhead [atUs], holding the level there now. */
    public fun addGainKeyframe(clipId: ClipId, atUs: Long) {
        val clip = settledClip(clipId) ?: return
        val sourceUs = gainKeyframeTime(clip, atUs) ?: return
        execute(EditCommand.SetClipAudio(clipId, GainKeyframes.add(clip.audio, sourceUs)))
    }

    /** Deletes the volume keyframe at the playhead [atUs] (within half a frame). */
    public fun removeGainKeyframe(clipId: ClipId, atUs: Long) {
        val clip = settledClip(clipId) ?: return
        val keyframe = gainKeyframeAt(clip, atUs) ?: return
        execute(EditCommand.SetClipAudio(clipId, GainKeyframes.remove(clip.audio, keyframe.sourceTimeUs)))
    }

    /** Sets the level (0..1, a share of the clip's volume) of the volume keyframe at the playhead [atUs]. */
    public fun setGainKeyframeLevel(clipId: ClipId, atUs: Long, gain: Float) {
        val clip = settledClip(clipId) ?: return
        val keyframe = gainKeyframeAt(clip, atUs) ?: return
        execute(EditCommand.SetClipAudio(clipId, GainKeyframes.setGain(clip.audio, keyframe.sourceTimeUs, gain)))
    }

    /** Sets how the level moves from the volume keyframe at the playhead [atUs] to the next one. */
    public fun setGainKeyframeCurve(clipId: ClipId, atUs: Long, interpolation: GainInterpolation) {
        val clip = settledClip(clipId) ?: return
        val keyframe = gainKeyframeAt(clip, atUs) ?: return
        execute(EditCommand.SetClipAudio(clipId, GainKeyframes.setInterpolation(clip.audio, keyframe.sourceTimeUs, interpolation)))
    }

    /** Moves the volume keyframe at the playhead [atUs] by [frames] frames within the clip. Returns its new timeline time. */
    public fun nudgeGainKeyframe(clipId: ClipId, atUs: Long, frames: Int): Long? {
        val clip = settledClip(clipId) ?: return null
        val keyframe = gainKeyframeAt(clip, atUs) ?: return null
        val rate = currentTimeline()?.frameRate ?: return null
        val fromUs = clip.startUs + keyframe.sourceTimeUs - clip.sourceInUs
        val targetUs = rate.frameToUs(rate.usToFrame(fromUs, FrameRounding.NEAREST) + frames)
        if (targetUs !in clip || targetUs == fromUs) return null
        execute(EditCommand.SetClipAudio(clipId, GainKeyframes.move(clip.audio, keyframe.sourceTimeUs, clip.sourceInUs + targetUs - clip.startUs)))
        return targetUs
    }

    /** Removes every volume keyframe of [clipId]. */
    public fun clearGainKeyframes(clipId: ClipId) {
        val clip = settledClip(clipId) ?: return
        if (clip.audio.keyframes.isEmpty()) return
        execute(EditCommand.SetClipAudio(clipId, GainKeyframes.clear(clip.audio)))
    }

    /** The source time a volume keyframe added at the playhead [atUs] goes to: the start of the frame on screen. Null outside the clip. */
    private fun gainKeyframeTime(clip: Clip, atUs: Long): Long? {
        if (atUs !in clip) return null
        gainKeyframeAt(clip, atUs)?.let { return it.sourceTimeUs }
        val rate = currentTimeline()?.frameRate ?: return null
        val local = clip.localTimeOf(rate.snapUs(atUs, FrameRounding.FLOOR)).coerceIn(0, clip.durationUs - 1)
        return clip.sourceInUs + local
    }

    /** The volume keyframe within half a frame of the playhead [atUs]. */
    private fun gainKeyframeAt(clip: Clip, atUs: Long) =
        GainKeyframes.near(clip.audio, clip.sourceInUs + clip.localTimeOf(atUs), halfFrameUs())

    /** The clip with any change in progress saved first, so keyframe actions start from what is shown. */
    private fun settledClip(clipId: ClipId): Clip? {
        commitOverlay()
        commitTransform()
        return currentTimeline()?.findClip(clipId)
    }

    /** The keyframe moment within half a frame of the playhead [atUs], in clip-local time. */
    private fun momentAt(clip: Clip, atUs: Long): Long? = Keyframing.momentNear(clip.animation, clip.localTimeOf(atUs), halfFrameUs())

    /**
     * The clip-local time an edit at the playhead [atUs] applies to: a
     * keyframe within half a frame, or else the start of the frame on
     * screen. Null outside the clip.
     */
    private fun keyframeTime(clip: Clip, atUs: Long): Long? {
        if (atUs !in clip) return null
        momentAt(clip, atUs)?.let { return it }
        val rate = currentTimeline()?.frameRate ?: return null
        return clip.localTimeOf(rate.snapUs(atUs, FrameRounding.FLOOR)).coerceIn(0, clip.durationUs - 1)
    }

    private fun halfFrameUs(): Long = (currentTimeline()?.frameRate?.frameToUs(1) ?: DEFAULT_FRAME_US) / 2

    /** The clip's static placement channels: its overlay's, or a picture's transform. */
    private fun staticChannels(clip: Clip): Map<TransformChannel, Float>? {
        val overlay = clip.overlay
        return when {
            overlay != null -> Keyframing.channels(overlay.transform)
            currentTimeline()?.trackOf(clip.id)?.kind == TrackKind.VIDEO -> Keyframing.channels(clip.transform)
            else -> null
        }
    }

    /** The command giving [clip] the static channel [values] and [animation], as one edit. */
    private fun withStatic(clip: Clip, values: Map<TransformChannel, Float>, animation: ClipAnimation): EditCommand? {
        val overlay = clip.overlay
        return when {
            overlay != null -> EditCommand.SetOverlay(clip.id, overlay.copy(transform = Keyframing.withChannels(overlay.transform, values)), animation)
            currentTimeline()?.trackOf(clip.id)?.kind == TrackKind.VIDEO ->
                EditCommand.SetClipTransform(clip.id, Keyframing.withChannels(clip.transform, values), animation)
            else -> null
        }
    }

    /** [clipId] as the canvas places it, with any change in progress. */
    private fun pictureFor(clipId: ClipId): PictureUi? {
        val project = latestProject ?: return null
        val timeline = currentTimeline() ?: return null
        val draft = transformDraft.value?.takeIf { it.clipId == clipId }
        val shown = draft?.let { timeline.withTransform(it.clipId, it.transform, it.animation) } ?: timeline
        return picturesUi(shown, project.media.associateBy { it.id }, allVideoTracks = true).firstOrNull { it.clipId == clipId }
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
        commitOverlay()
        commitTransform()
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

    /** [extraMedia]: media just added to the project that the latest project copy may not show yet. */
    private fun editContext(extraMedia: List<MediaRef> = emptyList()): EditContext? {
        val project = latestProject ?: return null
        return EditContext(
            media = (project.media + extraMedia).associate { it.id to MediaBounds(it.kind, it.durationUs, it.hasAudio) },
            newClipId = newClipId,
        )
    }

    private fun execute(command: EditCommand, extraMedia: List<MediaRef> = emptyList()) {
        // Any other edit first saves an overlay or transform change still in progress, so it isn't lost or applied to the wrong state.
        if (command !is EditCommand.SetOverlay) commitOverlay()
        if (command !is EditCommand.SetClipTransform) commitTransform()
        val timeline = currentTimeline() ?: return
        val context = editContext(extraMedia) ?: return
        when (val result = TimelineEditor.apply(timeline, command, context)) {
            is EditResult.Applied -> {
                history.value = history.value.record(command, timeline, result.timeline)
                save(result.timeline)
                when (command) {
                    is EditCommand.Delete -> selectClip(null)
                    // The transition panel stays open on its cut.
                    is EditCommand.SetTransition, is EditCommand.FitTransition -> Unit
                    else -> result.affectedClipId?.let(::selectClip)
                }
            }
            is EditResult.Rejected -> message.value = when (result.reason) {
                EditRejection.TOO_CLOSE_TO_EDGE -> EditorMessage.SPLIT_TOO_CLOSE
                EditRejection.OUTSIDE_CLIP -> EditorMessage.NOTHING_TO_SPLIT
                EditRejection.NO_CHANGE -> when {
                    command is EditCommand.CloseGaps -> EditorMessage.NO_GAPS
                    command is EditCommand.ArrangeLayer && (command.move == LayerMove.FORWARD || command.move == LayerMove.TO_FRONT) ->
                        EditorMessage.LAYER_ALREADY_FRONT
                    command is EditCommand.ArrangeLayer -> EditorMessage.LAYER_ALREADY_BACK
                    else -> null
                }
                EditRejection.NO_AUDIO -> EditorMessage.CLIP_HAS_NO_AUDIO
                EditRejection.UNSUPPORTED_MEDIA, EditRejection.MEDIA_NOT_FOUND ->
                    if (command is EditCommand.AddOverlay) EditorMessage.STICKER_UNAVAILABLE else null
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
                    val text = clip.overlay?.content as? OverlayContent.Text
                    if (text != null) {
                        return@mapNotNull TimelineClipUi(
                            id = clip.id,
                            trackId = track.id,
                            startUs = clip.startUs,
                            durationUs = clip.durationUs,
                            sourceInUs = clip.sourceInUs,
                            uri = "",
                            mediaKind = MediaKind.IMAGE,
                            // The first line names the clip; the timeline shows a placeholder for empty text.
                            title = text.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim(),
                            overlay = clip.overlay,
                            keyframeTimesUs = keyframeMarks(clip),
                        )
                    }
                    val media = mediaById[clip.mediaId ?: return@mapNotNull null] ?: return@mapNotNull null
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
                        overlay = clip.overlay,
                        keyframeTimesUs = keyframeMarks(clip),
                        gainKeyframeTimesUs = if (media.hasAudio) gainMarks(clip) else emptyList(),
                    )
                },
            )
        }

    /** Every cut of the main track, with its transition fitted the way preview and export will play it. */
    private fun cutsUi(timeline: Timeline, mediaById: Map<MediaId, MediaRef>): List<CutUi> {
        val clips = timeline.mainTrack?.clips.orEmpty()
        val resolved = TransitionLayout.resolve(timeline) { clip ->
            val media = clip.mediaId?.let(mediaById::get)
            when {
                media == null -> clip.sourceOutUs
                media.kind == MediaKind.IMAGE -> null
                media.durationUs <= 0 -> clip.sourceOutUs
                else -> media.durationUs
            }
        }.associateBy { it.outgoingIndex }
        return clips.zipWithNext().mapIndexed { index, (outgoing, incoming) ->
            val window = resolved[index]?.window
            CutUi(
                outgoingClipId = outgoing.id,
                incomingClipId = incoming.id,
                cutUs = outgoing.endUs,
                transition = outgoing.transitionOut,
                startUs = window?.startUs ?: outgoing.endUs,
                endUs = window?.endUs ?: outgoing.endUs,
                frames = window?.frames ?: 0,
                requestedFrames = window?.requestedFrames ?: 0,
                limit = window?.limit,
            )
        }
    }

    /** Volume keyframe times inside [clip], clip-local, for the timeline's markers. */
    private fun gainMarks(clip: Clip): List<Long> =
        clip.audio.keyframes.map { it.sourceTimeUs - clip.sourceInUs }.filter { it in 0 until clip.durationUs }

    /** Placement keyframe times inside [clip], for the timeline's markers. */
    private fun keyframeMarks(clip: Clip): List<Long> =
        if (!clip.animation.animatesTransform) emptyList() else clip.animation.transformKeyframeTimes().filter { it in 0 until clip.durationUs }

    /**
     * The main track's videos and photos (or, with [allVideoTracks], those of
     * every video track), in timeline order.
     */
    private fun picturesUi(timeline: Timeline, mediaById: Map<MediaId, MediaRef>, allVideoTracks: Boolean = false): List<PictureUi> {
        val tracks = if (allVideoTracks) timeline.tracks.filter { it.kind == TrackKind.VIDEO } else listOfNotNull(timeline.mainTrack)
        return tracks.flatMap { track ->
            track.clips.mapNotNull { clip ->
                val media = mediaById[clip.mediaId ?: return@mapNotNull null] ?: return@mapNotNull null
                if (media.kind == MediaKind.AUDIO) return@mapNotNull null
                PictureUi(clip.id, clip.startUs, clip.endUs, clip.transform, media.displayWidthPx, media.displayHeightPx, clip.animation)
            }
        }
    }

    /** [timeline] with [clipId]'s transform and keyframes replaced, if it is on a video track. */
    private fun Timeline.withTransform(clipId: ClipId, transform: ClipTransform, animation: ClipAnimation): Timeline {
        val track = trackOf(clipId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return this
        return withTrack(track.copy(clips = track.clips.map { if (it.id == clipId) it.copy(transform = transform, animation = animation) else it }))
    }

    /** Overlays bottom layer first (the reverse of the track list), with the change in progress applied. */
    private fun overlaysUi(timeline: Timeline, mediaById: Map<MediaId, MediaRef>, edit: OverlayDraft?): List<OverlayUi> =
        timeline.overlayTracks.asReversed().flatMap { track ->
            track.clips.mapNotNull { clip ->
                val overlay = clip.overlay ?: return@mapNotNull null
                val image = clip.mediaId?.let { mediaById[it] ?: return@mapNotNull null }
                val draft = edit?.takeIf { it.clipId == clip.id }
                OverlayUi(
                    clipId = clip.id,
                    startUs = clip.startUs,
                    endUs = clip.endUs,
                    overlay = draft?.overlay ?: overlay,
                    animation = draft?.animation ?: clip.animation,
                    imageUri = image?.uri,
                    imageWidthPx = image?.displayWidthPx ?: 0,
                    imageHeightPx = image?.displayHeightPx ?: 0,
                    imageRotationDegrees = image?.rotationDegrees ?: 0,
                )
            }
        }

    private companion object {
        const val KEY_SELECTED = "selectedClipId"
        const val KEY_SELECTED_CUT = "selectedCutClipId"

        /** A duplicate sits this far (a fraction of the canvas) right of and below the original. */
        const val DUPLICATE_OFFSET = 0.03f

        /** One frame at 30 fps, for when no timeline is loaded yet. */
        const val DEFAULT_FRAME_US = 33_334L

        /** New text: white, with a soft shadow so it reads on light and dark footage. */
        val DEFAULT_TEXT_STYLE = TextStyle(shadow = TextShadow(Argb.BLACK.withAlpha(140), blurFraction = 0.06f, offsetXFraction = 0.02f, offsetYFraction = 0.02f))
    }
}

/** How long a new text or sticker lasts. */
public const val OVERLAY_DURATION_US: Long = 3_000_000

/**
 * Brings an image picked on the device within the project's reach: keeps
 * read access to it and reads its details. Implemented by the app with the
 * media layer.
 */
public fun interface ImageImporter {
    /** The image's media info, or null if it can't be used (unreadable, not an image, or in a temporary location). */
    public suspend fun import(uri: String): MediaRef?

    public companion object {
        public val NONE: ImageImporter = ImageImporter { null }
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
 * - Every overlay is in the plan, with its keyframes. While paused, the one
 *   being edited is hidden from the player without a reload
 *   ([PreviewController.setHiddenOverlay]); the editing canvas draws it
 *   with the same painter.
 * - Returns null when there's nothing to play, or a file is unavailable.
 */
internal fun previewPlanFor(
    timeline: Timeline,
    project: Project,
    waveforms: Map<MediaId, Waveform>,
): Pair<TrackKind, PreviewPlan>? {
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
