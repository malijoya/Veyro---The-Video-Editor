package com.editor.veyro.feature.project.overview

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.project.MediaHealth
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.data.project.file.ProjectInterchange
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

/** One-off results shown in a snackbar. */
public enum class ProjectMessage { ACTION_FAILED, EXPORTED, EXPORT_FAILED }

@Immutable
public sealed interface ProjectUiState {
    public data object Loading : ProjectUiState
    public data object NotFound : ProjectUiState
    public data object LoadFailed : ProjectUiState

    /** The user deleted the project from this screen. The screen then closes. */
    public data object Deleted : ProjectUiState
    public data class Loaded(
        val project: Project,
        /** Health of each source URI. Null while the check runs. */
        val mediaHealth: Map<String, MediaHealth>? = null,
        val message: ProjectMessage? = null,
    ) : ProjectUiState {
        val missingUris: Set<String> get() = mediaHealth.orEmpty().filterValues { it == MediaHealth.MISSING }.keys
        val changedUris: Set<String> get() = mediaHealth.orEmpty().filterValues { it == MediaHealth.CHANGED }.keys
    }
}

/**
 * One saved project: details, media health, settings, rename, delete and
 * export.
 *
 * [checkMedia] checks whether each source file still opens, and whether it
 * is the same size as at import. It runs when the project changes and on
 * [refreshAvailability], which the screen calls on resume, because files can
 * change while Veyro is in the background. [writeProjectFile] writes an
 * exported project to a user-chosen document.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class ProjectViewModel(
    private val projectId: ProjectId,
    private val repository: ProjectRepository,
    private val checkMedia: suspend (MediaRef) -> MediaHealth,
    private val writeProjectFile: suspend (uri: String, text: String) -> Unit = { _, _ -> throw IOException("Export not configured") },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val message = MutableStateFlow<ProjectMessage?>(null)

    /** True from the moment the user confirms delete until it fails. */
    private val deleting = MutableStateFlow(false)
    private val availabilityCheck = MutableStateFlow(0)
    private var latest: Project? = null

    private val projectWithHealth: Flow<ProjectUiState> =
        combine(repository.project(projectId), availabilityCheck) { project, _ -> project }
            .transformLatest { project ->
                latest = project
                if (project == null) {
                    emit(ProjectUiState.NotFound)
                    return@transformLatest
                }
                emit(ProjectUiState.Loaded(project))
                val health = project.media.distinctBy { it.uri }.associate { it.uri to checkMedia(it) }
                emit(ProjectUiState.Loaded(project, mediaHealth = health))
            }
            .catch { emit(ProjectUiState.LoadFailed) }

    public val uiState: StateFlow<ProjectUiState> = combine(projectWithHealth, message, deleting) { state, message, isDeleting ->
        when {
            // During our own delete, "not found" means the delete succeeded.
            isDeleting && state == ProjectUiState.NotFound -> ProjectUiState.Deleted
            state is ProjectUiState.Loaded -> state.copy(message = message)
            else -> state
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState.Loading)

    public fun refreshAvailability() {
        availabilityCheck.value++
    }

    public fun rename(name: String) = act { repository.rename(projectId, name) }

    public fun updateSettings(settings: ProjectSettings) = act { repository.updateSettings(projectId, settings) }

    /** The suggested file name for an export, such as `Trip.veyro.json`. */
    public fun exportFileName(): String = ProjectInterchange.fileNameFor(latest?.name ?: "project")

    /** Writes the project, with its timeline, settings and media references, to [uri] as a portable project file. */
    public fun exportTo(uri: String) {
        val project = latest ?: return
        viewModelScope.launch {
            message.value = try {
                writeProjectFile(uri, ProjectInterchange.export(project, clock()))
                ProjectMessage.EXPORTED
            } catch (e: IOException) {
                ProjectMessage.EXPORT_FAILED
            } catch (e: SecurityException) {
                ProjectMessage.EXPORT_FAILED
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
                message.value = ProjectMessage.ACTION_FAILED
            }
        }
    }

    public fun messageShown() {
        message.value = null
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: IOException) {
                message.value = ProjectMessage.ACTION_FAILED
            } catch (e: IllegalArgumentException) {
                message.value = ProjectMessage.ACTION_FAILED
            }
        }
    }
}
