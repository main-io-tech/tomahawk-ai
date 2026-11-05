package com.mio.tomahawk.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CheckpointsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t") + args).directory(root.toFile()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out
    }

    private fun write(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `desfaz a missao sem perder o que nao foi commitado e sem mexer na branch nem no indice`() {
        val root = temp.newFolder("repo").toPath()
        git(root, "init", "-q")
        write(root, "src/App.kt", "v1")
        write(root, "README.md", "leia")
        git(root, "add", "-A")
        git(root, "commit", "-qm", "inicio")
        write(root, "src/App.kt", "v2 ainda sem commit")
        write(root, "notas.txt", "arquivo novo do usuario")
        git(root, "add", "README.md")
        val head = git(root, "rev-parse", "HEAD").trim()
        val status = git(root, "status", "--porcelain")

        val point = Checkpoints.create(root, "20261105-specify", "Designar Alvo")
        assertNotNull(point)
        assertEquals(head, git(root, "rev-parse", "HEAD").trim())
        assertEquals(status, git(root, "status", "--porcelain"))

        write(root, "src/App.kt", "estragado pela IA")
        Files.delete(root.resolve("README.md"))
        Files.delete(root.resolve("notas.txt"))
        write(root, "src/novo/Gerado.kt", "criado pela IA")
        write(root, "tomahawk/execucoes/registro.md", "o registro fica")
        assertEquals(4, Checkpoints.changes(root, point!!)!!.size)

        val undo = Checkpoints.restore(root, point)
        assertEquals("v2 ainda sem commit", Files.readString(root.resolve("src/App.kt")))
        assertEquals("leia", Files.readString(root.resolve("README.md")))
        assertEquals("arquivo novo do usuario", Files.readString(root.resolve("notas.txt")))
        assertFalse(Files.exists(root.resolve("src/novo")))
        assertTrue(Files.exists(root.resolve("tomahawk/execucoes/registro.md")))
        assertEquals(1, undo.removed)
        assertEquals(head, git(root, "rev-parse", "HEAD").trim())
        assertEquals(status, git(root, "status", "--porcelain").lines().filterNot { it.contains("tomahawk/") }.joinToString("\n"))
        assertEquals("Designar Alvo", Checkpoints.latest(root)?.label)

        val redo = Checkpoints.restore(root, undo.safety!!)
        assertEquals("estragado pela IA", Files.readString(root.resolve("src/App.kt")))
        assertTrue(redo.removed >= 0)
    }

    @Test
    fun `sem Git nao ha ponto de restauracao`() {
        val root = temp.newFolder("solto").toPath()
        write(root, "a.txt", "x")
        assertEquals(null, Checkpoints.create(root, "x", "y"))
    }
}
