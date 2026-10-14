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
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.media.playback.PreviewController
import com.editor.veyro.media.playback.PreviewPlan
import com.editor.veyro.media.playback.PreviewSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** A clip as the timeline UI draws it. Stable and equality-comparable, so unchanged clips skip recomposition. */
@Immutable
public data class TimelineClipUi(
    val id: ClipId,
    val startUs: Long,
    val durationUs: Long,
    val sourceInUs: Long,
    val uri: String,
    val mediaKind: MediaKind,
    val title: String,
) {
    val endUs: Long get() = startUs + durationUs
}

@Immutable
public data class TimelineTrackUi(
    val id: TrackId,
    val kind: TrackKind,
    val clips: List<TimelineClipUi>,
)

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
    ) : EditorUiState
}

/**
 * The editor: the project's timeline, clip selection, and a preview player
 * following the playhead.
 *
 * The timeline is [DefaultArrangement] of the project's media. It is rebuilt
 * whenever the media changes (for example after "Add media"). Editing
 * operations and storing the arrangement come with the timeline core (M1).
 * Selection is kept in [SavedStateHandle].
 */
public class EditorViewModel(
    private val savedState: SavedStateHandle,
    projectId: ProjectId,
    repository: ProjectRepository,
    createController: (CoroutineScope) -> PreviewController,
) : ViewModel() {

    public val controller: PreviewController = createController(viewModelScope)

    private val selectedId = savedState.getStateFlow<String?>(KEY_SELECTED, null)

    /** UI state plus the matching preview plan. The plan goes to the controller, not the UI. */
    private class Built(val state: EditorUiState, val plan: PreviewPlan?)

    public val uiState: StateFlow<EditorUiState> = combine(repository.project(projectId), selectedId) { project, selected ->
        project?.let { buildState(it, selected) } ?: Built(EditorUiState.NotFound, null)
    }
        .catch { emit(Built(EditorUiState.LoadFailed, null)) }
        // setPlan is a no-op for an unchanged plan, so selection changes don't reload the player.
        .onEach { built -> built.plan?.let(controller::setPlan) }
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState.Loading)

    public fun selectClip(id: ClipId?) {
        savedState[KEY_SELECTED] = id?.value
    }

    override fun onCleared() {
        controller.release()
    }

    private fun buildState(project: Project, selected: String?): Built {
        val timeline = DefaultArrangement.build(project)
        val mediaById = project.media.associateBy { it.id }
        val tracks = timeline.tracks.map { track ->
            TimelineTrackUi(
                id = track.id,
                kind = track.kind,
                clips = track.clips.mapNotNull { clip ->
                    val media = mediaById[clip.mediaId] ?: return@mapNotNull null
                    TimelineClipUi(
                        id = clip.id,
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
        val preview = previewPlanFor(timeline, mediaById)
        val state = EditorUiState.Ready(
            projectName = project.name,
            tracks = tracks,
            durationUs = timeline.durationUs,
            selectedClip = selected?.let { id -> tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id.value == id } } },
            previewTrackKind = preview?.first,
        )
        return Built(state, preview?.second)
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
 * Returns null when there's nothing to play, or when the track has gaps.
 * The default arrangement never has gaps, and gap handling comes with
 * editing.
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
