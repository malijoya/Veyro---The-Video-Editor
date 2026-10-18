package com.editor.veyro.feature.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.editor.veyro.core.designsystem.component.ConfirmDialog
import com.editor.veyro.core.designsystem.component.EmptyState
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.timeline.export.ExportBlocker
import com.editor.veyro.core.timeline.export.ExportWarning
import com.editor.veyro.media.export.ExportDestination
import com.editor.veyro.media.export.ExportError
import com.editor.veyro.media.export.ExportedVideo
import java.util.Locale

@Composable
public fun ExportRoute(viewModel: ExportViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val running = (state as? ExportUiState.Ready)?.phase is ExportPhase.Running
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MP4_MIME)) { uri ->
        uri?.let { viewModel.start(ExportDestination.Document(it.toString())) }
    }

    // Leaving would cancel the export, so ask first.
    BackHandler(enabled = running) { confirmCancel = true }
    KeepScreenOn(running)

    ExportScreen(
        state = state,
        onBack = { if (running) confirmCancel = true else onBack() },
        onSelectResolution = viewModel::selectResolution,
        onSelectFrameRate = viewModel::selectFrameRate,
        onSaveToGallery = { viewModel.start(ExportDestination.Gallery) },
        onSaveToFile = { pickFile.launch(viewModel.suggestedFileName()) },
        onCancel = { confirmCancel = true },
        onBackToSetup = viewModel::backToSetup,
        onDone = onDone,
    )

    if (confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.export_cancel_title),
            message = stringResource(R.string.export_cancel_message),
            confirmLabel = stringResource(R.string.export_cancel_confirm),
            dismissLabel = stringResource(R.string.export_keep_going),
            onConfirm = {
                confirmCancel = false
                viewModel.cancel()
            },
            onDismiss = { confirmCancel = false },
            destructive = true,
        )
    }
}

