package com.mio.tomahawk.speckit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Um ponto de um artefato para abrir no editor; [line] começa em 0. */
data class Marker(val file: Path, val line: Int, val text: String)

data class UserStory(val number: Int, val title: String, val priority: String?, val line: Int)

data class TaskItem(
    val id: String?,
    val done: Boolean,
    val parallel: Boolean,
    val story: String?,
    val text: String,
    val line: Int,
)

data class TaskPhase(val title: String, val line: Int, val tasks: List<TaskItem>) {
    val done: Int get() = tasks.count { it.done }
}

data class CheckItem(val done: Boolean, val text: String, val line: Int)

/** Um checklist de `checklists/`; o `requirements.md` é o da spec, mantido pelo specify e pelo clarify. */
data class Checklist(val file: Path, val items: List<CheckItem>) {
    val done: Int get() = items.count { it.done }
    val builtIn: Boolean get() = file.name == "requirements.md"
}

data class SpecSummary(
    val file: Path,
    val stories: List<UserStory>,
    val requirements: Int,
    val successCriteria: Int,
    /** Perguntas respondidas na seção `## Clarifications`. */
    val clarifications: Int,
    val pending: List<Marker>,
)

data class PlanSummary(val file: Path, val context: List<Pair<String, String>>, val pending: List<Marker>)

data class TasksSummary(val file: Path, val phases: List<TaskPhase>) {
    val all: List<TaskItem> get() = phases.flatMap { it.tasks }
    val total: Int get() = all.size
    val done: Int get() = all.count { it.done }
}

/** O que foi gerado numa feature `specs/NNN-nome/`, lido dos artefatos para avaliar e decidir a próxima etapa. */
data class FeatureReport(
    val dir: Path,
    val spec: SpecSummary?,
    val plan: PlanSummary?,
    val tasks: TasksSummary?,
    val checklists: List<Checklist>,
    /** research.md, data-model.md, quickstart.md e os arquivos de contracts/. */
    val designDocs: List<Path>,
    /** A modificação mais recente do tasks.md, para saber se uma análise ou convergência ainda vale. */
    val tasksModified: Long,
) {
    val name: String get() = dir.name
    val pending: List<Marker> get() = spec?.pending.orEmpty() + plan?.pending.orEmpty()
    val customChecklists: List<Checklist> get() = checklists.filterNot { it.builtIn }

    companion object {
        fun read(dir: Path): FeatureReport {
            val specFile = dir.resolve("spec.md").takeIf { it.isRegularFile() }
            val planFile = dir.resolve("plan.md").takeIf { it.isRegularFile() }
            val tasksFile = dir.resolve("tasks.md").takeIf { it.isRegularFile() }
            val checklistsDir = dir.resolve("checklists")
            val checklists = if (checklistsDir.isDirectory()) {
                Files.list(checklistsDir).use { files -> files.filter { it.isRegularFile() && it.name.endsWith(".md") }.toList() }
                    .sortedBy { it.name }
                    .map { Checklist(it, Markdown.checkItems(lines(it))) }
            } else emptyList()
            val design = listOf("research.md", "data-model.md", "quickstart.md").map(dir::resolve).filter { it.isRegularFile() } +
                dir.resolve("contracts").takeIf { it.isDirectory() }?.let { contracts ->
                    Files.walk(contracts, 3).use { walk -> walk.filter { it.isRegularFile() }.toList() }.sortedBy { it.toString() }
                }.orEmpty()
            return FeatureReport(
                dir = dir,
                spec = specFile?.let { Markdown.spec(it, lines(it)) },
                plan = planFile?.let { Markdown.plan(it, lines(it)) },
                tasks = tasksFile?.let { TasksSummary(it, Markdown.phases(lines(it))) },
                checklists = checklists,
                designDocs = design,
                tasksModified = tasksFile?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) } ?: 0L,
            )
        }

        private fun lines(file: Path): List<String> = SpecKitLayout.read(file)?.lines().orEmpty()
    }
}

/** A leitura dos Markdown que o Spec Kit gera, a partir dos modelos dele. */
object Markdown {

