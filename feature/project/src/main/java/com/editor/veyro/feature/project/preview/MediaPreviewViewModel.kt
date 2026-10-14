package com.editor.veyro.feature.project.preview

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.media.playback.PreviewController
import com.editor.veyro.media.playback.PreviewPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@Immutable
public sealed interface MediaPreviewUiState {
    public data object Loading : MediaPreviewUiState
    public data object NotFound : MediaPreviewUiState
    public data object LoadFailed : MediaPreviewUiState
    public data class Ready(
        /** The asset being previewed. */
        val media: MediaRef,
        /** All of the project's media, for switching without leaving the screen. */
        val projectMedia: List<MediaRef>,
        val projectName: String,
    ) : MediaPreviewUiState
}

/**
 * Previews one source asset of a project.
 *
 * - Owns a single [PreviewController] for the life of the screen. Switching
 *   assets with [select] reuses the same player with a new plan.
 * - Images have no playback, so selecting one leaves the player's current
 *   plan untouched and the screen shows the still.
 * - The selected asset ID is kept in [SavedStateHandle] and survives process
 *   death.
 *
 * This previews single source files only. Timeline playback and multi-layer
 * compositing are not implemented yet.
 */
public class MediaPreviewViewModel(
    private val savedState: SavedStateHandle,
    projectId: ProjectId,
    initialMediaId: MediaId,
    repository: ProjectRepository,
    createController: (CoroutineScope) -> PreviewController,
) : ViewModel() {

    public val controller: PreviewController = createController(viewModelScope)

    private val selectedId = savedState.getStateFlow(KEY_SELECTED, initialMediaId.value)

    public val uiState: StateFlow<MediaPreviewUiState> = combine(
        repository.project(projectId),
        selectedId,
    ) { project, selected ->
        val media = project?.media?.firstOrNull { it.id.value == selected }
        if (project == null || media == null) {
            MediaPreviewUiState.NotFound
        } else {
            MediaPreviewUiState.Ready(media, project.media, project.name)
        }
    }
        .catch { emit(MediaPreviewUiState.LoadFailed) }
        .onEach { state ->
            if (state !is MediaPreviewUiState.Ready) return@onEach
            if (state.media.kind != MediaKind.IMAGE && state.media.durationUs > 0) {
                // A no-op when the plan is unchanged (for example when the project is renamed).
                controller.setPlan(PreviewPlan.forAsset(state.media))
            } else {
                // A still is showing. Don't keep playing the previous clip behind it.
                controller.pause()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaPreviewUiState.Loading)

    public fun select(mediaId: MediaId) {
        savedState[KEY_SELECTED] = mediaId.value
    }

    override fun onCleared() {
        controller.release()
    }

    private companion object {
        const val KEY_SELECTED = "selectedMediaId"
    }
}
