package com.editor.veyro.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.data.project.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
public data class SettingsUiState(
    val projectCount: Int? = null,
    val projectStorageBytes: Long? = null,
    val persistedMediaCount: Int? = null,
    val versionName: String,
)

/**
 * Facts about the app's data on this device. Every value shown comes from
 * the real store. Nothing here is a placeholder.
 */
public class SettingsViewModel(
    repository: ProjectRepository,
    private val persistedMediaCount: () -> Int,
    versionName: String,
) : ViewModel() {

    private val mediaCount = MutableStateFlow<Int?>(null)

    public val uiState: StateFlow<SettingsUiState> = combine(
        repository.summaries
            .map<_, Pair<Int?, Long?>> { it.size to repository.storageBytes() }
            .catch { emit(null to null) },
        mediaCount,
    ) { (count, bytes), media ->
        SettingsUiState(
            projectCount = count,
            projectStorageBytes = bytes,
            persistedMediaCount = media,
            versionName = versionName,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(versionName = versionName))

    /** Re-reads values that can change outside the app, such as revoked media access. Call when shown. */
    public fun refresh() {
        mediaCount.value = persistedMediaCount()
    }
}
