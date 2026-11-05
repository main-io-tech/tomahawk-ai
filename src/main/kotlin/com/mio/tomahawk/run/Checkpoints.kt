package com.mio.tomahawk.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * O ponto de restauração antes de cada missão: o projeto inteiro, com o que não foi commitado e os arquivos novos
 * (menos o que o `.gitignore` ignora e a pasta do Tomahawk), num commit solto em `refs/tomahawk/<execução>`. Usa um
 * índice temporário, então não mexe no índice, na branch nem no stash do usuário. Desfazer volta os arquivos para o
 * ponto e apaga os que apareceram depois, guardando antes o estado de agora, para dar para voltar atrás.
 */
object Checkpoints {

    class Point(val ref: String, val commit: String, val label: String, val createdAt: Long)

    class Undo(val restored: Int, val removed: Int, val safety: Point?)

    const val PREFIX = "refs/tomahawk/"
    private const val UNDO_PREFIX = "${PREFIX}desfeito-"
    private const val SUBJECT = "Tomahawk: antes de "
    private const val KEEP = 30
    private const val TIMEOUT_MS = 180_000
    private val LOG = logger<Checkpoints>()
    private val IDENTITY = mapOf(
        "GIT_AUTHOR_NAME" to "Tomahawk IA", "GIT_AUTHOR_EMAIL" to "tomahawk@localhost",
        "GIT_COMMITTER_NAME" to "Tomahawk IA", "GIT_COMMITTER_EMAIL" to "tomahawk@localhost",
    )

    /** O projeto em [root] é um repositório Git e o `git` existe. */
    fun available(root: Path): Boolean = git(root, emptyMap(), "rev-parse", "--is-inside-work-tree")?.trim() == "true"

    /** Fora da EDT. Guarda o projeto agora, como o ponto [name] de "[label]"; nulo sem Git ou se falhar. */
    fun create(root: Path, name: String, label: String): Point? {
        if (!available(root)) return null
        val commit = snapshot(root, SUBJECT + label) ?: return null
        val ref = PREFIX + name
        git(root, emptyMap(), "update-ref", ref, commit) ?: return null
        prune(root)
        return Point(ref, commit, label, System.currentTimeMillis())
    }

