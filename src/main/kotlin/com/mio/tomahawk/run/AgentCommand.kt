package com.mio.tomahawk.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.Normalizer

/**
 * CLI de agente de IA que executa os comandos do Spec Kit. [integration] é a chave da integração no
 * `specify init --integration`; [homeDir], a pasta onde ela instala os comandos.
 */
enum class AgentProvider(
    val title: String,
    val executables: List<String>,
    val integration: String,
    val homeDir: String,
    val installHint: String,
) {
    CURSOR(
        "Cursor",
        listOf("agent", "cursor-agent"),
        "cursor-agent",
        ".cursor",
        "Instale o Cursor CLI (no PowerShell: irm 'https://cursor.com/install?win32=true' | iex) e rode agent login.",
    ),
    CLAUDE(
        "Claude Code",
        listOf("claude"),
        "claude",
        ".claude",
        "Instale o Claude Code (npm install -g @anthropic-ai/claude-code ou o instalador nativo) e entre uma vez com claude.",
    ),
}

/** Onde a missão roda: na aba, com o andamento monitorado, ou no terminal da IDE, onde dá para conversar com a IA. */
enum class RunMode(val label: String) {
    BACKGROUND("Na aba, no devcontainer"),
    TERMINAL("No terminal da IDE"),
    ASK("Perguntar a cada vez"),
}

/** O shell do terminal da IDE, para montar o comando com as aspas certas. */
enum class ShellKind {
    POWERSHELL, CMD, POSIX;

    companion object {
        fun of(shellPath: String?, windows: Boolean): ShellKind {
            val name = shellPath.orEmpty().replace('\\', '/').substringAfterLast('/').lowercase()
            return when {
                "pwsh" in name || "powershell" in name -> POWERSHELL
                name == "cmd" || name == "cmd.exe" -> CMD
                name.isEmpty() && windows -> POWERSHELL
                else -> POSIX
            }
        }
    }
}

object AgentCommand {

    /**
     * O comando sem janela, com a saída em `stream-json` para o Tomahawk acompanhar. Os comandos do Spec Kit rodam
     * scripts e escrevem arquivos, então o CLI recebe permissão para agir sem perguntar, como no despacho de
     * workflows do próprio Spec Kit; por isso, sem [readOnly], ele só roda no devcontainer ([DevContainer]). Com
     * [readOnly], para quando a IA só escreve um texto (o commit, o PR, a resposta a um comentário de outra pessoa),
     * o CLI só lê: o Cursor no modo `ask` e sem `--force`, o Claude Code só com as
     * ferramentas de leitura. Aí os argumentos extras que liberariam tudo ficam de fora. [settings] é o arquivo com as
     * regras de proteção do Claude Code ([Guardrails]); no Cursor elas ficam no `.cursor/cli.json` do projeto.
     */
    fun headless(
        provider: AgentProvider,
        executable: String,
        prompt: String,
        workspace: String,
        model: String?,
        extraArgs: List<String>,
        readOnly: Boolean = false,
        settings: String? = null,
    ): List<String> {
        val chosenModel = model?.trim()?.takeIf { it.isNotEmpty() }
        val text = oneLine(prompt)
        val extra = if (readOnly) withoutBypass(extraArgs) else extraArgs
        return when (provider) {
            AgentProvider.CURSOR -> buildList {
                add(executable)
                addAll(listOf("-p", "--output-format", "stream-json", "--trust"))
                addAll(if (readOnly) listOf("--mode", "ask") else listOf("--force"))
                addAll(listOf("--workspace", workspace))
                chosenModel?.let { addAll(listOf("--model", it)) }
                addAll(extra)
                add(text)
            }
            AgentProvider.CLAUDE -> buildList {
                addAll(listOf(executable, "-p", "--output-format", "stream-json", "--verbose"))
                addAll(if (readOnly) listOf("--allowedTools", READ_TOOLS, "--permission-mode", "dontAsk") else listOf("--permission-mode", "bypassPermissions"))
                settings?.let { addAll(listOf("--settings", it)) }
                chosenModel?.let { addAll(listOf("--model", it)) }
                addAll(extra)
                add(text)
            }
        }
    }

    /** As ferramentas do Claude Code no modo só leitura; `dontAsk` nega as outras sem perguntar. */
    const val READ_TOOLS = "Read,Grep,Glob"

    private val BYPASS_FLAGS = setOf("-f", "--force", "--yolo", "--dangerously-skip-permissions", "--allow-dangerously-skip-permissions")
    private val VALUED_FLAGS = setOf("--permission-mode", "--mode", "--sandbox", "--allowedTools", "--allowed-tools")

