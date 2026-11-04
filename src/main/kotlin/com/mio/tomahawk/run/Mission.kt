package com.mio.tomahawk.run

import com.mio.tomahawk.speckit.SddStep
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Uma etapa do SDD a disparar: [arguments] é o texto do usuário (o `$ARGUMENTS` do comando); [feature], a pasta
 * da feature, relativa à raiz, ou nula ao designar uma nova.
 */
data class Mission(
    val step: SddStep,
    val arguments: String,
    val feature: String?,
    val mode: RunMode? = null,
)

enum class LogKind { INFO, TOOL, TEXT, ERROR, RESULT }

class LogLine(val at: Long, val kind: LogKind, val text: String)

/** O andamento da missão em voo, lido pelas barras de progresso e pela Telemetria. */
class MissionProgress(val mission: Mission, val invocation: String, val provider: AgentProvider) {
    val startedAt: Long = System.currentTimeMillis()

    @Volatile
    var step: String = "Preparando o lançamento…"
        internal set

    @Volatile
    var detail: String = ""
        internal set

    @Volatile
    var actions: Int = 0
        internal set

    @Volatile
    var filesTouched: Int = 0
        internal set

    @Volatile
    var model: String? = null
        internal set

    /** Os últimos passos antes do atual, do mais recente para o mais antigo. */
    @Volatile
    var trail: List<String> = emptyList()
        private set

    @Volatile
    var cancelled = false
        private set

    val log: MutableList<LogLine> = CopyOnWriteArrayList()

    internal fun advance(what: String) {
        trail = (listOf(step) + trail).filter { it != what }.take(TRAIL_SIZE)
        step = what
        actions++
    }

    internal fun log(kind: LogKind, text: String) {
        if (log.size >= MAX_LOG) log.removeAt(0)
        log += LogLine(System.currentTimeMillis(), kind, text)
    }

    fun cancel() {
        cancelled = true
    }

    private companion object {
        const val TRAIL_SIZE = 3
        const val MAX_LOG = 2_000
    }
}

/** O prompt que leva o CLI a rodar o comando do Spec Kit: curto e numa linha, com a entrada do usuário num arquivo. */
object MissionPrompt {

    fun build(
        mission: Mission,
        invocation: String,
        commandFile: String?,
        inputFile: String?,
        interactive: Boolean,
    ): String = buildString {
        append("Run the Spec Kit command $invocation")
        if (commandFile != null) append(", following exactly the instructions in $commandFile")
        append(". ")
        if (inputFile != null) {
            append("Its \$ARGUMENTS (the user input) are the full contents of the UTF-8 file $inputFile: read that file first and use its text verbatim as the arguments. ")
        } else {
            append("Run it with empty \$ARGUMENTS. ")
        }
        if (mission.step == SddStep.SPECIFY) {
            append("This designates a new feature: let the command create its directory under specs/ and record it as the active feature. ")
        } else if (mission.feature != null) {
            append("The active feature is ${mission.feature} (as recorded in .specify/feature.json). ")
        }
        if (!interactive) {
            append("This is a non-interactive run and nobody will answer questions: when the command would ask the user, pick the recommended option, ")
            append("and list each question with the choice you made in the final answer. ")
        }
        if (mission.step.readOnly) append("This step is read-only: do not modify any file. ")
        append("Finish with a short summary of what was generated or changed, with the artifact paths. Write the final answer in Brazilian Portuguese.")
    }
}

/** Onde ficam os registros e os pedidos das missões: `tomahawk/execucoes/` e `tomahawk/pedidos/`. */
object RunStore {
    const val DIR = "tomahawk"
    const val RUNS_DIR = "execucoes"
    const val INPUTS_DIR = "pedidos"
    private const val MAX_RUNS = 300
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun runsDir(root: Path): Path = root.resolve(DIR).resolve(RUNS_DIR)

    /** As execuções registradas, da mais recente para a mais antiga. */
    fun load(root: Path): List<RunRecord> {
        val dir = runsDir(root).takeIf(Files::isDirectory) ?: return emptyList()
        val files = Files.list(dir).use { list -> list.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }.toList() }
            .sortedByDescending { it.fileName.toString() }
            .take(MAX_RUNS)
        return files.mapNotNull { file ->
            val lines = runCatching { Files.newBufferedReader(file, StandardCharsets.UTF_8).use { r -> r.lineSequence().take(Transcript.HEADER_LINES).toList() } }
                .getOrNull() ?: return@mapNotNull null
            Transcript.parse(file, lines)
        }
    }

    fun newName(step: SddStep, now: LocalDateTime = LocalDateTime.now()): String = "${now.format(FILE_TIME)}-${step.command}.md"

    /** Grava a entrada do usuário em `tomahawk/pedidos/`; devolve o caminho relativo à raiz. */
    fun writeInput(root: Path, name: String, text: String): String {
        val dir = root.resolve(DIR).resolve(INPUTS_DIR)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8)
        return "$DIR/$INPUTS_DIR/$name"
    }

    fun writeRun(root: Path, name: String, content: String): Path {
        val dir = runsDir(root)
        Files.createDirectories(dir)
        return dir.resolve(name).also { Files.writeString(it, content, StandardCharsets.UTF_8) }
    }
}
