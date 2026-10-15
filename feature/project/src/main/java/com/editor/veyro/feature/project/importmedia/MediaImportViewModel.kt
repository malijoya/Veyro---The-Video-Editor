package com.editor.veyro.feature.project.importmedia

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectName
import com.editor.veyro.data.project.MediaAccessReleaser
import com.editor.veyro.data.project.ProjectRepository
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Which system picker to open.
 * - [GALLERY]: the Photo Picker (photos and videos). It needs no storage
 *   permission and falls back to the document picker on devices without it.
 * - [FILES]: the Storage Access Framework, showing video, images and audio
 *   from any provider (Downloads, Drive, SD card).
 * - [AUDIO]: the Storage Access Framework, showing audio only. The Photo
 *   Picker doesn't offer audio.
 */
public enum class MediaSource { GALLERY, FILES, AUDIO }

/** Where imported media goes. */
@Immutable
public sealed interface ImportTarget {
    public data object NewProject : ImportTarget
    public data class ExistingProject(val projectId: ProjectId) : ImportTarget
}

/** A picked file as it moves through probing. Keyed by its URI. */
@Immutable
public sealed interface ImportItem {
    public val uri: String

    public data class Probing(override val uri: String) : ImportItem
    public data class Ready(val media: MediaRef) : ImportItem {
        override val uri: String get() = media.uri
    }
    public data class Failed(override val uri: String, val reason: ImportFailure) : ImportItem
}

public enum class ImportFailure {
    UNSUPPORTED_TYPE,
    NOT_ACCESSIBLE,
    UNREADABLE,

    /** The file sits in a cache or temp folder the system may clear, so a project can't depend on it. */
    TEMPORARY_LOCATION,
}

/** Set once importing succeeds. The screen then navigates. */
@Immutable
public sealed interface ImportOutcome {
    public data class Created(val projectId: ProjectId) : ImportOutcome
    public data class Added(val projectId: ProjectId, val count: Int) : ImportOutcome
}

@Immutable
public data class MediaImportUiState(
    val target: ImportTarget,
    /** Name for a new project. Ignored when adding to an existing one. */
    val name: String,
    val items: List<ImportItem> = emptyList(),
    /** URIs the user unticked. Everything else that is selectable counts as selected. */
    val deselected: Set<String> = emptySet(),
    /** URIs already in the target project. Shown, but can't be selected again. */
    val existingUris: Set<String> = emptySet(),
    /** URIs whose provider refused persistent access. They work now, but may not after a restart. */
    val temporaryAccess: Set<String> = emptySet(),
    val initialPickerLaunched: Boolean = false,
    val isImporting: Boolean = false,
    val importFailed: Boolean = false,
    /** The existing target project was deleted while this screen was open. */
    val targetMissing: Boolean = false,
    val outcome: ImportOutcome? = null,
    /** Set once the import is discarded. The screen then closes. */
    val closed: Boolean = false,
) {
    public fun isAlreadyInProject(item: ImportItem): Boolean = item.uri in existingUris

    public fun isSelectable(item: ImportItem): Boolean = item is ImportItem.Ready && !isAlreadyInProject(item)

    public fun isSelected(item: ImportItem): Boolean = isSelectable(item) && item.uri !in deselected

    val selectableCount: Int get() = items.count { isSelectable(it) }

    /** Selected media, each marked with whether its access was persisted. */
    val selectedMedia: List<MediaRef>
        get() = items.filter { isSelected(it) }.map { item ->
            val media = (item as ImportItem.Ready).media
            media.copy(accessPersisted = media.uri !in temporaryAccess)
        }

    val isProbing: Boolean get() = items.any { it is ImportItem.Probing }

    val nameValid: Boolean get() = target !is ImportTarget.NewProject || ProjectName.normalize(name) != null

    val canImport: Boolean
        get() = selectedMedia.isNotEmpty() && nameValid && !isProbing && !isImporting && !targetMissing
}

/** Platform operations the import needs, abstracted so the ViewModel is unit-testable. */
public interface ImportMediaGateway : MediaAccessReleaser {
    /** Persists read access if the provider allows it. Returns false if it doesn't. */
    public suspend fun persistAccess(uri: String): Boolean

    /** Reads metadata. Returns the media, or why it can't be used. */
    public suspend fun probe(uri: String): Result

    public sealed interface Result {
        public data class Ok(val media: MediaRef) : Result
        public data class Err(val failure: ImportFailure) : Result
    }
}

/**
 * State of a media import: picked files, their metadata, which ones are
 * selected, and (for a new project) its name.
 *
 * - Files are referenced in place, never copied. Read access is persisted as
 *   soon as a file is picked.
 * - Picked URIs, deselections and the name are kept in [SavedStateHandle].
 *   After process death the selection is restored and probed again.
 * - When the import finishes or is discarded, grants that no saved project
 *   uses are released. That covers unticked files, failed files and the
 *   whole selection on discard.
 */
