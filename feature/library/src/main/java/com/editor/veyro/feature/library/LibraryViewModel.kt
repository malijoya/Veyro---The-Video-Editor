package com.editor.veyro.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.data.project.ProjectSummary
import com.editor.veyro.data.project.file.ProjectInterchange
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

/** What a project card shows, from the project index (no document is opened). */
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

public enum class LibraryMessage { RENAME_FAILED, DELETE_FAILED, IMPORT_NOT_PROJECT, IMPORT_NEWER_VERSION, IMPORT_DAMAGED, IMPORT_FAILED }

@Immutable
public data class LibraryUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val projects: List<ProjectCardUi> = emptyList(),
    val sort: ProjectSort = ProjectSort.LAST_EDITED,
    val message: LibraryMessage? = null,
    val isImporting: Boolean = false,
    /** Set after a project file is imported. The screen opens it, then calls [LibraryViewModel.importedProjectOpened]. */
    val importedProjectId: ProjectId? = null,
)

/**
 * Backs the Home and Projects tabs: the saved project list (from the
 * index), plus rename, delete, and importing a project file.
 *
 * [readProjectFile] reads a user-picked file's text. It's injected so the
 * import logic is unit-testable.
 */
public class LibraryViewModel(
    private val repository: ProjectRepository,
    private val readProjectFile: suspend (uri: String) -> String = { error("Project file import not configured") },
) : ViewModel() {

    private sealed interface Load {
        data class Loaded(val projects: List<ProjectSummary>) : Load
        data object Failed : Load
    }

    private data class ImportState(val running: Boolean = false, val openId: ProjectId? = null)

    private val sort = MutableStateFlow(ProjectSort.LAST_EDITED)
    private val message = MutableStateFlow<LibraryMessage?>(null)
    private val importState = MutableStateFlow(ImportState())
    private val collator = Collator.getInstance()

    public val uiState: StateFlow<LibraryUiState> = combine(
        repository.summaries.map<List<ProjectSummary>, Load> { Load.Loaded(it) }.catch { emit(Load.Failed) },
        sort,
        message,
        importState,
    ) { load, sort, message, import ->
        when (load) {
            Load.Failed -> LibraryUiState(isLoading = false, loadFailed = true, sort = sort, message = message)
            is Load.Loaded -> LibraryUiState(
                isLoading = false,
                projects = sorted(load.projects, sort).map { it.toCard() },
                sort = sort,
                message = message,
                isImporting = import.running,
                importedProjectId = import.openId,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Imports a `.veyro.json` project file picked by the user, as a new project. */
    public fun importProjectFile(uri: String) {
        if (importState.value.running) return
        importState.value = ImportState(running = true)
        viewModelScope.launch {
            val text = try {
                readProjectFile(uri)
            } catch (e: IOException) {
                fail(LibraryMessage.IMPORT_FAILED)
                return@launch
            } catch (e: SecurityException) {
                fail(LibraryMessage.IMPORT_FAILED)
                return@launch
            }
            when (val result = ProjectInterchange.import(text)) {
                is ProjectInterchange.ImportResult.Ok -> try {
                    val stored = repository.importProject(result.project)
                    importState.value = ImportState(openId = stored.id)
                } catch (e: IOException) {
                    fail(LibraryMessage.IMPORT_FAILED)
                }
                ProjectInterchange.ImportResult.NotAProjectFile -> fail(LibraryMessage.IMPORT_NOT_PROJECT)
                ProjectInterchange.ImportResult.NewerVersion -> fail(LibraryMessage.IMPORT_NEWER_VERSION)
                ProjectInterchange.ImportResult.Damaged -> fail(LibraryMessage.IMPORT_DAMAGED)
            }
        }
    }

    public fun importedProjectOpened() {
        importState.value = ImportState()
    }

    private fun fail(reason: LibraryMessage) {
        importState.value = ImportState()
        message.value = reason
    }

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

    private fun sorted(projects: List<ProjectSummary>, sort: ProjectSort): List<ProjectSummary> = when (sort) {
        ProjectSort.LAST_EDITED -> projects // Repository order: most recently updated first.
        ProjectSort.CREATED -> projects.sortedByDescending { it.createdAtEpochMs }
        ProjectSort.NAME -> projects.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    private fun ProjectSummary.toCard() = ProjectCardUi(
        id = id,
        name = name,
        coverUri = coverUri,
        coverKind = coverKind,
        mediaCount = mediaCount,
        videoDurationUs = videoDurationUs,
        hasImages = hasImages,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
