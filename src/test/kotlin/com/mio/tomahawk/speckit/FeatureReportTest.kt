package com.mio.tomahawk.speckit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FeatureReportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val spec = """
        # Feature Specification: Photo Albums

        <!--
          ### User Story 9 - Template example (Priority: P3)
          [NEEDS CLARIFICATION: this one is inside a comment]
        -->

        ## User Scenarios & Testing

        ### User Story 1 - Organize photos (Priority: P1) 🎯 MVP

        ### User Story 2 - Reorder albums (Priority: P2)

        ## Requirements

        - **FR-001**: System MUST group albums by date
        - **FR-002**: System MUST allow drag and drop
        - **FR-003**: System MUST keep photos local [NEEDS CLARIFICATION: max photo size?]

        ## Success Criteria

        - **SC-001**: Users find an album in under 10 seconds

        ## Clarifications

        ### Session 2026-11-04

        - Q: Nested albums? → A: Never
        - Q: Who can delete? → A: Owner only

        ## Assumptions

        ```text
        - [ ] this checkbox is code, not a task
        ```
    """.trimIndent()

    private val tasks = """
        # Tasks: Photo Albums

        ## Phase 1: Setup

        - [x] T001 Create project structure
        - [X] T002 [P] Configure linting

        ## Phase 3: User Story 1 - Organize photos (Priority: P1)

        - [ ] T010 [P] [US1] Create Album model in src/models/album.kt
        - [ ] T011 [US1] Implement AlbumService
    """.trimIndent()

    @Test
    fun `lê histórias, requisitos, critérios, esclarecimentos e pendências da spec ignorando comentários`() {
        val file = Path.of("spec.md")
        val summary = Markdown.spec(file, spec.lines())
        assertEquals(listOf(1, 2), summary.stories.map { it.number })
        assertEquals("Organize photos", summary.stories[0].title)
        assertEquals("P1", summary.stories[0].priority)
        assertEquals(3, summary.requirements)
        assertEquals(1, summary.successCriteria)
        assertEquals(2, summary.clarifications)
        assertEquals(1, summary.pending.size)
        assertTrue(summary.pending.single().text.contains("max photo size"))
    }

    @Test
    fun `agrupa as tarefas por fase com id, paralelismo e história`() {
        val phases = Markdown.phases(tasks.lines())
        assertEquals(listOf("Phase 1: Setup", "Phase 3: User Story 1 - Organize photos (Priority: P1)"), phases.map { it.title })
        assertEquals(2, phases[0].done)
        val model = phases[1].tasks[0]
        assertEquals("T010", model.id)
        assertTrue(model.parallel)
        assertEquals("US1", model.story)
        assertFalse(model.done)
        assertEquals("US1", phases[1].tasks[1].story)
        assertNull(phases[0].tasks[0].story)
    }

    @Test
    fun `lê o contexto técnico do plano`() {
        val plan = """
            # Implementation Plan

            ## Technical Context

            **Language/Version**: Kotlin 2.4
            **Primary Dependencies**: Ktor
            **Storage**: SQLite

            ## Constitution Check
            **Gate**: ignored
        """.trimIndent()
        val summary = Markdown.plan(Path.of("plan.md"), plan.lines())
        assertEquals(listOf("Language/Version" to "Kotlin 2.4", "Primary Dependencies" to "Ktor", "Storage" to "SQLite"), summary.context)
    }

    @Test
    fun `lê a pasta da feature com artefatos de design e checklists`() {
        val dir = temp.newFolder("specs", "001-photo-albums").toPath()
        Files.writeString(dir.resolve("spec.md"), spec)
        Files.writeString(dir.resolve("plan.md"), "# Plan")
        Files.writeString(dir.resolve("tasks.md"), tasks)
        Files.writeString(dir.resolve("research.md"), "# Research")
        Files.createDirectories(dir.resolve("contracts"))
        Files.writeString(dir.resolve("contracts").resolve("api.yaml"), "openapi: 3.1.0")
        Files.createDirectories(dir.resolve("checklists"))
        Files.writeString(dir.resolve("checklists").resolve("requirements.md"), "- [x] CHK001 ok")
        Files.writeString(dir.resolve("checklists").resolve("ux.md"), "- [ ] CHK001 a\n- [x] CHK002 b")

        val report = FeatureReport.read(dir)
        assertEquals("001-photo-albums", report.name)
        assertEquals(4, report.tasks?.total)
        assertEquals(2, report.tasks?.done)
        assertEquals(2, report.designDocs.size)
        assertEquals(listOf("ux.md"), report.customChecklists.map { it.file.fileName.toString() })
        assertEquals(1, report.customChecklists.single().done)
        assertTrue(report.tasksModified > 0)
    }
}
