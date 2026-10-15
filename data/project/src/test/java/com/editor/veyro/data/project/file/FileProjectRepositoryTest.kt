package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.data.project.MediaAccessReleaser
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FileProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private var nextId = 0
    private val released = mutableListOf<Set<String>>()
    private val unreadable = mutableListOf<String>()

    private fun TestScope.repository(root: File = tmp.root) = FileProjectRepository(
        rootDir = root,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        mediaAccessReleaser = MediaAccessReleaser { released += it },
        clock = { now },
        newId = { "id-${nextId++}" },
        onUnreadable = { id, reason -> unreadable += "$id: $reason" },
    )

    private fun media(name: String, kind: MediaKind = MediaKind.VIDEO) = MediaRef(
        id = MediaId("m-$name"), uri = "content://test/$name", kind = kind,
        displayName = name, mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/png",
        durationUs = if (kind == MediaKind.VIDEO) 3_000_000 else 0,
        widthPx = 1280, heightPx = 720, rotationDegrees = 90, sizeBytes = 42,
    )

    @Test
    fun `created projects persist and reload in a new instance`() = runTest {
        val created = repository().create("  My   trip ", listOf(media("a"), media("b", MediaKind.IMAGE)))
        assertEquals("My trip", created.name)
        assertEquals(1_000L, created.createdAtEpochMs)

        val reloaded = repository().projects.first()
        assertEquals(listOf(created), reloaded)
        assertTrue(File(tmp.root, "id-0/project.json").isFile)
        assertFalse(File(tmp.root, "id-0/project.json.tmp").exists())
    }

    @Test
    fun `projects are ordered by most recent update`() = runTest {
        val repo = repository()
        val first = repo.create("First", listOf(media("a")))
        now = 2_000
        val second = repo.create("Second", listOf(media("b")))
        assertEquals(listOf(second.id, first.id), repo.projects.first().map { it.id })

        now = 3_000
        assertTrue(repo.rename(first.id, "First renamed"))
        assertEquals(listOf(first.id, second.id), repo.projects.first().map { it.id })
        assertEquals("First renamed", repository().project(first.id).first()?.name)
    }

    @Test
    fun `renaming to the same name does not touch the update time`() = runTest {
        val repo = repository()
        val project = repo.create("Same", listOf(media("a")))
        now = 9_000
        assertTrue(repo.rename(project.id, " Same "))
        assertEquals(1_000L, repo.project(project.id).first()?.updatedAtEpochMs)
    }

    @Test
    fun `invalid input is rejected`() = runTest {
        val repo = repository()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.create("   ", listOf(media("a"))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.create("Empty", emptyList()) }
        }
        assertFalse(repo.rename(ProjectId("missing"), "Name"))
        assertTrue(repo.projects.first().isEmpty())
    }

    @Test
    fun `delete removes files and releases media no longer referenced`() = runTest {
        val repo = repository()
        val keep = repo.create("Keep", listOf(media("shared"), media("keep")))
        val drop = repo.create("Drop", listOf(media("shared"), media("drop")))

        assertTrue(repo.delete(drop.id))
        assertFalse(File(tmp.root, drop.id.value).exists())
        assertNull(repo.project(drop.id).first())
        assertEquals(listOf(setOf("content://test/shared", "content://test/keep")), released)
        assertEquals(listOf(keep), repository().projects.first())

        assertFalse(repo.delete(drop.id))
        assertEquals(1, released.size)
    }

    @Test
    fun `unreadable documents are skipped but kept on disk`() = runTest {
        val repo = repository()
        val good = repo.create("Good", listOf(media("a")))
        File(tmp.root, "broken").apply { mkdirs() }.resolve("project.json").writeText("{ not json")
        File(tmp.root, "future").apply { mkdirs() }
            .resolve("project.json").writeText("""{"schemaVersion": 99, "id": "future"}""")
        File(tmp.root, "mismatch").apply { mkdirs() }.resolve("project.json")
            .writeText(File(tmp.root, "${good.id.value}/project.json").readText())

        assertEquals(listOf(good), repository().projects.first())
        assertEquals(3, unreadable.size)
        assertTrue(unreadable.any { it.startsWith("future: Saved by a newer app version") })
        assertTrue(File(tmp.root, "broken/project.json").exists())
    }

    @Test
    fun `storage usage counts document bytes`() = runTest {
        val repo = repository()
        assertEquals(0L, repo.storageBytes())
        repo.create("Sized", listOf(media("a")))
        assertEquals(File(tmp.root, "id-0/project.json").length(), repo.storageBytes())
    }

    @Test
    fun `document format is stable`() {
        val project = com.editor.veyro.core.model.project.Project(
            id = ProjectId("p"), name = "N", createdAtEpochMs = 1, updatedAtEpochMs = 2,
            media = listOf(media("a")),
        )
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
        assertTrue(json.contains("\"kind\":\"video\""))
        assertEquals(DecodeResult.Ok(project), ProjectDocumentCodec.decode(json))
    }
}
