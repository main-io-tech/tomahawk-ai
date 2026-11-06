package com.mio.tomahawk.run

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * O devcontainer das missões na aba, a única forma de a IA rodar sem perguntar. O container só enxerga a pasta do
 * projeto, com o `.git/config` e os hooks só leitura, porque o Git desta máquina roda o que estiver neles. A
 * configuração é conferida antes de subir ([audit]) e o container de pé, pelo `docker inspect` ([isolation]): nada
 * de modo privilegiado, de rede, processos ou dispositivos da máquina, nem de pasta da máquina fora do projeto.
 */
object DevContainer {

    const val CONFIG = ".devcontainer/tomahawk/devcontainer.json"
    const val CLI_HINT = "Instale o Docker Desktop e o CLI de devcontainers (npm install -g @devcontainers/cli)."
    const val HOME = "/home/vscode"

    private const val UP_TIMEOUT_MINUTES = 30L
    private const val TIMEOUT_MS = 60_000
    private const val WORKSPACE = "\${localWorkspaceFolder}"
    private val DOCKER_FEATURES = listOf("docker-outside-of-docker", "docker-in-docker", "docker-from-docker")
    private val HOST_ARGS = Regex("""^(--privileged|--(network|net|pid|ipc|uts|userns|cgroupns)(=host)?|--cap-add.*|--security-opt.*|--device.*|-v|--volume.*|--mount.*)$""")
    private val NAMESPACES = listOf("NetworkMode" to "a rede", "PidMode" to "os processos", "IpcMode" to "a memória compartilhada", "UTSMode" to "o nome de rede", "UsernsMode" to "os usuários")

    /** O container de pé: [folder] é a pasta do projeto lá dentro. */
    class Session internal constructor(
        val root: Path,
        val config: Path,
        val containerId: String,
        val folder: String,
        private val cli: String,
        private val docker: String,
    ) {
        /** Um caminho desta máquina como a IA o enxerga no container. */
        fun path(path: String): String = containerPath(root.toString(), folder, path)

        /** [command] no container, na pasta do projeto, com [env] e o ambiente do próprio container. */
        fun exec(command: List<String>, env: Map<String, String>): GeneralCommandLine =
            commandLine(cli, root, listOf("exec", "--workspace-folder", root.toString(), "--config", config.toString(), "--docker-path", docker) +
                env.flatMap { (key, value) -> listOf("--remote-env", "$key=$value") } + command)

        /** Derruba o container, e com ele a IA: matar o `devcontainer exec` não para o processo lá dentro. */
        fun stop() {
            runCatching { ExecUtil.execAndGetOutput(GeneralCommandLine(docker, "kill", containerId).withCharset(StandardCharsets.UTF_8), TIMEOUT_MS) }
        }
    }

    fun config(root: Path): Path = root.resolve(CONFIG)

