package com.editor.veyro.feature.export

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.audio.WaveformSource
import com.editor.veyro.core.model.project.MediaHealth
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.timeline.export.ExportBlocker
import com.editor.veyro.core.timeline.export.ExportOptions
import com.editor.veyro.core.timeline.export.ExportPlanResult
import com.editor.veyro.core.timeline.export.ExportPlanner
import com.editor.veyro.core.timeline.export.ExportResolution
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.core.timeline.export.ExportWarning
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.media.export.ExportDestination
import com.editor.veyro.media.export.ExportError
import com.editor.veyro.media.export.ExportPipeline
import com.editor.veyro.media.export.ExportStatus
import com.editor.veyro.media.export.ExportedVideo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which step of a running export is in progress. */
public enum class ExportStage { PREPARING, ANALYSING_AUDIO, RENDERING, VERIFYING, SAVING }

/** Where the export is: choosing options, running, finished, or failed. */
@Immutable
public sealed interface ExportPhase {
    /** Choosing options. [cancelled] is true right after the user cancelled an export. */
    public data class Setup(val cancelled: Boolean = false) : ExportPhase

    /** [percent] is Transformer's own figure while rendering, and null whenever it isn't known. */
    public data class Running(val stage: ExportStage, val percent: Int?) : ExportPhase

    public data class Done(val video: ExportedVideo) : ExportPhase
    public data class Failed(val error: ExportError, val detail: String) : ExportPhase
}

@Immutable
public sealed interface ExportUiState {
    public data object Loading : ExportUiState
    public data object NotFound : ExportUiState

    public data class Ready(
        val projectName: String,
        val durationUs: Long,
        val resolutions: List<ExportResolution>,
        val selectedResolution: ExportResolution,
        val frameRates: List<FrameRate>,
        val selectedFrameRate: FrameRate,
        /** Saving to the gallery needs Android 10. Older devices only offer the file picker. */
        val galleryAvailable: Boolean,
        val warnings: Set<ExportWarning>,
        val blockers: Set<ExportBlocker>,
        /** Files behind the blockers, for the message. */
        val problemFiles: List<String>,
        /** Source files are being checked. Export waits for it. */
        val checkingMedia: Boolean,
        val phase: ExportPhase,
    ) : ExportUiState {
        val canStart: Boolean get() = !checkingMedia && blockers.isEmpty() && phase !is ExportPhase.Running
    }
}

/**
 * The export screen: choose size, frame rate and destination, run the
 * export, then show the result.
 *
 * - [ExportPlanner] builds the plan from the same timeline the editor
 *   shows. Warnings and blockers appear before the user starts.
 * - [pipeline] renders, verifies and saves. Only its verified
 *   [ExportStatus.Completed] leads to [ExportPhase.Done].
 * - Progress is shown only as the renderer reports it.
 * - The export runs in this ViewModel's scope. Leaving the screen cancels
 *   it, which stops the encoder and deletes the partial file. Background
 *   export (a foreground service) isn't built yet.
 */
