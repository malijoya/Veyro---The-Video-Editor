package com.editor.veyro.data.project

import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.Timeline
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debounced autosave for timeline edits.
 *
 * - **Coalescing:** a burst of edits (dragging, then quick undos) becomes one
 *   write, [delayMs] after the last edit. Only the latest timeline per
 *   project is kept.
 * - **Flushing:** [flush] writes immediately. The editor calls it when it
 *   goes to the background and when it closes. [flushInBackground] does the
 *   same from places that can't suspend.
 * - **Scope:** runs in an app-wide [scope], not a screen's, so a pending
 *   save finishes even after the editor screen is gone.
 * - **No cancellation:** an in-progress write is never cancelled. A newer
 *   edit waits for it, then writes after its own delay.
 */
public class ProjectAutosave(
    private val repository: ProjectRepository,
    private val scope: CoroutineScope,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    public sealed interface Status {
        public data object Idle : Status
        public data object Pending : Status
        public data object Saving : Status
        public data class Saved(val atEpochMs: Long) : Status
        public data object Failed : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    public val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Any()
    private val pending = LinkedHashMap<ProjectId, Timeline>()
    private var generation = 0L
    private val writeMutex = Mutex()

    /** Queues [timeline] to be saved for [projectId], replacing any earlier pending edit for it. */
    public fun schedule(projectId: ProjectId, timeline: Timeline) {
        val ticket = synchronized(lock) {
            pending[projectId] = timeline
            ++generation
        }
        _status.value = Status.Pending
        scope.launch {
            delay(delayMs)
            // Only the latest edit's timer writes. Earlier ones find a newer generation and stop.
            if (synchronized(lock) { generation == ticket }) writePending()
        }
    }

    /** Writes everything pending now. Returns once it's on disk (or failed). */
    public suspend fun flush() {
        synchronized(lock) { ++generation }
        writePending()
    }

    public fun flushInBackground() {
        scope.launch { flush() }
    }

    public fun hasPendingChanges(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    private suspend fun writePending() {
        writeMutex.withLock {
            val work = synchronized(lock) { LinkedHashMap(pending).also { pending.clear() } }
            if (work.isEmpty()) return
            _status.value = Status.Saving
            val failed = mutableMapOf<ProjectId, Timeline>()
            for ((id, timeline) in work) {
                try {
                    repository.updateTimeline(id, timeline)
                } catch (e: IOException) {
                    failed[id] = timeline
                } catch (e: IllegalArgumentException) {
                    // An invalid timeline will never save. Don't retry it forever.
                }
            }
            if (failed.isNotEmpty()) {
                // Keep failed edits for the next attempt, unless a newer edit replaced them meanwhile.
                synchronized(lock) { failed.forEach { (id, t) -> pending.putIfAbsent(id, t) } }
                _status.value = Status.Failed
            } else {
                _status.value = if (hasPendingChanges()) Status.Pending else Status.Saved(clock())
            }
        }
    }

    public companion object {
        /** Long enough to merge a drag's last frames and quick follow-up taps. Short enough that a crash loses almost nothing. */
        public const val DEFAULT_DELAY_MS: Long = 600
    }
}
