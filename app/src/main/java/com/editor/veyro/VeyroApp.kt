package com.editor.veyro

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.feature.editor.EditorRoute
import com.editor.veyro.feature.editor.EditorViewModel
import com.editor.veyro.feature.project.preview.MediaPreviewRoute
import com.editor.veyro.feature.project.preview.MediaPreviewViewModel
import com.editor.veyro.media.playback.ExoPlaybackEngine
import com.editor.veyro.media.playback.PreviewController
import com.editor.veyro.feature.library.HomeRoute
import com.editor.veyro.feature.library.LibraryViewModel
import com.editor.veyro.feature.library.ProjectsRoute
import com.editor.veyro.feature.project.importmedia.AndroidImportMediaGateway
import com.editor.veyro.feature.project.importmedia.ImportTarget
import com.editor.veyro.feature.project.importmedia.MediaImportRoute
import com.editor.veyro.feature.project.importmedia.MediaImportViewModel
import com.editor.veyro.feature.project.importmedia.MediaSource
import com.editor.veyro.feature.project.importmedia.defaultProjectName
import com.editor.veyro.feature.project.overview.ProjectRoute
import com.editor.veyro.feature.project.overview.ProjectViewModel
import com.editor.veyro.feature.settings.SettingsRoute
import com.editor.veyro.feature.settings.SettingsViewModel
import com.editor.veyro.navigation.HomeKey
import com.editor.veyro.navigation.EditorKey
import com.editor.veyro.navigation.ImportKey
import com.editor.veyro.navigation.PreviewKey
import com.editor.veyro.navigation.ProjectKey
import com.editor.veyro.navigation.ProjectsKey
import com.editor.veyro.navigation.SettingsKey
import com.editor.veyro.navigation.TopLevelDestination

/**
 * The app shell. A single Navigation 3 back stack always starts with Home.
 * - Top-level tabs show a bottom bar on compact windows and a rail on wider
 *   ones.
 * - Detail screens (new project, project) hide navigation for full focus.
 */
@Composable
fun VeyroApp(container: AppContainer) {
    val backStack = rememberNavBackStack(HomeKey)
    val current = backStack.lastOrNull()
    val currentTab = TopLevelDestination.entries.firstOrNull { it.key == current }

    val layoutType = if (currentTab != null) {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())
    } else {
        NavigationSuiteType.None
    }
    // The navigation bar or rail already pads for the system bars on its side.
    val tabInsets = when (layoutType) {
        NavigationSuiteType.NavigationBar ->
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        NavigationSuiteType.NavigationRail ->
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom)
        else -> WindowInsets.safeDrawing
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { tab ->
                val selected = tab == currentTab
                item(
                    selected = selected,
                    onClick = { backStack.selectTab(tab) },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
    ) {
        val context = LocalContext.current
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeRoute(
                        viewModel = viewModel { LibraryViewModel(container.projects, container.projectFiles::readText) },
                        contentWindowInsets = tabInsets,
                        onNewFromGallery = { backStack.add(ImportKey(MediaSource.GALLERY)) },
                        onNewFromFiles = { backStack.add(ImportKey(MediaSource.FILES)) },
                        onNewFromAudio = { backStack.add(ImportKey(MediaSource.AUDIO)) },
                        onOpenProject = { backStack.add(ProjectKey(it.value)) },
                        onSeeAllProjects = { backStack.selectTab(TopLevelDestination.PROJECTS) },
                    )
                }
                entry<ProjectsKey> {
                    ProjectsRoute(
                        viewModel = viewModel { LibraryViewModel(container.projects, container.projectFiles::readText) },
                        contentWindowInsets = tabInsets,
                        onNewProject = { backStack.add(ImportKey(MediaSource.GALLERY)) },
                        onOpenProject = { backStack.add(ProjectKey(it.value)) },
                    )
                }
                entry<SettingsKey> {
                    SettingsRoute(
                        viewModel = viewModel {
                            SettingsViewModel(
                                repository = container.projects,
                                persistedMediaCount = container.mediaAccess::persistedCount,
                                versionName = container.versionName,
                            )
                        },
                        contentWindowInsets = tabInsets,
                    )
                }
                entry<ImportKey> { key ->
                    MediaImportRoute(
                        viewModel = viewModel {
                            MediaImportViewModel(
                                savedState = createSavedStateHandle(),
                                target = key.projectId?.let { ImportTarget.ExistingProject(ProjectId(it)) }
                                    ?: ImportTarget.NewProject,
                                repository = container.projects,
                                media = AndroidImportMediaGateway(container.mediaProber, container.mediaAccess, container.sourcePolicy),
                                defaultName = defaultProjectName(context),
                            )
                        },
                        initialSource = key.source,
                        onCreated = { id ->
                            // Replace the import with the saved project, so Back returns to where the user started.
                            backStack.removeLastOrNull()
                            backStack.add(ProjectKey(id.value))
                        },
                        // Adding to a project returns to it. That project's screen is the entry below this one.
                        onAdded = { backStack.removeLastOrNull() },
                        onClose = { backStack.removeLastOrNull() },
                    )
                }
                entry<ProjectKey> { key ->
                    ProjectRoute(
                        viewModel = viewModel {
                            ProjectViewModel(
                                projectId = ProjectId(key.projectId),
                                repository = container.projects,
                                checkMedia = container.mediaAccess::health,
                                writeProjectFile = container.projectFiles::writeText,
                            )
                        },
                        onBack = { backStack.removeLastOrNull() },
                        onAddMedia = { source -> backStack.add(ImportKey(source, key.projectId)) },
                        onOpenMedia = { mediaId -> backStack.add(PreviewKey(key.projectId, mediaId.value)) },
                        onOpenEditor = { backStack.add(EditorKey(key.projectId)) },
                    )
                }
                entry<EditorKey> { key ->
                    // The ViewModel outlives the Activity across rotation, so its factory must not capture an Activity context.
                    val appContext = context.applicationContext
                    EditorRoute(
                        viewModel = viewModel {
                            EditorViewModel(
                                savedState = createSavedStateHandle(),
                                projectId = ProjectId(key.projectId),
                                repository = container.projects,
                                createController = { scope ->
                                    PreviewController(engineFactory = { ExoPlaybackEngine(appContext) }, scope = scope)
                                },
                                autosave = container.autosave,
                            )
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<PreviewKey> { key ->
                    // The ViewModel outlives the Activity across rotation, so its factory must not capture an Activity context.
                    val appContext = context.applicationContext
                    MediaPreviewRoute(
                        viewModel = viewModel {
                            MediaPreviewViewModel(
                                savedState = createSavedStateHandle(),
                                projectId = ProjectId(key.projectId),
                                initialMediaId = MediaId(key.mediaId),
                                repository = container.projects,
                                createController = { scope ->
                                    PreviewController(engineFactory = { ExoPlaybackEngine(appContext) }, scope = scope)
                                },
                            )
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            },
        )
    }
}

/** Shows [tab], keeping Home at the root so Back from any tab returns Home. */
private fun NavBackStack<NavKey>.selectTab(tab: TopLevelDestination) {
    if (lastOrNull() == tab.key) return
    while (size > 1) removeAt(lastIndex)
    if (firstOrNull() != HomeKey) {
        clear()
        add(HomeKey)
    }
    if (tab.key != HomeKey) add(tab.key)
}