/** Keeps the display on while exporting. The export stops if the screen is left, and an off screen can lead to that. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportScreen(
    state: ExportUiState,
    onBack: () -> Unit,
    onSelectResolution: (com.editor.veyro.core.timeline.export.ExportResolution) -> Unit,
    onSelectFrameRate: (FrameRate) -> Unit,
    onSaveToGallery: () -> Unit,
    onSaveToFile: () -> Unit,
    onCancel: () -> Unit,
    onBackToSetup: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.export_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val modifier = Modifier
            .padding(padding)
            .fillMaxSize()
        when (state) {
            ExportUiState.Loading -> Centered(modifier) { CircularProgressIndicator() }
            ExportUiState.NotFound -> EmptyState(
                icon = Icons.Filled.Warning,
                title = stringResource(R.string.export_not_found),
                message = stringResource(R.string.export_not_found_message),
                modifier = modifier,
            )
            is ExportUiState.Ready -> Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                modifier = modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    .widthIn(max = 640.dp),
            ) {
                when (val phase = state.phase) {
                    is ExportPhase.Setup -> SetupContent(state, phase.cancelled, onSelectResolution, onSelectFrameRate, onSaveToGallery, onSaveToFile)
                    is ExportPhase.Running -> RunningContent(phase, onCancel)
                    is ExportPhase.Done -> DoneContent(phase.video, onBackToSetup, onDone)
                    is ExportPhase.Failed -> FailedContent(phase, onBackToSetup, onBack)
                }
            }
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

// ---------------- Setup ----------------

@Composable
private fun SetupContent(
    state: ExportUiState.Ready,
    cancelled: Boolean,
    onSelectResolution: (com.editor.veyro.core.timeline.export.ExportResolution) -> Unit,
    onSelectFrameRate: (FrameRate) -> Unit,
    onSaveToGallery: () -> Unit,
    onSaveToFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(state.projectName, style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.export_summary, DurationFormat.clock(state.durationUs)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (cancelled) Notice(stringResource(R.string.export_cancelled), isError = false)

    Label(stringResource(R.string.export_resolution))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        state.resolutions.forEachIndexed { index, resolution ->
            val size = "${resolution.widthPx}×${resolution.heightPx}"
            FilterChip(
                selected = resolution == state.selectedResolution,
                onClick = { onSelectResolution(resolution) },
                label = { Text(if (index == 0) stringResource(R.string.export_resolution_canvas, size) else size) },
            )
        }
    }

    Label(stringResource(R.string.export_frame_rate))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        state.frameRates.forEach { rate ->
            FilterChip(
                selected = rate == state.selectedFrameRate,
                onClick = { onSelectFrameRate(rate) },
                label = { Text(formatFps(rate)) },
            )
        }
    }
    Hint(stringResource(R.string.export_frame_rate_hint))
    Hint(stringResource(R.string.export_format_hint))

    if (state.checkingMedia) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Hint(stringResource(R.string.export_checking_media))
        }
    }
    state.blockers.forEach { blocker ->
        Notice(blockerText(blocker, state.problemFiles), isError = true)
    }
    state.warnings.forEach { warning ->
        Notice(warningText(warning), isError = false)
    }

    Spacer(Modifier.height(Spacing.xs))
    if (state.galleryAvailable) {
        Button(onClick = onSaveToGallery, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_save_gallery))
        }
    }
    OutlinedButton(onClick = onSaveToFile, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.export_save_file))
    }
}

// ---------------- Running ----------------

@Composable
private fun RunningContent(phase: ExportPhase.Running, onCancel: () -> Unit) {
    Spacer(Modifier.height(Spacing.xl))
    Text(
        stringResource(
            when (phase.stage) {
                ExportStage.PREPARING -> R.string.export_stage_preparing
                ExportStage.ANALYSING_AUDIO -> R.string.export_stage_analysing
                ExportStage.RENDERING -> R.string.export_stage_rendering
                ExportStage.VERIFYING -> R.string.export_stage_verifying
                ExportStage.SAVING -> R.string.export_stage_saving
            },
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    val percent = phase.percent
    if (percent != null) {
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.export_percent, percent), style = MaterialTheme.typography.bodyMedium)
    } else {
        // The renderer hasn't given a figure, so none is shown.
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Hint(stringResource(R.string.export_keep_open))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.export_cancel))
    }
}

// ---------------- Done ----------------

@Composable
private fun DoneContent(video: ExportedVideo, onExportAgain: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.export_done_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(video.displayName, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Hint(
            when (video.destination) {
                ExportDestination.Gallery -> stringResource(R.string.export_saved_gallery)
                is ExportDestination.Document -> stringResource(R.string.export_saved_file)
            },
        )
    }
    val info = video.video
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Detail(stringResource(R.string.export_detail_resolution), "${info.widthPx}×${info.heightPx}")
        Detail(stringResource(R.string.export_detail_duration), DurationFormat.clock(info.durationUs))
        Detail(stringResource(R.string.export_detail_size), Formatter.formatShortFileSize(context, info.sizeBytes))
        Detail(stringResource(R.string.export_detail_codecs), listOfNotNull(codecName(info.videoMime), info.audioMime?.let(::codecName)).joinToString(" · "))
    }
    Hint(stringResource(R.string.export_verified))
    if (openFailed) Notice(stringResource(R.string.export_no_player), isError = true)

    Button(onClick = { openFailed = !play(context, video.uri) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Text(stringResource(R.string.export_play), modifier = Modifier.padding(start = Spacing.sm))
    }
    OutlinedButton(onClick = { share(context, video.uri) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Text(stringResource(R.string.export_share), modifier = Modifier.padding(start = Spacing.sm))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onExportAgain, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_again)) }
        TextButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_done)) }
    }
}

private fun play(context: Context, uri: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), MP4_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    true
} catch (e: ActivityNotFoundException) {
    false
}

private fun share(context: Context, uri: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(MP4_MIME)
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

// ---------------- Failed ----------------

@Composable
private fun FailedContent(phase: ExportPhase.Failed, onTryAgain: () -> Unit, onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.export_failed_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(errorText(phase.error), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Hint(stringResource(R.string.export_nothing_saved))
    }
    // The technical reason, for bug reports.
    Text(phase.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = onTryAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_try_again)) }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_back)) }
}

// ---------------- Pieces ----------------

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        Icon(
            if (isError) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun blockerText(blocker: ExportBlocker, files: List<String>): String = when (blocker) {
    ExportBlocker.NO_VIDEO -> stringResource(R.string.export_blocker_no_video)
    ExportBlocker.MEDIA_UNAVAILABLE -> stringResource(R.string.export_blocker_media, files.joinToString())
    ExportBlocker.SOURCE_RANGE_INVALID -> stringResource(R.string.export_blocker_range, files.joinToString())
}

@Composable
private fun warningText(warning: ExportWarning): String = stringResource(
    when (warning) {
        ExportWarning.EXTRA_TRACKS_IGNORED -> R.string.export_warning_tracks
        ExportWarning.EFFECTS_NOT_RENDERED -> R.string.export_warning_effects
        ExportWarning.AUDIO_TRIMMED_TO_VIDEO -> R.string.export_warning_audio_trimmed
        ExportWarning.MIX_LOWERED_TO_PREVENT_CLIPPING -> R.string.export_warning_mix_lowered
        ExportWarning.AUDIO_NOT_ANALYSED -> R.string.export_warning_not_analysed
        ExportWarning.OVERLAYS_TRIMMED_TO_VIDEO -> R.string.export_warning_overlays_trimmed
        ExportWarning.TRANSITIONS_SHORTENED -> R.string.export_warning_transitions_shortened
    },
)

@Composable
private fun errorText(error: ExportError): String = stringResource(
    when (error) {
        ExportError.SOURCE_UNREADABLE -> R.string.export_error_source
        ExportError.DECODER_UNSUPPORTED -> R.string.export_error_decoder
        ExportError.ENCODER_UNSUPPORTED -> R.string.export_error_encoder
        ExportError.PROCESSING_FAILED -> R.string.export_error_processing
        ExportError.OUTPUT_WRITE_FAILED -> R.string.export_error_write
        ExportError.INVALID_OUTPUT -> R.string.export_error_invalid
        ExportError.STORAGE_FULL -> R.string.export_error_storage
        ExportError.SAVE_FAILED -> R.string.export_error_save
        ExportError.UNKNOWN -> R.string.export_error_unknown
    },
)

private fun formatFps(rate: FrameRate): String =
    if (rate.denominator == 1L) "${rate.numerator} fps" else String.format(Locale.US, "%.3f", rate.fps).trimEnd('0').trimEnd('.') + " fps"

private fun codecName(mime: String): String = when (mime) {
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/mp4v-es" -> "MPEG-4"
    "video/3gpp" -> "H.263"
    "audio/mp4a-latm" -> "AAC"
    "audio/3gpp" -> "AMR-NB"
    "audio/amr-wb" -> "AMR-WB"
    else -> mime
}

private const val MP4_MIME = "video/mp4"