    /** Os pontos do projeto, do mais novo para o mais antigo, sem os guardados ao desfazer. */
    fun list(root: Path): List<Point> {
        val out = git(root, emptyMap(), "for-each-ref", "--sort=-creatordate", "--format=%(refname)%09%(objectname)%09%(creatordate:unix)%09%(contents:subject)", PREFIX)
            ?: return emptyList()
        return out.lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 4).takeIf { it.size == 4 } ?: return@mapNotNull null
            Point(parts[0], parts[1], parts[3].removePrefix(SUBJECT), (parts[2].toLongOrNull() ?: 0) * 1000)
        }.filterNot { it.ref.startsWith(UNDO_PREFIX) }
    }

    fun latest(root: Path): Point? = list(root).firstOrNull()

    /** Os arquivos que mudaram no projeto desde [point]; nulo se o Git falhar. */
    fun changes(root: Path, point: Point): List<String>? {
        val now = snapshot(root, "Tomahawk: agora") ?: return null
        return names(root, "diff", "--name-only", "--no-renames", "-z", point.commit, now)
    }

    /**
     * Fora da EDT. Volta o projeto para [point]: os arquivos alterados ou apagados voltam como eram, e os que
     * apareceram depois são apagados. O estado de agora vira um ponto antes, para dar para refazer.
     */
    fun restore(root: Path, point: Point): Undo {
        val safety = snapshot(root, SUBJECT + "desfazer ${point.label}")?.let { commit ->
            val ref = UNDO_PREFIX + System.currentTimeMillis()
            git(root, emptyMap(), "update-ref", ref, commit)?.let { Point(ref, commit, "desfazer ${point.label}", System.currentTimeMillis()) }
        } ?: throw IllegalStateException("O Git não conseguiu guardar o estado de agora; nada foi desfeito.")
        val added = names(root, "diff", "--name-only", "--no-renames", "--diff-filter=A", "-z", point.commit, safety.commit).orEmpty()
        val changed = names(root, "diff", "--name-only", "--no-renames", "--diff-filter=MDT", "-z", point.commit, safety.commit).orEmpty()
        if (changed.isNotEmpty()) {
            git(root, emptyMap(), "restore", "--source=${point.commit}", "--worktree", "--", ".", ":(exclude)${RunStore.DIR}")
                ?: throw IllegalStateException("O git restore falhou; o estado antes de desfazer está em ${safety.ref}.")
        }
        var removed = 0
        added.filterNot { it == RunStore.DIR || it.startsWith(RunStore.DIR + "/") }.forEach { relative ->
            val file = root.resolve(relative).normalize()
            if (file.startsWith(root) && runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                removed++
                var dir = file.parent
                while (dir != null && dir != root && dir.startsWith(root) && runCatching { Files.list(dir).use { !it.findAny().isPresent } }.getOrDefault(false)) {
                    Files.deleteIfExists(dir)
                    dir = dir.parent
                }
            }
        }
        return Undo(changed.size, removed, safety)
    }

    /** O projeto num commit solto, por um índice temporário copiado do real (que guarda o que o Git já sabe). */
    private fun snapshot(root: Path, message: String): String? {
        val index = Files.createTempFile("tomahawk-index", "")
        try {
            val real = git(root, emptyMap(), "rev-parse", "--git-path", "index")?.trim()?.takeIf { it.isNotEmpty() }?.let { root.resolve(it) }
            if (real != null && Files.isRegularFile(real)) Files.copy(real, index, StandardCopyOption.REPLACE_EXISTING) else Files.delete(index)
            val env = mapOf("GIT_INDEX_FILE" to index.toString())
            git(root, env, "add", "-A", "--", ".", ":(exclude)${RunStore.DIR}") ?: git(root, env, "add", "-A") ?: return null
            val tree = git(root, env, "write-tree")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val head = git(root, emptyMap(), "rev-parse", "--verify", "-q", "HEAD")?.trim()?.takeIf { it.isNotEmpty() }
            val args = listOf("commit-tree", tree) + (head?.let { listOf("-p", it) } ?: emptyList()) + listOf("-m", message)
            return git(root, IDENTITY, *args.toTypedArray())?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            Files.deleteIfExists(index)
        }
    }

    private fun prune(root: Path) {
        list(root).drop(KEEP).forEach { git(root, emptyMap(), "update-ref", "-d", it.ref) }
        val undone = git(root, emptyMap(), "for-each-ref", "--sort=-creatordate", "--format=%(refname)", UNDO_PREFIX)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        undone.drop(KEEP).forEach { git(root, emptyMap(), "update-ref", "-d", it) }
    }

    private fun names(root: Path, vararg args: String): List<String>? =
        git(root, emptyMap(), *args)?.split('\u0000')?.map { it.trim('\n', '\r') }?.filter { it.isNotEmpty() }

    private val executable: String by lazy {
        PathEnvironmentVariableUtil.findInPath(if (SystemInfo.isWindows) "git.exe" else "git")?.path
            ?: listOf("C:/Program Files/Git/cmd/git.exe", "/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git").firstOrNull { File(it).isFile }
            ?: "git"
    }

    private fun git(root: Path, env: Map<String, String>, vararg args: String): String? = runCatching {
        val line = GeneralCommandLine(listOf(executable) + args)
            .withWorkDirectory(root.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(env + ("GIT_OPTIONAL_LOCKS" to "0"))
        val output = ExecUtil.execAndGetOutput(line, TIMEOUT_MS)
        if (output.isTimeout || output.exitCode != 0) {
            LOG.info("git ${args.firstOrNull()} falhou (${output.exitCode}): ${output.stderr.take(300)}")
            null
        } else {
            output.stdout
        }
    }.getOrElse {
        LOG.info("git ${args.firstOrNull()} não rodou", it)
        null
    }
}
