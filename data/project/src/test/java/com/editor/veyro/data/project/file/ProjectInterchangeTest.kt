package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.time.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectInterchangeTest {

    private val project = Project(
        id = ProjectId("p"), name = "Trip", createdAtEpochMs = 1, updatedAtEpochMs = 2,
        media = listOf(
            MediaRef(
                id = MediaId("v"), uri = "content://v", kind = MediaKind.VIDEO, displayName = "v.mp4", mimeType = "video/mp4",
                durationUs = 1_000_000, widthPx = 640, heightPx = 360, rotationDegrees = 0, sizeBytes = 99,
            ),
        ),
        settings = ProjectSettings(1_280, 720, FrameRate.FPS_25, snapToEdges = false),
    )

    @Test
    fun `export wraps the versioned document and imports back unchanged`() {
        val text = ProjectInterchange.export(project, exportedAtEpochMs = 123)
        assertTrue(text.contains("\"format\":\"veyro.project\""))
        assertTrue(text.contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
        assertEquals(ProjectInterchange.ImportResult.Ok(project), ProjectInterchange.import(text))
    }

    @Test
    fun `older embedded documents are migrated on import`() {
        val text = """{"format":"veyro.project","formatVersion":1,"project":
            {"schemaVersion":1,"id":"old","name":"Old","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "media":[{"id":"m","uri":"content://m","kind":"image","durationUs":0,"widthPx":100,"heightPx":50,"rotationDegrees":0}]}}"""
        val imported = (ProjectInterchange.import(text) as ProjectInterchange.ImportResult.Ok).project
        assertEquals("Old", imported.name)
        assertEquals(100, imported.settings.canvasWidthPx)
    }

    @Test
    fun `other files are rejected with a reason`() {
        assertEquals(ProjectInterchange.ImportResult.NotAProjectFile, ProjectInterchange.import("hello"))
        assertEquals(ProjectInterchange.ImportResult.NotAProjectFile, ProjectInterchange.import("""{"name":"x"}"""))
        assertEquals(ProjectInterchange.ImportResult.NewerVersion, ProjectInterchange.import("""{"format":"veyro.project","formatVersion":9,"project":{}}"""))
        assertEquals(
            ProjectInterchange.ImportResult.NewerVersion,
            ProjectInterchange.import("""{"format":"veyro.project","formatVersion":1,"project":{"schemaVersion":99}}"""),
        )
        assertEquals(ProjectInterchange.ImportResult.Damaged, ProjectInterchange.import("""{"format":"veyro.project","formatVersion":1}"""))
        assertEquals(
            ProjectInterchange.ImportResult.Damaged,
            ProjectInterchange.import("""{"format":"veyro.project","formatVersion":1,"project":{"schemaVersion":4,"id":"x"}}"""),
        )
    }

    @Test
    fun `file names are safe`() {
        assertEquals("Trip.veyro.json", ProjectInterchange.fileNameFor("Trip"))
        assertEquals("a_b_c_.veyro.json", ProjectInterchange.fileNameFor("a/b:c?"))
        assertEquals("project.veyro.json", ProjectInterchange.fileNameFor("   "))
    }
}
