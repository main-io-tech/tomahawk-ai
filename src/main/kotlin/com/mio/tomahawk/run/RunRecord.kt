package com.mio.tomahawk.run

import com.mio.tomahawk.speckit.SddStep
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RunStatus(val label: String) {
    OK("concluída"),
    FAILED("falhou"),
    CANCELLED("cancelada"),
    TERMINAL("no terminal"),
}

/** O resultado que o Tomahawk lê da resposta de etapas que não deixam artefato, como a convergência. */
enum class Verdict(val label: String) {
    CONVERGED("Convergido"),
    TASKS_APPENDED("Tarefas acrescentadas"),
    ISSUES("Problemas encontrados"),
    CLEAN("Sem problemas"),
    ;

    companion object {
        private val CONVERGED_TEXT = Regex("""\bconverged\b""", RegexOption.IGNORE_CASE)
        private val NOT_CONVERGED = Regex("""\bnot\s+converged\b|\bnao\s+convergi""", RegexOption.IGNORE_CASE)
        private val APPENDED = Regex("""tasks?\s+appended|appended\s+\d*\s*(new\s+)?tasks?|tarefas?\s+acrescentad|tarefas?\s+adicionad""", RegexOption.IGNORE_CASE)
        private val SEVERE = Regex("""\b(CRITICAL|HIGH)\b""")
        private val NO_ISSUES = Regex("""no (critical )?issues|0 critical|nenhum problema|sem problemas""", RegexOption.IGNORE_CASE)

        fun of(step: SddStep, answer: String): Verdict? = when (step) {
            SddStep.CONVERGE -> when {
                APPENDED.containsMatchIn(answer) || NOT_CONVERGED.containsMatchIn(answer) -> TASKS_APPENDED
                CONVERGED_TEXT.containsMatchIn(answer) || "convergido" in answer.lowercase() -> CONVERGED
                else -> null
            }
            SddStep.ANALYZE -> when {
                NO_ISSUES.containsMatchIn(answer) -> CLEAN
                SEVERE.containsMatchIn(answer) -> ISSUES
                else -> null
            }
            else -> null
        }

        fun byName(name: String?): Verdict? = entries.firstOrNull { it.name == name?.trim() || it.label == name?.trim() }
    }
}

/** Uma execução registrada em `tomahawk/execucoes/`, lida do cabeçalho do registro. */
data class RunRecord(
    val file: Path,
    val step: SddStep?,
    val feature: String?,
    val status: RunStatus,
    val error: String?,
    val verdict: Verdict?,
    /** Em milissegundos desde a época, para comparar com a modificação dos artefatos. */
    val startedAt: Long,
    val durationSeconds: Long,
    val provider: String?,
    val filesChanged: Int,
    val title: String,
) {
    val ok: Boolean get() = status == RunStatus.OK
}

/** O registro de uma execução: o pedido, o que a IA fez e a resposta, para conferir e monitorar depois. */
object Transcript {

    private val FIELD = Regex("""^- \*\*(.+?):\*\* (.*)$""")
    private val STAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val HUMAN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    const val HEADER_LINES = 16

    class Entry(
        val step: SddStep,
        val invocation: String,
        val feature: String?,
        val status: RunStatus,
        val error: String?,
        val verdict: Verdict?,
        val startedAt: Long,
        val durationSeconds: Long,
        val provider: String,
        val model: String?,
        val arguments: String,
        val prompt: String,
        val files: Collection<String>,
        val steps: List<String>,
        val answer: String,
        val stderr: String,
        val command: List<String>,
        val costUsd: Double? = null,
    )

    fun render(entry: Entry): String = buildString {
        val started = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.startedAt), ZoneId.systemDefault())
        appendLine("# Tomahawk: ${entry.step.codename} (${entry.invocation})")
        appendLine()
        appendLine("- **Etapa:** ${entry.step.command}")
        appendLine("- **Feature:** ${entry.feature ?: "-"}")
        appendLine("- **Início:** ${started.format(STAMP)}")
        appendLine("- **Duração:** ${entry.durationSeconds} s")
        appendLine("- **CLI:** ${entry.provider}${entry.model?.let { " · modelo $it" }.orEmpty()}")
        appendLine("- **Situação:** ${entry.status.label}${entry.error?.let { ": ${it.lines().first()}" }.orEmpty()}")
        entry.verdict?.let { appendLine("- **Veredito:** ${it.label}") }
        appendLine("- **Arquivos alterados:** ${entry.files.size}")
        entry.costUsd?.let { appendLine("- **Custo:** US$ ${"%.4f".format(java.util.Locale.ROOT, it)}") }
        appendLine()
        appendLine("Iniciada em ${started.format(HUMAN)}.")
        appendLine()
        appendLine("## Entrada")
        appendLine()
        appendLine(entry.arguments.ifBlank { "_Sem argumentos._" })
        if (entry.files.isNotEmpty()) {
            appendLine()
            appendLine("## Arquivos alterados")
            appendLine()
            entry.files.forEach { appendLine("- `$it`") }
        }
        if (entry.steps.isNotEmpty()) {
            appendLine()
            appendLine("## Passos")
            appendLine()
            entry.steps.forEach { appendLine("1. $it") }
        }
        appendLine()
        appendLine("## Resposta")
        appendLine()
        appendLine(entry.answer.ifBlank { "_Sem resposta._" })
        if (entry.stderr.isNotBlank() && entry.status == RunStatus.FAILED) {
            appendLine()
            appendLine("## Saída de erro do CLI")
            appendLine()
            appendLine("```text")
            appendLine(entry.stderr.trimEnd().lines().takeLast(40).joinToString("\n"))
            appendLine("```")
        }
        appendLine()
        appendLine("## Prompt enviado")
        appendLine()
        appendLine("```text")
        appendLine(entry.prompt)
        appendLine("```")
        appendLine()
        appendLine("<!-- comando: ${entry.command.dropLast(1).joinToString(" ").replace("--", "- -")} -->")
    }

    /** O cabeçalho de um registro; nulo se não for um registro do Tomahawk. */
    fun parse(file: Path, lines: List<String>): RunRecord? {
        val title = lines.firstOrNull { it.startsWith("# Tomahawk: ") }?.removePrefix("# Tomahawk: ")?.trim() ?: return null
        val fields = lines.mapNotNull { FIELD.matchEntire(it)?.let { m -> m.groupValues[1] to m.groupValues[2].trim() } }.toMap()
        val situation = fields["Situação"].orEmpty()
        val status = RunStatus.entries.firstOrNull { situation.startsWith(it.label) } ?: RunStatus.FAILED
        val started = fields["Início"]?.let { runCatching { LocalDateTime.parse(it, STAMP) }.getOrNull() }
            ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L
        return RunRecord(
            file = file,
            step = SddStep.byCommand(fields["Etapa"]),
            feature = fields["Feature"]?.takeIf { it != "-" },
            status = status,
            error = situation.substringAfter(": ", "").ifBlank { null }.takeIf { status == RunStatus.FAILED },
            verdict = Verdict.byName(fields["Veredito"]),
            startedAt = started,
            durationSeconds = fields["Duração"]?.removeSuffix(" s")?.toLongOrNull() ?: 0L,
            provider = fields["CLI"],
            filesChanged = fields["Arquivos alterados"]?.toIntOrNull() ?: 0,
            title = title,
        )
    }
}
