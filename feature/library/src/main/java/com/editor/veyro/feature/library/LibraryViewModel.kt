package com.editor.veyro.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.data.project.ProjectRepository
import java.io.IOException
import java.text.Collator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

public enum class ProjectSort { LAST_EDITED, CREATED, NAME }

/** What a project card shows, derived from a saved [Project]. */
@Immutable
public data class ProjectCardUi(
    val id: ProjectId,
    val name: String,
    val coverUri: String?,
    val coverKind: MediaKind?,
    val mediaCount: Int,
    val videoDurationUs: Long,
    val hasImages: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

public enum class LibraryMessage { RENAME_FAILED, DELETE_FAILED }

@Immutable
public data class LibraryUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val projects: List<ProjectCardUi> = emptyList(),
    val sort: ProjectSort = ProjectSort.LAST_EDITED,
    val message: LibraryMessage? = null,
)

/** Backs the Home and Projects tabs: the saved project list, plus rename and delete. */
public class LibraryViewModel(private val repository: ProjectRepository) : ViewModel() {

    private sealed interface Load {
        data class Loaded(val projects: List<Project>) : Load
        data object Failed : Load
    }

    private val sort = MutableStateFlow(ProjectSort.LAST_EDITED)
    private val message = MutableStateFlow<LibraryMessage?>(null)
    private val collator = Collator.getInstance()

    public val uiState: StateFlow<LibraryUiState> = combine(
        repository.projects.map<List<Project>, Load> { Load.Loaded(it) }.catch { emit(Load.Failed) },
        sort,
        message,
    ) { load, sort, message ->
        when (load) {
            Load.Failed -> LibraryUiState(isLoading = false, loadFailed = true, sort = sort, message = message)
            is Load.Loaded -> LibraryUiState(
                isLoading = false,
                projects = sorted(load.projects, sort).map { it.toCard() },
                sort = sort,
                message = message,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    public fun setSort(value: ProjectSort) {
        sort.value = value
    }

    public fun rename(id: ProjectId, name: String) {
        viewModelScope.launch {
            try {
                repository.rename(id, name)
            } catch (e: IOException) {
                message.value = LibraryMessage.RENAME_FAILED
            } catch (e: IllegalArgumentException) {
                message.value = LibraryMessage.RENAME_FAILED
            }
        }
    }

    public fun delete(id: ProjectId) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: IOException) {
                message.value = LibraryMessage.DELETE_FAILED
            }
        }
    }

    public fun messageShown() {
        message.value = null
    }

    private fun sorted(projects: List<Project>, sort: ProjectSort): List<Project> = when (sort) {
        ProjectSort.LAST_EDITED -> projects // Repository order: most recently updated first.
        ProjectSort.CREATED -> projects.sortedByDescending { it.createdAtEpochMs }
        ProjectSort.NAME -> projects.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    private fun Project.toCard() = ProjectCardUi(
        id = id,
        name = name,
        coverUri = coverMedia?.uri,
        coverKind = coverMedia?.kind,
        mediaCount = media.size,
        videoDurationUs = mediaDurationUs,
        hasImages = media.any { it.kind == MediaKind.IMAGE },
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
