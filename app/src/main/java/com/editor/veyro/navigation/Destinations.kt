package com.editor.veyro.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.editor.veyro.R
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.feature.project.importmedia.MediaSource
import kotlinx.serialization.Serializable

/*
 * Navigation keys. They are @Serializable so Navigation 3 can save the back
 * stack across process death. Only IDs and small values go here, never
 * whole models.
 */

@Serializable
data object HomeKey : NavKey

@Serializable
data object ProjectsKey : NavKey

@Serializable
data object SettingsKey : NavKey

/** Media import. Without [projectId] it creates a new project, with it it adds to that project. */
@Serializable
data class ImportKey(val source: MediaSource, val projectId: String? = null) : NavKey

@Serializable
data class ProjectKey(val projectId: String) : NavKey

/** The tabs in the navigation bar or rail. */
enum class TopLevelDestination(
    val key: NavKey,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(HomeKey, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    PROJECTS(ProjectsKey, R.string.nav_projects, VeyroIcons.VideoLibraryFilled, VeyroIcons.VideoLibrary),
    SETTINGS(SettingsKey, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}
