package com.editor.veyro.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.editor.veyro.core.designsystem.component.AvailabilityBadge
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing

@Composable
public fun SettingsRoute(viewModel: SettingsViewModel, contentWindowInsets: WindowInsets) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Media access can be revoked in system settings while Veyro is in the background.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    SettingsScreen(state, contentWindowInsets)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: SettingsUiState, contentWindowInsets: WindowInsets) {
    val context = LocalContext.current
    val loading = stringResource(R.string.settings_loading)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        contentWindowInsets = contentWindowInsets,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Layout.screenGutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
            ) {
                item {
                    SettingsSection(stringResource(R.string.settings_section_storage)) {
                        SettingsRow(
                            icon = VeyroIcons.Storage,
                            title = stringResource(R.string.settings_projects),
                            supporting = if (state.projectCount != null && state.projectStorageBytes != null) {
                                stringResource(
                                    R.string.settings_projects_summary,
                                    pluralStringResource(R.plurals.settings_project_count, state.projectCount, state.projectCount),
                                    Formatter.formatShortFileSize(context, state.projectStorageBytes),
                                )
                            } else {
                                loading
                            },
                        )
                        SettingsRow(
                            icon = VeyroIcons.PhotoLibrary,
                            title = stringResource(R.string.settings_media_access),
                            supporting = state.persistedMediaCount?.let {
                                pluralStringResource(R.plurals.settings_media_access_summary, it, it)
                            } ?: loading,
                        )
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.settings_section_permissions)) {
                        SettingsRow(
                            icon = Icons.Outlined.Settings,
                            title = stringResource(R.string.settings_app_permissions),
                            supporting = stringResource(R.string.settings_app_permissions_summary),
                            onClick = { openAppDetails(context) },
                        )
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.settings_section_export)) {
                        SettingsRow(
                            icon = VeyroIcons.HighQuality,
                            title = stringResource(R.string.settings_export_quality),
                            supporting = stringResource(R.string.settings_export_quality_summary),
                            badge = stringResource(R.string.settings_coming_soon),
                        )
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.settings_section_about)) {
                        SettingsRow(
                            icon = Icons.Outlined.Lock,
                            title = stringResource(R.string.settings_privacy),
                            supporting = stringResource(R.string.settings_privacy_summary),
                        )
                        SettingsRow(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.settings_version),
                            supporting = state.versionName,
                        )
                    }
                }
            }
        }
    }
}

private fun openAppDetails(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Some locked-down devices remove this screen. Fall back to the main settings screen.
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = Spacing.xs, bottom = Spacing.sm)
                .semantics { heading() },
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column { content() }
        }
    }
}

/** A settings line. Rows without [onClick] are informational and not focusable as buttons. */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: (() -> Unit)? = null,
    badge: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (badge != null) AvailabilityBadge(badge)
            }
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