public class ExportViewModel(
    private val savedState: SavedStateHandle,
    private val projectId: ProjectId,
    repository: ProjectRepository,
    private val checkMedia: suspend (MediaRef) -> MediaHealth,
    private val pipeline: ExportPipeline,
    private val galleryAvailable: Boolean,
    /** Writes edits still waiting for autosave, so the export reads the timeline the user last saw. */
    private val flushPendingEdits: suspend () -> Unit = {},
    /** Peaks for the mix headroom. Every audio file is analysed before an export starts. */
    private val waveforms: WaveformSource = WaveformSource.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val project = repository.project(projectId)
    private val unavailable = MutableStateFlow<Set<MediaId>?>(null)
    private val phase = MutableStateFlow<ExportPhase>(ExportPhase.Setup())
    private val resolutionChoice = savedState.getStateFlow<String?>(KEY_RESOLUTION, null)
    private val frameRateChoice = savedState.getStateFlow<String?>(KEY_FRAME_RATE, null)
    private var latestProject: Project? = null
    private var healthChecked = false
    private var job: Job? = null

    private val choices = combine(resolutionChoice, frameRateChoice) { r, f -> r to f }

    public val uiState: StateFlow<ExportUiState> = combine(project, unavailable, choices, phase) { project, missing, (resolutionKey, rateKey), phase ->
        latestProject = project
        if (project == null) return@combine ExportUiState.NotFound
        if (!healthChecked) {
            healthChecked = true
            viewModelScope.launch { unavailable.value = unavailableMedia(project) }
        }
        val settings = project.settings
        val resolutions = ExportOptions.resolutionsFor(settings.canvasWidthPx, settings.canvasHeightPx)
        val rates = ExportOptions.frameRatesFor(settings.frameRate)
        val resolution = resolutions.firstOrNull { it.key == resolutionKey } ?: resolutions.first()
        val rate = rates.firstOrNull { it.key == rateKey } ?: rates.first()
        val timeline = DefaultArrangement.timelineFor(project)
        val mediaById = project.media.associateBy { it.id }
        val planned = ExportPlanner.plan(
            timeline, project.media, ExportSettings(resolution.widthPx, resolution.heightPx, rate), missing.orEmpty(),
            waveforms = { id -> mediaById[id]?.let(waveforms::cached) },
        )
        ExportUiState.Ready(
            projectName = project.name,
            durationUs = timeline.mainTrack?.endUs ?: 0,
            resolutions = resolutions,
            selectedResolution = resolution,
            frameRates = rates,
            selectedFrameRate = rate,
            galleryAvailable = galleryAvailable,
            // Audio is measured when the export starts, so "not analysed" isn't a warning yet.
            warnings = (planned as? ExportPlanResult.Ready)?.plan?.warnings.orEmpty() - ExportWarning.AUDIO_NOT_ANALYSED,
            blockers = (planned as? ExportPlanResult.Blocked)?.blockers.orEmpty(),
            problemFiles = (planned as? ExportPlanResult.Blocked)?.unavailableMedia.orEmpty(),
            checkingMedia = missing == null,
            phase = phase,
        )
    }
        .catch { emit(ExportUiState.NotFound) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState.Loading)

    public fun selectResolution(resolution: ExportResolution) {
        if (phase.value is ExportPhase.Running) return
        savedState[KEY_RESOLUTION] = resolution.key
    }

    public fun selectFrameRate(rate: FrameRate) {
        if (phase.value is ExportPhase.Running) return
        savedState[KEY_FRAME_RATE] = rate.key
    }

    /** The name suggested to the file picker, such as `Trip_20261015_143000.mp4`. */
    public fun suggestedFileName(): String {
        val base = (latestProject?.name ?: "Veyro")
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "_")
            .trim()
            .take(MAX_NAME_LENGTH)
            .ifEmpty { "Veyro" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(clock()))
        return "${base}_$stamp.mp4"
    }

    /**
     * Starts an export to [destination]. Source files are checked again
     * first, since they may have gone since the screen opened.
     */
    public fun start(destination: ExportDestination) {
        val state = uiState.value as? ExportUiState.Ready ?: return
        if (!state.canStart) return
        val fileName = suggestedFileName()
        phase.value = ExportPhase.Running(ExportStage.PREPARING, null)
        job = viewModelScope.launch {
            flushPendingEdits()
            val project = this@ExportViewModel.project.first() ?: run {
                phase.value = ExportPhase.Setup()
                return@launch
            }
            val missing = unavailableMedia(project)
            unavailable.value = missing
            // Measure every sound first, so the mix is turned down only where overlapping peaks would clip.
            phase.value = ExportPhase.Running(ExportStage.ANALYSING_AUDIO, null)
            val peaks = HashMap<MediaId, Waveform>()
            project.media.filter { it.hasAudio && it.id !in missing }.forEach { media ->
                waveforms.load(media)?.let { peaks[media.id] = it }
            }
            val settings = ExportSettings(state.selectedResolution.widthPx, state.selectedResolution.heightPx, state.selectedFrameRate)
            val plan = when (val planned = ExportPlanner.plan(DefaultArrangement.timelineFor(project), project.media, settings, missing, peaks::get)) {
                is ExportPlanResult.Blocked -> {
                    // The blockers now show on the setup screen.
                    phase.value = ExportPhase.Setup()
                    return@launch
                }
                is ExportPlanResult.Ready -> planned.plan
            }
            pipeline.run(plan, destination, fileName).collect { status ->
                phase.value = when (status) {
                    ExportStatus.Preparing -> ExportPhase.Running(ExportStage.PREPARING, null)
                    is ExportStatus.Rendering -> ExportPhase.Running(ExportStage.RENDERING, status.percent)
                    ExportStatus.Verifying -> ExportPhase.Running(ExportStage.VERIFYING, null)
                    ExportStatus.Saving -> ExportPhase.Running(ExportStage.SAVING, null)
                    is ExportStatus.Completed -> ExportPhase.Done(status.result)
                    is ExportStatus.Failed -> ExportPhase.Failed(status.error, status.detail)
                }
            }
        }
    }

    /** Stops a running export. The encoder is released and the partial file deleted. */
    public fun cancel() {
        val running = job ?: return
        if (phase.value !is ExportPhase.Running) return
        running.cancel()
        job = null
        phase.value = ExportPhase.Setup(cancelled = true)
    }

    /** Back to the options after a failure, a finished export, or the cancelled notice. */
    public fun backToSetup() {
        if (phase.value is ExportPhase.Running) return
        phase.value = ExportPhase.Setup()
    }

    private suspend fun unavailableMedia(project: Project): Set<MediaId> =
        project.media.filter { checkMedia(it) == MediaHealth.MISSING }.map { it.id }.toSet()

    private val ExportResolution.key: String get() = "${widthPx}x$heightPx"
    private val FrameRate.key: String get() = "$numerator/$denominator"

    private companion object {
        const val KEY_RESOLUTION = "exportResolution"
        const val KEY_FRAME_RATE = "exportFrameRate"
        const val MAX_NAME_LENGTH = 60
    }
}
