package com.editor.veyro.data.project

import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.timeline.Timeline
import kotlinx.coroutines.flow.Flow

/** Saved projects. The only way the rest of the app reads or changes them. */
public interface ProjectRepository {
    /** Every project's list metadata, most recently updated first. Served by the index, without opening documents. */
    public val summaries: Flow<List<ProjectSummary>>

    /** The full project with [id], or `null` if it doesn't exist, was deleted, or can't be read. */
    public fun project(id: ProjectId): Flow<Project?>

    /**
     * Creates and saves a project. [name] goes through
     * [com.editor.veyro.core.model.project.ProjectName.normalize]. Settings
     * default to matching the first visual media
     * ([ProjectSettings.matching]).
     *
     * @throws IllegalArgumentException if the name is blank, [media] is empty,
     *   or a media URI points at a temporary location (see [SourceReferencePolicy]).
     */
    public suspend fun create(name: String, media: List<MediaRef>, settings: ProjectSettings? = null): Project

    /**
     * Appends [media] to the project, skipping items whose source URI is
     * already in it. If the project has an edited timeline, the new media is
     * added to the end of its tracks too. Returns the number added, or `null`
     * if the project no longer exists.
     *
     * @throws IllegalArgumentException if a media URI points at a temporary location.
     */
    public suspend fun addMedia(id: ProjectId, media: List<MediaRef>): Int?

    /**
     * Saves an edited timeline. Returns false if the project no longer exists.
     *
     * @throws IllegalArgumentException if a clip references media that isn't in the project.
     */
    public suspend fun updateTimeline(id: ProjectId, timeline: Timeline): Boolean

    /**
     * Saves new project settings. A change of frame rate also changes the
     * stored timeline's frame grid. Clip times stay exactly as they are.
     * Returns false if the project no longer exists.
     */
    public suspend fun updateSettings(id: ProjectId, settings: ProjectSettings): Boolean

    /**
     * Saves [project] (for example, read from an exported project file) as a
     * new project, under a new ID and with "now" as its update time.
     * Returns the stored copy.
     */
    public suspend fun importProject(project: Project): Project

    /** @throws IllegalArgumentException if the name is blank. Returns false if the project no longer exists. */
    public suspend fun rename(id: ProjectId, name: String): Boolean

    /** Deletes the project and its files. Returns false if it didn't exist. */
    public suspend fun delete(id: ProjectId): Boolean

    /** URIs of all media used by any saved project. Used to release media access no project needs. */
    public suspend fun referencedMediaUris(): Set<String>

    /** Bytes used on disk by project documents and their backups. */
    public suspend fun storageBytes(): Long
}

/**
 * Releases persisted read access to source media that no saved project
 * references any more. It is implemented by the media layer and called after
 * a project is deleted.
 */
public fun interface MediaAccessReleaser {
    public suspend fun releaseAllExcept(referencedUris: Set<String>)
}

/**
 * Rejects source references that won't survive: files inside the app's
 * cache or temp folders, which the system may delete at any time. Projects
 * must reference media where the user keeps it, through a `content://` URI
 * with persisted access (or a stable file path).
 */
public class SourceReferencePolicy(temporaryRoots: List<java.io.File>) {
    private val roots = temporaryRoots.map { it.absoluteFile.normalize().path.trimEnd('/', '\\') }

    public fun isPermanent(uri: String): Boolean {
        if (!uri.startsWith("file:")) return true
        val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return false
        // File(URI) decodes the path correctly on every platform, including escaped characters.
        val file = runCatching { java.io.File(parsed) }.getOrNull() ?: return false
        val normalized = file.absoluteFile.normalize().path
        val inTemporaryRoot = roots.any { root -> normalized == root || normalized.startsWith(root + java.io.File.separator) }
        val path = parsed.path.orEmpty()
        return !inTemporaryRoot && !path.startsWith("/tmp/") && !path.startsWith("/data/local/tmp/")
    }

    public fun requirePermanent(media: List<MediaRef>) {
        media.forEach { require(isPermanent(it.uri)) { "Media ${it.displayName ?: it.id.value} is in a temporary location: ${it.uri}" } }
    }

    public companion object {
        /** Accepts everything. For tests that don't exercise the policy. */
        public val PERMISSIVE: SourceReferencePolicy = SourceReferencePolicy(emptyList())
    }
}