    /** [args] sem as opções que mudam as permissões, que o modo só leitura define. */
    fun withoutBypass(args: List<String>): List<String> = buildList {
        var skipValue = false
        for (arg in args) {
            when {
                skipValue -> skipValue = false
                arg in BYPASS_FLAGS -> Unit
                arg in VALUED_FLAGS -> skipValue = true
                VALUED_FLAGS.any { arg.startsWith("$it=") } -> Unit
                else -> add(arg)
            }
        }
    }

    /** [args] sem as opções que liberariam tudo sem perguntar, que o terminal não aceita. */
    fun withoutFullAccess(args: List<String>): List<String> = buildList {
        var skipValue = false
        for (arg in args) {
            when {
                skipValue -> skipValue = false
                arg in BYPASS_FLAGS -> Unit
                arg == "--permission-mode" -> skipValue = true
                arg.startsWith("--permission-mode=") -> Unit
                else -> add(arg)
            }
        }
    }

    /**
     * O comando de uma linha para o terminal da IDE, que roda nesta máquina e por isso nunca com permissão total.
     * Interativo, no modo agente: o usuário acompanha, aprova os comandos e responde; o Cursor sem `--force` e o
     * Claude Code em `acceptEdits`, que edita o projeto mas pergunta antes de rodar comandos. Sem [interactive] (o
     * teste), só leitura, como em [headless], com a resposta em [output]. [env] entra antes do comando, com a sintaxe
     * de [shell].
     */
    fun terminal(
        provider: AgentProvider,
        executable: String,
        prompt: String,
        model: String?,
        extraArgs: List<String>,
        shell: ShellKind,
        env: Map<String, String> = emptyMap(),
        interactive: Boolean = true,
        settings: String? = null,
        output: String? = null,
    ): String {
        val args = buildList {
            when {
                interactive -> if (provider == AgentProvider.CLAUDE) addAll(listOf("--permission-mode", "acceptEdits"))
                provider == AgentProvider.CURSOR -> addAll(listOf("-p", "--trust", "--mode", "ask", "--output-format", "text"))
                else -> addAll(listOf("-p", "--allowedTools", READ_TOOLS, "--permission-mode", "dontAsk"))
            }
            if (provider == AgentProvider.CLAUDE) settings?.let { addAll(listOf("--settings", it)) }
            model?.trim()?.takeIf { it.isNotEmpty() }?.let { addAll(listOf("--model", it)) }
            addAll(if (interactive) withoutFullAccess(extraArgs) else withoutBypass(extraArgs))
            add(oneLine(prompt))
        }
        val quoted = (listOf(executable) + args).map { quote(it, shell) }
        val command = (if (shell == ShellKind.POWERSHELL) "& " + quoted.joinToString(" ") else quoted.joinToString(" ")) +
            output?.let { file ->
                when (shell) {
                    ShellKind.POWERSHELL -> " | Out-File -Encoding utf8 ${quote(file, shell)}"
                    else -> " > ${quote(file, shell)}"
                }
            }.orEmpty()
        return withEnv(shell, env, command)
    }

    /** [command] com [env] definido antes, na sintaxe de [shell]. */
    fun withEnv(shell: ShellKind, env: Map<String, String>, command: String): String =
        env.entries.joinToString("") { (key, value) ->
            when (shell) {
                ShellKind.POWERSHELL -> "\$env:$key=${quote(value, shell)}; "
                ShellKind.CMD -> "set \"$key=${value.replace("\"", "")}\" && "
                ShellKind.POSIX -> "$key=${quote(value, shell)} "
            }
        } + command

    /** [inner], um comando POSIX, no shell de login da distribuição [distro] do WSL, em [workDir], com as aspas de [shell]. */
    fun wrapWsl(distro: String, workDir: String, inner: String, shell: ShellKind): String {
        val quoted = listOf("wsl.exe", "-d", distro, "--cd", workDir, "--", "bash", "-lic", inner).map { quote(it, shell) }
        return if (shell == ShellKind.POWERSHELL) "& " + quoted.joinToString(" ") else quoted.joinToString(" ")
    }

    fun quote(arg: String, shell: ShellKind): String = when (shell) {
        ShellKind.POWERSHELL -> "'" + arg.replace("'", "''") + "'"
        ShellKind.CMD -> "\"" + arg.replace("\"", "") + "\""
        ShellKind.POSIX -> "'" + arg.replace("'", "'\\''") + "'"
    }

    /** Os argumentos extras das configurações, separados por espaço, com aspas simples ou duplas agrupando. */
    fun splitArgs(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return Regex(""""([^"]*)"|'([^']*)'|(\S+)""").findAll(text).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toList()
    }

    /**
     * Quebras de linha e os caracteres especiais do cmd.exe viram espaço, e os acentos saem: no Windows o CLI do
     * Cursor é um `.cmd` e o prompt passa pela página de código do console. O texto do usuário vai num arquivo UTF-8.
     */
    fun oneLine(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[\r\n]+"""), " ").replace(Regex("""[&|<>^%"]"""), " ").replace(Regex(" {2,}"), " ").trim()
}

