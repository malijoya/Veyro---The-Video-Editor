package com.editor.veyro.data.project

import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import kotlinx.coroutines.flow.Flow

/** Saved projects. The only way the rest of the app reads or changes them. */
public interface ProjectRepository {
    /** All readable projects, most recently updated first. */
    public val projects: Flow<List<Project>>

    /** The project with [id], or `null` if it doesn't exist or was deleted. */
    public fun project(id: ProjectId): Flow<Project?>

    /**
     * Creates and saves a project. [name] goes through
     * [com.editor.veyro.core.model.project.ProjectName.normalize].
     *
     * @throws IllegalArgumentException if the name is blank or [media] is empty.
     */
    public suspend fun create(name: String, media: List<MediaRef>): Project

    /**
     * Appends [media] to the project, skipping items whose source URI is
     * already in it. Returns the number added, or `null` if the project no
     * longer exists.
     */
    public suspend fun addMedia(id: ProjectId, media: List<MediaRef>): Int?

    /** @throws IllegalArgumentException if the name is blank. Returns false if the project no longer exists. */
    public suspend fun rename(id: ProjectId, name: String): Boolean

    /** Deletes the project and its files. Returns false if it didn't exist. */
    public suspend fun delete(id: ProjectId): Boolean

    /** URIs of all media used by any saved project. Used to release media access no project needs. */
    public suspend fun referencedMediaUris(): Set<String>

    /** Bytes used on disk by project documents. */
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
