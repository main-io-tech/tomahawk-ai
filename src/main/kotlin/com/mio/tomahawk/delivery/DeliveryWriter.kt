package com.mio.tomahawk.delivery

import com.intellij.execution.util.ExecUtil
import com.mio.tomahawk.run.AgentCommand
import com.mio.tomahawk.run.AgentEvent
import com.mio.tomahawk.run.AgentEvents
import com.mio.tomahawk.run.AgentHost
import com.mio.tomahawk.run.AgentVerifier
import com.mio.tomahawk.run.TomahawkSettings
import java.nio.file.Path

/** O que a IA escreve na entrega. */
enum class WriteTarget(val label: String) {
    COMMIT("a mensagem do commit"),
    PULL_REQUEST("o título e a descrição do PR"),
}

/**
 * A mensagem do commit e o texto do PR escritos pela IA configurada no Arsenal, a partir do diff que o Git da IDE
 * grava num arquivo: o prompt do CLI vai numa linha só, sem acentos.
 */
object DeliveryWriter {

    private const val TIMEOUT_MS = 300_000
    private val FENCE = Regex("""^```[\w-]*\s*\n([\s\S]*?)\n?```$""")
    private val TITLE_PREFIX = Regex("""^(?:#+\s*|\*\*)?(?:T[ií]tulo|Title)\s*:\s*\**\s*""", RegexOption.IGNORE_CASE)

    fun commitPrompt(contextFile: String): String =
        "Write a git commit message for the uncommitted changes of this repository. " +
            "The context (git status, diff stat and diff, truncated if large) is in the UTF-8 file $contextFile: read it first, " +
            "and read the new untracked files it lists if you need their content. " +
            "Do not modify any file and do not run commands that change the repository. " +
            "Answer with only the commit message, with no preamble and no code fences: a first line in the Conventional Commits format " +
            "(type: summary, at most 72 characters), an empty line, and then a body with short bullet points explaining what changed and why. " +
            "Write the summary and the body in Brazilian Portuguese."

    fun pullRequestPrompt(contextFile: String, branch: String, base: String, feature: String?): String =
        "Write the title and the description of a GitHub pull request from the branch $branch into $base. " +
            "The context (the commits and the diff of the branch against the base, plus the uncommitted changes that will be committed, " +
            "truncated if large) is in the UTF-8 file $contextFile: read it first. " +
            (feature?.let { "The changes implement the Spec Kit feature in $it: read its spec.md and tasks.md for the intent. " }.orEmpty()) +
            "Do not modify any file and do not run commands that change the repository. " +
            "Answer with only the pull request, with no preamble and no code fences: the first line is the title in the Conventional Commits format " +
            "(at most 72 characters), then an empty line, then the description in Markdown with the sections Resumo, Alteracoes and Como testar. " +
            "Write in Brazilian Portuguese."

    fun replyPrompt(contextFile: String, me: String, number: Int, branch: String, hasNotes: Boolean): String =
        "You are @$me, the author of the pull request #$number of this repository, and another developer commented on it. " +
            "The pull request, the whole conversation and the code under discussion are in the UTF-8 file $contextFile: read it first. " +
            "The branch of the pull request is $branch and the local checkout may be another branch: read the files with git show $branch:path " +
            "or in the working tree when you need more context. " +
            "Do not modify any file and do not run commands that change the repository. " +
            "Write the reply of the author to the last comment of the conversation: answer the questions, explain the decisions with arguments, " +
            "agree and say what will be changed when the reviewer is right, or politely disagree and explain why. " +
            (if (hasNotes) "Base the reply on the notes of the author at the end of the file. " else "") +
            "Answer with only the reply in Markdown, with no preamble and no code fences around the whole answer, short and direct, " +
            "in the language of the comment (Brazilian Portuguese when unsure)."

    /** A resposta sem a cerca de código que alguns modelos põem em volta. */
    fun clean(text: String): String {
        val trimmed = text.trim().replace("\r\n", "\n")
        return FENCE.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
    }

    /** O título (a primeira linha com texto, sem `Título:` nem `#`) e a descrição (o resto). */
    fun pullRequest(text: String): Pair<String, String> {
        val lines = clean(text).lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return "" to ""
        val title = lines[first].trim().replace(TITLE_PREFIX, "").trim().trimEnd('*').trim()
        return title to lines.drop(first + 1).joinToString("\n").trim()
    }

    /** Fora da EDT. O texto final da IA, ou exceção com o motivo. */
    fun ask(root: Path, prompt: String): String {
        val provider = TomahawkSettings.provider
        val launch = TomahawkSettings.launch()
            ?: throw DeliveryException(
                if (TomahawkSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL no Arsenal." else "O ${provider.title} CLI não foi encontrado. Configure a IA no Arsenal.",
            )
        if (!TomahawkSettings.ready) throw DeliveryException("Teste a integração com a IA no Arsenal antes de gerar o texto.")
        val workDir = root.toString()
        val command = AgentCommand.headless(
            provider, launch.executable, prompt, launch.workspace(workDir), TomahawkSettings.model, AgentCommand.splitArgs(TomahawkSettings.extraArgs),
        )
        val output = ExecUtil.execAndGetOutput(launch.process(command, workDir, AgentVerifier.apiKeyEnv(provider)), TIMEOUT_MS)
        val result = output.stdoutLines.flatMap(AgentEvents::parse).filterIsInstance<AgentEvent.Result>().lastOrNull()
        return when {
            output.isTimeout -> throw DeliveryException("A IA não respondeu em ${TIMEOUT_MS / 60_000} minutos.")
            result == null || output.exitCode != 0 -> throw DeliveryException(
                AgentVerifier.explain(
                    provider,
                    result?.text?.takeIf { it.isNotBlank() } ?: output.stderrLines.lastOrNull { it.isNotBlank() } ?: "O CLI terminou com o código ${output.exitCode}.",
                ),
            )
            result.error -> throw DeliveryException(AgentVerifier.explain(provider, result.text.ifBlank { "A IA informou um erro." }))
            else -> clean(result.text).ifBlank { throw DeliveryException("A IA respondeu sem texto.") }
        }
    }
}