public class MediaImportViewModel(
    private val savedState: SavedStateHandle,
    private val target: ImportTarget,
    private val repository: ProjectRepository,
    private val media: ImportMediaGateway,
    defaultName: String,
) : ViewModel() {

    private val probePermits = Semaphore(MAX_CONCURRENT_PROBES)

    private val _uiState = MutableStateFlow(
        MediaImportUiState(
            target = target,
            name = savedState[KEY_NAME] ?: defaultName,
            deselected = savedState.get<ArrayList<String>>(KEY_DESELECTED).orEmpty().toSet(),
            initialPickerLaunched = savedState[KEY_PICKER_LAUNCHED] ?: false,
        ),
    )
    public val uiState: StateFlow<MediaImportUiState> = _uiState.asStateFlow()

    init {
        if (target is ImportTarget.ExistingProject) {
            viewModelScope.launch {
                val project = repository.project(target.projectId).first()
                _uiState.update {
                    if (project == null) {
                        it.copy(targetMissing = true)
                    } else {
                        it.copy(existingUris = project.media.mapTo(mutableSetOf()) { m -> m.uri })
                    }
                }
            }
        }
        val restored = savedState.get<ArrayList<String>>(KEY_URIS).orEmpty()
        if (restored.isNotEmpty()) startImporting(restored)
    }

    public fun onInitialPickerLaunched() {
        savedState[KEY_PICKER_LAUNCHED] = true
        _uiState.update { it.copy(initialPickerLaunched = true) }
    }

    public fun setName(name: String) {
        savedState[KEY_NAME] = name
        _uiState.update { it.copy(name = name, importFailed = false) }
    }

    /**
     * Handles a picker result. An empty list means the user cancelled. If
     * nothing was picked before, the import closes. Files already in the
     * list are ignored.
     */
    public fun onPicked(uris: List<String>) {
        if (uris.isEmpty()) {
            if (_uiState.value.items.isEmpty()) discard()
            return
        }
        val existing = _uiState.value.items.mapTo(mutableSetOf()) { it.uri }
        val added = uris.distinct().filterNot { it in existing }
        if (added.isNotEmpty()) startImporting(added)
    }

    public fun toggle(uri: String) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.uri == uri } ?: return
        if (!state.isSelectable(item)) return
        updateDeselected(if (uri in state.deselected) state.deselected - uri else state.deselected + uri)
    }

    public fun selectAll() {
        updateDeselected(emptySet())
    }

    public fun clearSelection() {
        updateDeselected(_uiState.value.items.filter { _uiState.value.isSelectable(it) }.mapTo(mutableSetOf()) { it.uri })
    }

    public fun importSelected() {
        val state = _uiState.value
        if (!state.canImport) return
        _uiState.update { it.copy(isImporting = true, importFailed = false) }
        viewModelScope.launch {
            try {
                val outcome = when (target) {
                    ImportTarget.NewProject -> ImportOutcome.Created(repository.create(state.name, state.selectedMedia).id)
                    is ImportTarget.ExistingProject -> {
                        val added = repository.addMedia(target.projectId, state.selectedMedia)
                        if (added == null) {
                            _uiState.update { it.copy(isImporting = false, targetMissing = true) }
                            return@launch
                        }
                        ImportOutcome.Added(target.projectId, added)
                    }
                }
                // Drop grants for files that were unticked, failed, or already in the project.
                releaseUnused()
                _uiState.update { it.copy(isImporting = false, outcome = outcome) }
            } catch (e: IOException) {
                _uiState.update { it.copy(isImporting = false, importFailed = true) }
            }
        }
    }

    /** Abandons the import, releasing media access no saved project needs. */
    public fun discard() {
        viewModelScope.launch {
            releaseUnused()
            _uiState.update { it.copy(closed = true) }
        }
    }

    private fun startImporting(uris: List<String>) {
        _uiState.update { state -> state.copy(items = state.items + uris.map { ImportItem.Probing(it) }) }
        savedState[KEY_URIS] = ArrayList(_uiState.value.items.map { it.uri })
        uris.forEach { uri ->
            viewModelScope.launch {
                if (!media.persistAccess(uri)) {
                    _uiState.update { it.copy(temporaryAccess = it.temporaryAccess + uri) }
                }
                val item = when (val result = probePermits.withPermit { media.probe(uri) }) {
                    is ImportMediaGateway.Result.Ok -> ImportItem.Ready(result.media)
                    is ImportMediaGateway.Result.Err -> ImportItem.Failed(uri, result.failure)
                }
                _uiState.update { state ->
                    state.copy(items = state.items.map { if (it.uri == uri && it is ImportItem.Probing) item else it })
                }
            }
        }
    }

    private fun updateDeselected(deselected: Set<String>) {
        savedState[KEY_DESELECTED] = ArrayList(deselected)
        _uiState.update { it.copy(deselected = deselected) }
    }

    private suspend fun releaseUnused() {
        media.releaseAllExcept(repository.referencedMediaUris())
    }

    private companion object {
        const val KEY_URIS = "uris"
        const val KEY_NAME = "name"
        const val KEY_DESELECTED = "deselected"
        const val KEY_PICKER_LAUNCHED = "pickerLaunched"
        const val MAX_CONCURRENT_PROBES = 3
    }
}
