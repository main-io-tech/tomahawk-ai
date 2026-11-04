package com.mio.tomahawk.speckit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class SpecKitLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(root: Path, relative: String, text: String = "x") {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `lê integração, constituição, features e feature ativa`() {
        val root = temp.root.toPath()
        write(root, ".specify/integration.json", """{"default_integration":"claude","integration_settings":{"claude":{"invoke_separator":"-"}}}""")
        write(root, ".specify/memory/constitution.md", "# [PROJECT_NAME] Constitution")
        write(root, "specs/001-first/spec.md")
        write(root, "specs/002-second/spec.md")
        val layout = SpecKitLayout(root)

        assertTrue(layout.initialized)
        assertEquals(IntegrationInfo("claude", "-"), layout.integration())
        assertFalse(layout.constitutionReady())
        assertEquals(listOf("001-first", "002-second"), layout.features().map { it.fileName.toString() })
        assertEquals("002-second", layout.activeFeature()?.fileName.toString())
        assertEquals("001-first", layout.activeFeature(gitBranch = "001-first")?.fileName.toString())

        layout.activate(root.resolve("specs/001-first"))
        assertEquals("001-first", layout.activeFeature(gitBranch = "002-second")?.fileName.toString())
        assertTrue(Files.readString(root.resolve(".specify/feature.json")).contains("\"specs/001-first\""))

        write(root, ".specify/memory/constitution.md", "# Photo Constitution\n## Core Principles")
        assertTrue(layout.constitutionReady())
    }

    @Test
    fun `acha skills e arquivos de comando, preferindo a pasta do agente`() {
        val root = temp.root.toPath()
        write(root, ".specify/memory/constitution.md")
        write(root, ".claude/skills/speckit-specify/SKILL.md")
        write(root, ".cursor/commands/speckit.specify.md")
        write(root, ".cursor/commands/speckit.plan.md")
        write(root, ".github/prompts/speckit.tasks.prompt.md")
        write(root, ".git/speckit.analyze.md")

        val commands = SpecKitLayout(root).commands(preferred = listOf(".cursor"))
        assertEquals(setOf(SddStep.SPECIFY, SddStep.PLAN, SddStep.TASKS), commands.keys)
        assertFalse(commands.getValue(SddStep.SPECIFY).skill)
        assertEquals(".", commands.getValue(SddStep.PLAN).separator)

        val claude = SpecKitLayout(root).commands(preferred = listOf(".claude"))
        assertTrue(claude.getValue(SddStep.SPECIFY).skill)
        assertEquals("-", claude.getValue(SddStep.SPECIFY).separator)
    }
}