    fun cli(): Path? {
        val names = if (SystemInfo.isWindows) listOf("devcontainer.cmd", "devcontainer.exe", "devcontainer") else listOf("devcontainer")
        names.firstNotNullOfOrNull { PathEnvironmentVariableUtil.findInPath(it) }?.let { return it.toPath() }
        val dirs = listOfNotNull(System.getenv("APPDATA")?.let { File(it, "npm") }, File(System.getProperty("user.home"), ".local/bin"), File("/usr/local/bin"), File("/opt/homebrew/bin"))
        return dirs.flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile }?.toPath()
    }

    private fun docker(): String =
        PathEnvironmentVariableUtil.findInPath(if (SystemInfo.isWindows) "docker.exe" else "docker")?.path
            ?: listOf("C:/Program Files/Docker/Docker/resources/bin/docker.exe", "/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/usr/bin/docker").firstOrNull { File(it).isFile }
            ?: "docker"

    /** Por que a missão na aba não pode sair agora, ou nulo. Só lê o disco e o PATH, sem subir nada. */
    fun problem(root: Path, provider: AgentProvider): String? {
        if (cli() == null) return "O CLI de devcontainers não foi encontrado. $CLI_HINT"
        val file = config(root)
        if (!Files.isRegularFile(file)) return "O projeto ainda não tem o devcontainer do Tomahawk ($CONFIG)."
        audit(runCatching { Files.readString(file) }.getOrDefault("")).firstOrNull()?.let { return "O devcontainer não isola a máquina: $it." }
        if (provider == AgentProvider.CURSOR && !TomahawkSettings.hasCursorApiKey) {
            return "No devcontainer, o Cursor CLI entra pela chave de API: informe a chave no Arsenal."
        }
        return null
    }

    /** As credenciais do CLI que entram no container: a chave do Cursor, ou as do Claude Code definidas nesta máquina. */
    fun credentials(provider: AgentProvider): Map<String, String> = when (provider) {
        AgentProvider.CURSOR -> AgentVerifier.apiKeyEnv(provider)
        AgentProvider.CLAUDE -> listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN")
            .mapNotNull { key -> System.getenv(key)?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap()
    }

    /** Grava o devcontainer padrão do Tomahawk, se o projeto ainda não tiver um, e devolve o arquivo. */
    fun create(root: Path): Path {
        val file = config(root)
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.parent)
            Files.writeString(file, template(git = Files.isDirectory(root.resolve(".git"))))
        }
        return file
    }

    /** Ubuntu com Node (para o Claude Code), PowerShell (para os scripts do Spec Kit) e os dois CLIs de IA. */
    fun template(git: Boolean): String {
        val mounts = buildList {
            if (git) {
                add("source=$WORKSPACE/.git/config,target=\${containerWorkspaceFolder}/.git/config,type=bind,readonly")
                add("source=$WORKSPACE/.git/hooks,target=\${containerWorkspaceFolder}/.git/hooks,type=bind,readonly")
            }
            add("source=tomahawk-claude-\${devcontainerId},target=$HOME/.claude,type=volume")
        }.joinToString(",\n") { "    \"$it\"" }
        val setup = listOf(
            "sudo chown -R vscode:vscode $HOME/.claude",
            "git config --global --add safe.directory '*'",
            "curl -fsSL https://cursor.com/install | bash",
            "npm install -g @anthropic-ai/claude-code",
        ).joinToString(" && ")
        return """
            |// O devcontainer das missões na aba do Tomahawk IA: só aqui a IA roda sem perguntar.
            |// O container só enxerga a pasta do projeto; o .git/config e os hooks ficam só leitura, porque o Git da sua
            |// máquina roda o que estiver neles. Acrescente as ferramentas do projeto em "features", se a IA precisar delas.
            |// O Tomahawk recusa initializeCommand, modo privilegiado, capacidades extras, rede ou processos da máquina e
            |// pastas da máquina fora do projeto (como o socket do Docker).
            |{
            |  "name": "Tomahawk IA",
            |  "image": "mcr.microsoft.com/devcontainers/base:ubuntu",
            |  "features": {
            |    "ghcr.io/devcontainers/features/node:1": {},
            |    "ghcr.io/devcontainers/features/powershell:1": {}
            |  },
            |  "remoteUser": "vscode",
            |  "remoteEnv": { "PATH": "${'$'}{containerEnv:PATH}:$HOME/.local/bin" },
            |  "mounts": [
            |$mounts
            |  ],
            |  "postCreateCommand": "$setup"
            |}
            |""".trimMargin()
    }

    /** O que, na configuração, deixa o container alcançar a máquina; vazio se nada. */
    fun audit(text: String): List<String> {
        val json = runCatching { JsonParser.parseString(stripJsonc(text)) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return listOf("o devcontainer.json não é um JSON válido")
        val problems = mutableListOf<String>()
        if (json.has("initializeCommand")) problems += "o initializeCommand roda na sua máquina, fora do container"
        if (json.has("dockerComposeFile")) problems += "o Tomahawk não confere configurações com Docker Compose; use image ou build"
        if (json.get("privileged").bool()) problems += "o modo privilegiado dá ao container acesso à máquina"
        if (json.get("capAdd").strings().isNotEmpty()) problems += "o capAdd dá ao container poderes da máquina"
        if (json.get("securityOpt").strings().isNotEmpty()) problems += "o securityOpt desliga isolamentos do container"
        json.get("runArgs").strings().filter { HOST_ARGS.matches(it.trim()) }.forEach { problems += "o runArgs tem $it" }
        json.getAsJsonObject("features")?.keySet()?.filter { key -> DOCKER_FEATURES.any { it in key } }?.forEach {
            problems += "a feature $it dá à IA o Docker da máquina"
        }
        mounts(json.get("mounts")).filter { it.bind && !inWorkspace(it.source) }.forEach { problems += "a montagem de ${it.source} expõe uma pasta da máquina" }
        json.get("workspaceMount")?.let(::mounts)?.firstOrNull()?.takeIf { it.bind && !inWorkspace(it.source) }?.let {
            problems += "o workspaceMount monta ${it.source} em vez da pasta do projeto"
        }
        return problems
    }

    /**
     * O que, no container de pé (a saída do `docker inspect`), deixa ele alcançar a máquina; vazio se nada. [root] é
     * a pasta do projeto nesta máquina; com [git], o `.git/config` e os hooks precisam estar montados só leitura.
     */
    fun isolation(inspect: String, root: String, git: Boolean): List<String> {
        val parsed = runCatching { JsonParser.parseString(inspect) }.getOrNull()
        val container = (if (parsed?.isJsonArray == true) parsed.asJsonArray.firstOrNull() else parsed)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return listOf("o docker inspect não descreveu o container")
        val host = container.getAsJsonObject("HostConfig") ?: JsonObject()
        val problems = mutableListOf<String>()
        if (host.get("Privileged").bool()) problems += "o container roda em modo privilegiado"
        NAMESPACES.forEach { (key, label) -> if (host.get(key).str() == "host") problems += "o container usa $label da máquina" }
        host.get("CapAdd").strings().takeIf { it.isNotEmpty() }?.let { problems += "o container ganhou capacidades extras (${it.joinToString()})" }
        if ((host.get("Devices") as? JsonArray)?.size()?.let { it > 0 } == true) problems += "o container acessa dispositivos da máquina"
        host.get("SecurityOpt").strings().filter { "unconfined" in it || "disable" in it }.forEach { problems += "o container desliga um isolamento ($it)" }
        val mounts = (container.get("Mounts") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val binds = mounts.filter { it.get("Type").str() == "bind" }
        binds.mapNotNull { it.get("Source").str() }.filterNot { inside(it, root) }.forEach { problems += "a pasta $it da máquina está montada no container" }
        if (git) {
            listOf("config", "hooks").forEach { name ->
                val locked = binds.any { it.get("Destination").str()?.trimEnd('/')?.endsWith("/.git/$name") == true && it.get("RW")?.bool() == false }
                if (!locked) problems += "o .git/$name precisa estar montado só leitura, porque o Git da sua máquina roda o que estiver nele"
            }
        }
        return problems
    }

    /**
     * Sobe o container (ou reaproveita o que já está de pé) e confere o isolamento; fora da EDT. [log] recebe o
     * andamento; com [cancelled], desiste. Falha com a explicação quando não sobe ou não isola.
     */
    fun up(root: Path, log: (String) -> Unit, cancelled: () -> Boolean): Session {
        val cli = cli()?.toString() ?: throw IllegalStateException("O CLI de devcontainers não foi encontrado. $CLI_HINT")
        val config = config(root)
        if (!Files.isRegularFile(config)) throw IllegalStateException("O projeto não tem o devcontainer do Tomahawk ($CONFIG).")
        audit(Files.readString(config)).takeIf { it.isNotEmpty() }?.let { throw IllegalStateException("O devcontainer não isola a máquina: ${it.joinToString("; ")}.") }
        val git = Files.isDirectory(root.resolve(".git"))
        if (git) Files.createDirectories(root.resolve(".git").resolve("hooks"))
        val docker = docker()
        val process = commandLine(cli, root, listOf("up", "--workspace-folder", root.toString(), "--config", config.toString(), "--docker-path", docker)).createProcess()
        process.outputStream.close()
        val stdout = StringBuffer()
        val lastError = AtomicReference("")
        val out = Thread { process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { stdout.appendLine(it) } }.apply { isDaemon = true; start() }
        val err = Thread {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                line.trim().takeIf { it.isNotEmpty() }?.let { lastError.set(it); log(it.take(200)) }
            }
        }.apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(UP_TIMEOUT_MINUTES)
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            val why = when {
                cancelled() -> "A missão foi abortada enquanto o devcontainer subia."
                System.nanoTime() > deadline -> "O devcontainer não subiu em $UP_TIMEOUT_MINUTES minutos."
                else -> null
            } ?: continue
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw IllegalStateException(why)
        }
        out.join(2_000)
        err.join(1_000)
        val result = stdout.toString().lines().asReversed().firstNotNullOfOrNull { line ->
            runCatching { JsonParser.parseString(line.trim()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.has("outcome") }
        }
        val id = result?.get("containerId").str()
        if (result?.get("outcome").str() != "success" || id == null) {
            val why = result?.get("message").str() ?: result?.get("description").str() ?: lastError.get().ifEmpty { "o CLI terminou com o código ${process.exitValue()}" }
            throw IllegalStateException("O devcontainer não subiu: $why. Confira se o Docker está rodando.")
        }
        val folder = result?.get("remoteWorkspaceFolder").str() ?: "/workspaces/${root.fileName}"
        val session = Session(root, config, id, folder, cli, docker)
        val inspect = runCatching { ExecUtil.execAndGetOutput(GeneralCommandLine(docker, "inspect", id).withCharset(StandardCharsets.UTF_8), TIMEOUT_MS) }.getOrNull()
        if (inspect == null || inspect.exitCode != 0) {
            session.stop()
            throw IllegalStateException("Não deu para conferir o container pelo docker inspect: ${inspect?.stderr?.trim()?.take(200).orEmpty()}")
        }
        val problems = isolation(inspect.stdout, root.toString(), git)
        if (problems.isNotEmpty()) {
            session.stop()
            throw IllegalStateException("O container não isola a máquina e foi parado: ${problems.joinToString("; ")}.")
        }
        return session
    }

    /** [path] desta máquina dentro de [root] vira o caminho em [folder], no container; os outros ficam como estão. */
    fun containerPath(root: String, folder: String, path: String): String {
        val r = root.replace('\\', '/').trimEnd('/')
        val p = path.replace('\\', '/')
        val windows = r.length > 1 && r[1] == ':'
        val (a, b) = if (windows) p.lowercase() to r.lowercase() else p to r
        return when {
            a == b -> folder
            a.startsWith("$b/") -> folder.trimEnd('/') + p.substring(r.length)
            else -> path
        }
    }

    /** A pasta [source] do Docker está dentro de [root]; entende `C:\`, `/run/desktop/mnt/host/c/`, `/host_mnt/` e `/mnt/c/`. */
    fun inside(source: String, root: String): Boolean {
        val s = hostPath(source)
        val r = hostPath(root)
        val windows = Regex("^/[a-z]/", RegexOption.IGNORE_CASE).containsMatchIn("$r/")
        val (a, b) = if (windows) s.lowercase() to r.lowercase() else s to r
        return a == b || a.startsWith("$b/")
    }

    private fun hostPath(path: String): String {
        var p = path.trim().replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/')
        Regex("^([A-Za-z]):").find(p)?.let { p = "/" + it.groupValues[1].lowercase() + p.substring(2) }
        listOf("/run/desktop/mnt/host", "/host_mnt", "/mnt/host").firstOrNull { p.startsWith("$it/") }?.let { p = p.removePrefix(it) }
        Regex("^/mnt/([A-Za-z])/").find(p)?.let { p = "/" + it.groupValues[1].lowercase() + p.substring(6) }
        return p
    }

    private class Mount(val bind: Boolean, val source: String)

    /** As montagens do `devcontainer.json`, em texto (`source=…,target=…,type=bind`) ou em objeto. */
    private fun mounts(element: JsonElement?): List<Mount> {
        val items = when {
            element == null || element.isJsonNull -> return emptyList()
            element.isJsonArray -> element.asJsonArray.toList()
            else -> listOf(element)
        }
        return items.mapNotNull { item ->
            val fields = when {
                item.isJsonObject -> item.asJsonObject.entrySet().associate { (key, value) -> key.lowercase() to value.str().orEmpty() }
                item.isJsonPrimitive -> item.asString.split(',').associate { part -> part.substringBefore('=').trim().lowercase() to part.substringAfter('=', "").trim() }
                else -> return@mapNotNull null
            }
            val source = fields["source"] ?: fields["src"] ?: return@mapNotNull null
            Mount((fields["type"] ?: "bind") == "bind", source)
        }
    }

    private fun inWorkspace(source: String) = source.startsWith(WORKSPACE) && ".." !in source.replace('\\', '/').split('/')

    /** O JSON com comentários e vírgulas sobrando do `devcontainer.json` como JSON puro. */
    fun stripJsonc(text: String): String {
        val out = StringBuilder()
        var i = 0
        var string = false
        while (i < text.length) {
            val c = text[i]
            when {
                string -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) out.append(text[++i]) else if (c == '"') string = false
                    i++
                }
                c == '"' -> { string = true; out.append(c); i++ }
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString().replace(Regex(""",(\s*[}\]])"""), "$1")
    }

    private fun commandLine(cli: String, root: Path, args: List<String>): GeneralCommandLine =
        GeneralCommandLine(listOf(cli) + args)
            .withWorkDirectory(root.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(Guardrails.environment(EnvironmentUtil.getEnvironmentMap(), emptyMap()))

    private fun JsonElement?.str(): String? = this?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonElement?.bool(): Boolean = this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } == true
    private fun JsonElement?.strings(): List<String> = when {
        this == null || isJsonNull -> emptyList()
        isJsonArray -> asJsonArray.mapNotNull { it.str() }.filter { it.isNotBlank() }
        else -> listOfNotNull(str()).filter { it.isNotBlank() }
    }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
