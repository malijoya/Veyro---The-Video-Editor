package com.editor.veyro.feature.project.overview

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.data.project.ProjectRepository
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

@Immutable
public sealed interface ProjectUiState {
    public data object Loading : ProjectUiState
    public data object NotFound : ProjectUiState
    public data object LoadFailed : ProjectUiState

    /** The user deleted the project from this screen. The screen then closes. */
    public data object Deleted : ProjectUiState
    public data class Loaded(
        val project: Project,
        /** URIs of media whose source file can't be opened. Null while still checking. */
        val missingUris: Set<String>? = null,
        val actionFailed: Boolean = false,
    ) : ProjectUiState
}

/**
 * One saved project: its details and media, plus rename, delete and a
 * check for missing source files.
 *
 * [isAvailable] opens each source file's descriptor without reading it. It
 * runs whenever the project changes and on [refreshAvailability], which the
 * screen calls on resume, because files can be deleted while Veyro is in the
 * background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class ProjectViewModel(
    private val projectId: ProjectId,
    private val repository: ProjectRepository,
    private val isAvailable: suspend (uri: String) -> Boolean,
) : ViewModel() {

    private val actionFailed = MutableStateFlow(false)

    /** True from the moment the user confirms delete until it fails. */
    private val deleting = MutableStateFlow(false)
    private val availabilityCheck = MutableStateFlow(0)

    private val projectWithAvailability: Flow<ProjectUiState> =
        combine(repository.project(projectId), availabilityCheck) { project, _ -> project }
            .transformLatest { project ->
                if (project == null) {
                    emit(ProjectUiState.NotFound)
                    return@transformLatest
                }
                emit(ProjectUiState.Loaded(project))
                val missing = project.media.map { it.uri }.distinct().filterNot { isAvailable(it) }.toSet()
                emit(ProjectUiState.Loaded(project, missingUris = missing))
            }
            .catch { emit(ProjectUiState.LoadFailed) }

    public val uiState: StateFlow<ProjectUiState> = combine(
        projectWithAvailability,
        actionFailed,
        deleting,
    ) { state, failed, isDeleting ->
        when {
            // During our own delete, "not found" means the delete succeeded.
            isDeleting && state == ProjectUiState.NotFound -> ProjectUiState.Deleted
            state is ProjectUiState.Loaded -> state.copy(actionFailed = failed)
            else -> state
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState.Loading)

    public fun refreshAvailability() {
        availabilityCheck.value++
    }

    public fun rename(name: String) {
        viewModelScope.launch {
            actionFailed.value = try {
                repository.rename(projectId, name)
                false
            } catch (e: IOException) {
                true
            } catch (e: IllegalArgumentException) {
                true
            }
        }
    }

    public fun delete() {
        viewModelScope.launch {
            deleting.value = true
            try {
                repository.delete(projectId)
            } catch (e: IOException) {
                deleting.value = false
                actionFailed.value = true
            }
        }
    }

    public fun actionErrorShown() {
        actionFailed.value = false
    }
}