    private val CHECKBOX = Regex("""^\s*[-*+]\s+\[([ xX])]\s+(.*)$""")
    private val TASK_ID = Regex("""^(T\d{2,})\b""")
    private val STORY_TAG = Regex("""\[(US\d+)]""")
    private val PARALLEL = Regex("""\[P]""")
    private val HEADING_2 = Regex("""^##\s+(.+?)\s*$""")
    private val STORY = Regex(
        """^#{2,4}\s+User Story\s+(\d+)\s*[-–—:]?\s*(.*?)\s*(?:\(Priority:\s*(P\d+)\))?\s*(?:🎯.*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val REQUIREMENT = Regex("""\bFR-\d+""")
    private val CRITERION = Regex("""\bSC-\d+""")
    private val NEEDS_CLARIFICATION = Regex("""\[NEEDS CLARIFICATION[^\]]*]""", RegexOption.IGNORE_CASE)
    private val CONTEXT_FIELD = Regex("""^\*\*([^*]+?)\*\*\s*:\s*(.+)$""")

    fun checkItems(lines: List<String>): List<CheckItem> = visible(lines).mapNotNull { (index, line) ->
        CHECKBOX.matchEntire(line)?.let { CheckItem(it.groupValues[1].isNotBlank(), it.groupValues[2].trim(), index) }
    }

    /** As tarefas agrupadas pelos títulos `##` (as fases); as que vêm antes do primeiro título ficam em "Tarefas". */
    fun phases(lines: List<String>): List<TaskPhase> {
        val phases = mutableListOf<TaskPhase>()
        var title = "Tarefas"
        var titleLine = 0
        var tasks = mutableListOf<TaskItem>()
        fun close() {
            if (tasks.isNotEmpty()) phases += TaskPhase(title, titleLine, tasks)
            tasks = mutableListOf()
        }
        for ((index, line) in visible(lines)) {
            val heading = HEADING_2.matchEntire(line)
            if (heading != null) {
                close()
                title = heading.groupValues[1]
                titleLine = index
                continue
            }
            val match = CHECKBOX.matchEntire(line) ?: continue
            val text = match.groupValues[2].trim()
            tasks += TaskItem(
                id = TASK_ID.find(text)?.groupValues?.get(1),
                done = match.groupValues[1].isNotBlank(),
                parallel = PARALLEL.containsMatchIn(text),
                story = STORY_TAG.find(text)?.groupValues?.get(1),
                text = text,
                line = index,
            )
        }
        close()
        return phases
    }

    fun spec(file: Path, lines: List<String>): SpecSummary {
        val visible = visible(lines)
        val stories = visible.mapNotNull { (index, line) ->
            STORY.matchEntire(line.trim())?.let { match ->
                UserStory(match.groupValues[1].toInt(), match.groupValues[2].trim().trimEnd('-', ' '), match.groupValues[3].ifBlank { null }, index)
            }
        }
        val text = visible.joinToString("\n") { it.second }
        return SpecSummary(
            file = file,
            stories = stories,
            requirements = REQUIREMENT.findAll(text).map { it.value }.toSet().size,
            successCriteria = CRITERION.findAll(text).map { it.value }.toSet().size,
            clarifications = clarifications(visible),
            pending = pending(file, visible),
        )
    }

    fun plan(file: Path, lines: List<String>): PlanSummary {
        val visible = visible(lines)
        val start = visible.indexOfFirst { (_, line) -> HEADING_2.matchEntire(line)?.groupValues?.get(1)?.contains("Technical Context", true) == true }
        val context = if (start < 0) emptyList() else visible.drop(start + 1)
            .takeWhile { (_, line) -> HEADING_2.matchEntire(line) == null }
            .mapNotNull { (_, line) -> CONTEXT_FIELD.matchEntire(line.trim())?.let { it.groupValues[1].trim() to it.groupValues[2].trim() } }
        return PlanSummary(file, context, pending(file, visible))
    }

    private fun clarifications(visible: List<Pair<Int, String>>): Int {
        val start = visible.indexOfFirst { (_, line) -> HEADING_2.matchEntire(line)?.groupValues?.get(1)?.startsWith("Clarifications", true) == true }
        if (start < 0) return 0
        return visible.drop(start + 1)
            .takeWhile { (_, line) -> HEADING_2.matchEntire(line) == null }
            .count { (_, line) -> line.trimStart().startsWith("- Q:") }
    }

    private fun pending(file: Path, visible: List<Pair<Int, String>>): List<Marker> =
        visible.flatMap { (index, line) -> NEEDS_CLARIFICATION.findAll(line).map { Marker(file, index, line.trim()) }.toList() }
            .distinctBy { it.line }

    /** As linhas com o índice original, sem os comentários HTML (`<!-- -->`) dos modelos e sem os blocos de código. */
    internal fun visible(lines: List<String>): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var inComment = false
        var inFence = false
        for ((index, raw) in lines.withIndex()) {
            if (!inComment && raw.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            var line = raw
            val kept = StringBuilder()
            while (line.isNotEmpty()) {
                if (inComment) {
                    val end = line.indexOf("-->")
                    if (end < 0) { line = ""; break }
                    line = line.substring(end + 3)
                    inComment = false
                } else {
                    val start = line.indexOf("<!--")
                    if (start < 0) { kept.append(line); break }
                    kept.append(line, 0, start)
                    line = line.substring(start + 4)
                    inComment = true
                }
            }
            if (kept.isNotBlank()) result += index to kept.toString()
        }
        return result
    }
}