/** Um evento do `stream-json` dos CLIs: uma linha de JSON por evento. */
sealed interface AgentEvent {
    data class Init(val model: String?) : AgentEvent
    data class Text(val text: String) : AgentEvent
    /** [kind] é o tipo da ferramenta em minúsculas, como `read`, `write`, `edit` ou `shell`. */
    data class Tool(val kind: String, val target: String?) : AgentEvent
    data class Result(val text: String, val error: Boolean, val costUsd: Double?) : AgentEvent
    /** Linha que não é JSON ou de um formato desconhecido: entra no registro como veio. */
    data class Other(val line: String) : AgentEvent
}

/** Lê o `stream-json` do Cursor CLI e do Claude Code, que têm formatos parecidos mas não iguais. */
object AgentEvents {

    fun parse(line: String): List<AgentEvent> {
        val text = line.trim()
        if (text.isEmpty()) return emptyList()
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return listOf(AgentEvent.Other(text))
        return when (json.str("type")) {
            "system" -> if (json.str("subtype") == null || json.str("subtype") == "init") listOf(AgentEvent.Init(json.str("model"))) else emptyList()
            "assistant" -> assistant(json)
            "tool_call" -> if (json.str("subtype") == "started") listOfNotNull(cursorTool(json)) else emptyList()
            "result" -> listOf(
                AgentEvent.Result(
                    json.str("result").orEmpty(),
                    json.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean == true || json.str("subtype")?.startsWith("error") == true,
                    json.get("total_cost_usd")?.takeIf { it.isJsonPrimitive }?.asDouble,
                ),
            )
            "user", "thinking", "stream_event", "rate_limit_event" -> emptyList()
            else -> listOf(AgentEvent.Other(text))
        }
    }

    /** Os blocos da mensagem: texto, e no Claude também as ferramentas (`tool_use`). */
    private fun assistant(json: JsonObject): List<AgentEvent> {
        val content = json.getAsJsonObject("message")?.get("content") ?: return emptyList()
        if (content.isJsonPrimitive) return listOf(AgentEvent.Text(content.asString)).filter { it.text.isNotBlank() }
        if (!content.isJsonArray) return emptyList()
        return (content as JsonArray).mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            when (obj.str("type")) {
                "text" -> obj.str("text")?.takeIf { it.isNotBlank() }?.let(AgentEvent::Text)
                "tool_use" -> claudeTool(obj)
                else -> null
            }
        }
    }

    private fun claudeTool(block: JsonObject): AgentEvent.Tool {
        val name = block.str("name").orEmpty()
        val input = block.getAsJsonObject("input")
        val target = input?.let { it.str("file_path") ?: it.str("path") ?: it.str("pattern") ?: it.str("command") ?: it.str("description") }
        val kind = when (name) {
            "Read" -> "read"
            "Write" -> "write"
            "Edit", "MultiEdit", "NotebookEdit" -> "edit"
            "Bash", "PowerShell" -> "shell"
            "Grep" -> "grep"
            "Glob" -> "glob"
            "LS" -> "ls"
            "TodoWrite" -> "todo"
            else -> name.lowercase()
        }
        return AgentEvent.Tool(kind, target)
    }

    /** Ferramentas como `readToolCall`, `editToolCall` ou `shellToolCall`, com o caminho em `args.path`. */
    private fun cursorTool(json: JsonObject): AgentEvent.Tool? {
        val call = json.getAsJsonObject("tool_call") ?: return null
        val (name, body) = call.entrySet().firstOrNull { it.key.endsWith("ToolCall") } ?: call.entrySet().firstOrNull { it.value.isJsonObject }
            ?: return null
        val kind = name.removeSuffix("ToolCall").ifEmpty { name }.lowercase()
        val args = (body as? JsonObject)?.getAsJsonObject("args")
        val target = args?.let { it.str("path") ?: it.str("file_path") ?: it.str("filePath") ?: it.str("pattern") ?: it.str("command") }
        return AgentEvent.Tool(if (kind == "multiedit") "edit" else kind, target)
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
