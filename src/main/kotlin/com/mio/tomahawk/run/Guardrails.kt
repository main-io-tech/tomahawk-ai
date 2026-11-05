package com.mio.tomahawk.run

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * As proteções da máquina quando a IA roda com permissão para agir sozinha, em camadas:
 * - regras no próprio CLI, que barram antes de rodar: os comandos perigosos no `deny` do Cursor (`.cursor/cli.json`)
 *   e do Claude Code (`--settings`), que também não deixa as ferramentas de arquivo escreverem fora do projeto;
 * - o vigia ([verdict]), que lê cada ferramenta no `stream-json` e aborta a missão num comando perigoso, numa
 *   escrita fora do projeto ou numa leitura de credenciais, mesmo quando a regra do CLI não pegou;
 * - o ambiente sem os segredos ([environment]) que a IA não precisa;
 * - o ponto de restauração no Git antes de cada missão, em [Checkpoints].
 *
 * O Cursor CLI só respeita o `deny` de `Shell(...)` com `--force`; o de `Write(...)` não, por isso o vigia e o ponto
 * de restauração cobrem a escrita.
 */
object Guardrails {

    /** Um tipo de operação perigosa: como o vigia a reconhece e as regras que barram no Cursor e no Claude Code. */
    class Danger(val reason: String, val pattern: Regex, val cursor: List<String>, val claude: List<String>)

    private fun shell(vararg commands: String) = commands.flatMap { listOf("Bash($it)", "PowerShell($it)") }

    private fun words(vararg names: String) = Regex("""(?i)(^|[\s;&|(`'"])(${names.joinToString("|")})(\.exe)?(\s|$|[;&|)])""")

    val DANGERS = listOf(
        Danger(
            "apagar pastas inteiras",
            Regex("""(?i)(^|[\s;&|(])(rm\s+(-\w+\s+)*-\w*[rR]\w*|rmdir\s+/s|rd\s+/s|del\s+(/\w\s+)*/s|rimraf|Remove-Item\b[^;&|]*\s-r(ecurse)?\b|(rd|rmdir|ri)\s+[^;&|]*-r(ecurse)?\b)"""),
            listOf("Shell(rm:-*r*)", "Shell(rm:-*R*)", "Shell(Remove-Item:*-Recurse*)", "Shell(Remove-Item:*-r *)", "Shell(rd:*/s*)", "Shell(rd:*/S*)",
                "Shell(rmdir:*/s*)", "Shell(rmdir:*/S*)", "Shell(del:*/s*)", "Shell(del:*/S*)", "Shell(rd:*-r*)", "Shell(rmdir:*-r*)", "Shell(rimraf)"),
            shell("rm -r *", "rm -rf *", "rm -fr *", "rm -R *", "rm -Rf *", "rm -fR *", "rimraf *") + "PowerShell(Remove-Item *-Recurse*)" +
                "PowerShell(Remove-Item *-r *)" + "Bash(rd /s *)" + "Bash(rmdir /s *)" + "Bash(del /s *)",
        ),
        Danger(
            "formatar ou apagar discos e partições",
            Regex("""(?i)(^|[\s;&|(])(format(\.com)?\s+[a-z]:|diskpart|mkfs(\.\w+)?|fdisk|parted|wipefs|dd\s+[^;&|]*\bof=|Format-Volume|Clear-Disk|Remove-Partition|Initialize-Disk|bcdedit|vssadmin\s+delete|cipher\s+/w)(?![\w-])"""),
            listOf("Shell(format)", "Shell(diskpart)", "Shell(mkfs*)", "Shell(fdisk)", "Shell(parted)", "Shell(wipefs)", "Shell(dd)", "Shell(Format-Volume)",
                "Shell(Clear-Disk)", "Shell(Remove-Partition)", "Shell(Initialize-Disk)", "Shell(bcdedit)", "Shell(vssadmin)", "Shell(cipher)"),
            shell("format *", "diskpart*", "mkfs*", "fdisk *", "parted *", "wipefs *", "dd *", "Format-Volume*", "Clear-Disk*", "Remove-Partition*",
                "Initialize-Disk*", "bcdedit*", "vssadmin *"),
        ),
        Danger(
            "desligar ou reiniciar a máquina",
            words("shutdown", "reboot", "poweroff", "halt", "Stop-Computer", "Restart-Computer"),
            listOf("Shell(shutdown)", "Shell(reboot)", "Shell(poweroff)", "Shell(halt)", "Shell(Stop-Computer)", "Shell(Restart-Computer)"),
            shell("shutdown*", "reboot*", "poweroff*", "halt*", "Stop-Computer*", "Restart-Computer*"),
        ),
        Danger(
            "pedir privilégio de administrador",
            Regex("""(?i)(^|[\s;&|(])(sudo|su|doas|pkexec|runas|gsudo)(\s|$)|-Verb\s+RunAs"""),
            listOf("Shell(sudo)", "Shell(su)", "Shell(doas)", "Shell(pkexec)", "Shell(runas)", "Shell(gsudo)", "Shell(Start-Process:*RunAs*)"),
            shell("sudo *", "su *", "doas *", "pkexec *", "runas *", "gsudo *") + "PowerShell(Start-Process *RunAs*)",
        ),
        Danger(
            "mudar a configuração do sistema",
            Regex("""(?i)(^|[\s;&|(])(reg(\.exe)?\s+(add|delete|import|load|restore)|regedit|setx|netsh|schtasks|sc(\.exe)?\s+(create|delete|config|stop)|crontab|systemctl|launchctl|Set-ExecutionPolicy|New-Service|Remove-Service|Set-Service|icacls|takeown|chown|chmod\s+-R|New-ItemProperty|Set-ItemProperty\s+[^;&|]*HK(LM|CU)|Remove-ItemProperty|Set-MpPreference|Add-MpPreference)\b"""),
            listOf("Shell(reg)", "Shell(regedit)", "Shell(setx)", "Shell(netsh)", "Shell(schtasks)", "Shell(sc:create*)", "Shell(sc:delete*)", "Shell(sc:config*)",
                "Shell(crontab)", "Shell(systemctl)", "Shell(launchctl)", "Shell(Set-ExecutionPolicy)", "Shell(New-Service)", "Shell(Remove-Service)", "Shell(Set-Service)",
                "Shell(icacls)", "Shell(takeown)", "Shell(chown)", "Shell(chmod:-R*)", "Shell(New-ItemProperty)", "Shell(Set-ItemProperty:*HK*)",
                "Shell(Remove-ItemProperty)", "Shell(Set-MpPreference)", "Shell(Add-MpPreference)"),
            shell("reg add *", "reg delete *", "reg import *", "regedit*", "setx *", "netsh *", "schtasks *", "sc create *", "sc delete *", "sc config *",
                "crontab *", "systemctl *", "launchctl *", "Set-ExecutionPolicy*", "New-Service*", "Remove-Service*", "Set-Service*", "icacls *", "takeown *",
                "chown *", "chmod -R *", "New-ItemProperty*", "Remove-ItemProperty*", "Set-MpPreference*", "Add-MpPreference*") + "PowerShell(Set-ItemProperty *HK*)",
        ),
        Danger(
            "rodar um script baixado da internet",
            Regex("""(?i)((curl|wget)\b[^;&]*\|\s*(sudo\s+)?(ba|z|da)?sh\b|(^|[\s;&|(])(iex|Invoke-Expression)\b|\b(irm|iwr|Invoke-RestMethod|Invoke-WebRequest)\b[^;&]*\|\s*(iex|Invoke-Expression)\b)"""),
            listOf("Shell(iex)", "Shell(Invoke-Expression)"),
            listOf("PowerShell(Invoke-Expression*)", "Bash(curl * | sh*)", "Bash(curl * | bash*)", "Bash(wget * | sh*)", "Bash(wget * | bash*)"),
        ),
        Danger(
            "instalar programas na máquina",
            Regex("""(?i)(^|[\s;&|(])(winget|choco|scoop|brew|apt|apt-get|dnf|yum|pacman|snap|zypper)\s+(install|uninstall|remove|upgrade|purge)\b|\b(npm|pnpm)\s+(i|install|add|uninstall|remove|rm)\s+[^;&|]*(-g|--global)\b|\byarn\s+global\b|\b(pipx|uv\s+tool|cargo)\s+(install|uninstall)\b"""),
            listOf("Shell(winget)", "Shell(choco)", "Shell(scoop)", "Shell(brew)", "Shell(apt)", "Shell(apt-get)", "Shell(dnf)", "Shell(yum)", "Shell(pacman)",
                "Shell(snap)", "Shell(zypper)", "Shell(npm:*-g*)", "Shell(npm:*--global*)", "Shell(pnpm:*-g*)", "Shell(yarn:global*)", "Shell(pipx)", "Shell(cargo:install*)"),
            shell("winget *", "choco *", "scoop *", "brew install *", "brew uninstall *", "apt *", "apt-get *", "dnf *", "yum *", "pacman *", "snap *",
                "npm * -g*", "npm * --global*", "pnpm * -g*", "yarn global *", "pipx *", "cargo install *"),
        ),
        Danger(
            "reescrever ou apagar o histórico do Git, ou descartar o que não foi commitado",
            Regex("""(?i)\bgit\s+(-C\s+\S+\s+)?(push|reset\s+--hard|clean\s+-\w*[fd]|branch\s+-D|stash\s+(drop|clear)|rebase|filter-branch|filter-repo|update-ref\s+-d|reflog\s+expire|gc\s+--prune|remote\s+(remove|rm|set-url)|checkout\s+(-f\b|--\s+\.|\.\s*$)|restore\s+(\.|--source|--worktree|-W))"""),
            listOf("Shell(git:push*)", "Shell(git:reset --hard*)", "Shell(git:clean*)", "Shell(git:branch -D*)", "Shell(git:stash drop*)", "Shell(git:stash clear*)",
                "Shell(git:rebase*)", "Shell(git:filter-branch*)", "Shell(git:filter-repo*)", "Shell(git:update-ref -d*)", "Shell(git:reflog expire*)",
                "Shell(git:gc --prune*)", "Shell(git:remote remove*)", "Shell(git:remote set-url*)", "Shell(git:checkout -f*)", "Shell(git:checkout -- .*)",
                "Shell(git:restore .*)", "Shell(git:restore --source*)"),
            shell("git push*", "git reset --hard*", "git clean *", "git branch -D *", "git stash drop*", "git stash clear*", "git rebase*", "git filter-branch*",
                "git filter-repo*", "git update-ref -d *", "git reflog expire*", "git gc --prune*", "git remote remove *", "git remote set-url *",
                "git checkout -f*", "git checkout -- .*", "git restore .*", "git restore --source*"),
        ),
    )

    /** Pastas e arquivos de credenciais, a partir da pasta do usuário. */
    private val SECRET_DIRS = listOf(".ssh", ".aws", ".azure", ".gnupg", ".kube", ".config/gh", ".config/gcloud")
    private val SECRET_FILES = listOf(".docker/config.json", ".npmrc", ".pypirc", ".netrc", ".git-credentials")
    val SECRETS = SECRET_DIRS + SECRET_FILES

    /** As credenciais como globs a partir de `~`: as pastas com tudo dentro, os arquivos como são. */
    private val SECRET_GLOBS = SECRET_DIRS.map { "$it/**" } + SECRET_FILES

    private val SECRET_PATTERN = Regex(
        """(?i)(^|[\\/~\s'"])(""" + SECRETS.joinToString("|") { Regex.escape(it).replace("/", """[\\/]""") } + """)([\\/\s'"]|$)|\bid_(rsa|ed25519|ecdsa|dsa)\b""",
    )

    /** Variáveis de ambiente com segredos que a IA não recebe; as do próprio CLI passam. */
    private val SECRET_ENV = Regex("""(?i)(TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|ACCESS_?KEY|PRIVATE_?KEY|CREDENTIAL|^AWS_|^AZURE_|^GOOGLE_APPLICATION_CREDENTIALS$|^GCP_|^ARM_CLIENT|^NPM_CONFIG__AUTH)""")
    private val CLI_ENV = Regex("""(?i)^(CURSOR_|ANTHROPIC_|CLAUDE_)""")

    private val WRITE_TOOLS = setOf("write", "edit", "delete", "applypatch", "editnotebook", "multiedit")
    private val READ_TOOLS = setOf("read", "grep", "glob", "ls")

    val enabled: Boolean get() = TomahawkSettings.guardrails

    /** Por que o comando de shell é perigoso, ou nulo. */
    fun danger(command: String): Danger? = DANGERS.firstOrNull { it.pattern.containsMatchIn(command) }

    /**
     * O vigia: por que esta ferramenta deve parar a missão, ou nulo. [root] é a pasta do projeto, no formato em que
     * o CLI a enxerga (no WSL, `/mnt/c/...`).
     */
    fun verdict(kind: String, target: String?, root: String): String? {
        val text = target?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            kind == "shell" -> danger(text)?.let { "o comando tentou ${it.reason}: ${text.take(160)}" }
                ?: if (SECRET_PATTERN.containsMatchIn(text)) "o comando mexeu em credenciais da máquina: ${text.take(160)}" else null
            kind in WRITE_TOOLS -> when {
                !inside(text, root) -> "a IA tentou alterar um arquivo fora do projeto: $text"
                gitInternals(text, root) -> "a IA tentou alterar o repositório Git por dentro: $text"
                else -> null
            }
            kind in READ_TOOLS -> if (SECRET_PATTERN.containsMatchIn(text.replace('\\', '/'))) "a IA tentou ler credenciais da máquina: $text" else null
            else -> null
        }
    }

    /** [path] dentro de [root]: relativo, ou absoluto debaixo dele. Sem distinguir maiúsculas no Windows. */
    fun inside(path: String, root: String): Boolean {
        val p = normalize(path)
        if (!absolute(p)) return !p.split('/').contains("..")
        val r = normalize(root).trimEnd('/')
        val windows = r.length > 1 && r[1] == ':'
        val (a, b) = if (windows) p.lowercase() to r.lowercase() else p to r
        return a == b || a.startsWith("$b/")
    }

    private fun gitInternals(path: String, root: String): Boolean {
        val p = normalize(path)
        val relative = if (absolute(p)) p.substring(normalize(root).trimEnd('/').length).trimStart('/') else p
        return relative == ".git" || relative.startsWith(".git/")
    }

    private fun normalize(path: String) = path.trim().trim('"', '\'').replace('\\', '/').replace(Regex("/+"), "/").removePrefix("./")

    private fun absolute(path: String) = path.startsWith("/") || path.startsWith("~") || Regex("^[A-Za-z]:").containsMatchIn(path)

    /** O ambiente da máquina sem os segredos, mais [extra] (as variáveis do CLI e da feature). */
    fun environment(base: Map<String, String>, extra: Map<String, String>): Map<String, String> =
        base.filterKeys { CLI_ENV.containsMatchIn(it) || !SECRET_ENV.containsMatchIn(it) } + extra

    /** As regras do Claude Code, para o `--settings`: os comandos perigosos, as credenciais e a escrita fora do projeto. */
    fun claudeSettings(): String {
        val deny = JsonArray()
        DANGERS.flatMap { it.claude }.distinct().forEach(deny::add)
        SECRET_GLOBS.forEach { glob ->
            deny.add("Read(~/$glob)")
            deny.add("Edit(~/$glob)")
        }
        deny.add("Edit(/.git/**)")
        val permissions = JsonObject().apply {
            add("deny", deny)
            addProperty("blockWritesOutsideWorkingDirectories", true)
        }
        return GSON.toJson(JsonObject().apply { add("permissions", permissions) })
    }

    /** As regras do Cursor CLI que o Tomahawk põe no `deny` do `.cursor/cli.json` do projeto. */
    fun cursorDeny(): List<String> =
        (DANGERS.flatMap { it.cursor } + SECRET_GLOBS.map { "Read(~/$it)" }).distinct()

    /**
     * O `.cursor/cli.json` de [text] (ou novo) com as regras do Tomahawk no `deny`, sem mexer nas do usuário; com
     * [on] falso, só tira as do Tomahawk. O CLI recusa o arquivo sem `allow` e `deny`, então os dois ficam sempre.
     */
    fun mergeCursorConfig(text: String?, on: Boolean): String {
        val root = text?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() } ?: JsonObject()
        val permissions = root.getAsJsonObject("permissions") ?: JsonObject().also { root.add("permissions", it) }
        val allow = permissions.get("allow")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val ours = cursorDeny().toSet()
        val theirs = permissions.get("deny")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.filterNot { it in ours }.orEmpty()
        val deny = JsonArray().apply { (theirs + if (on) ours else emptySet()).forEach(::add) }
        permissions.add("allow", allow)
        permissions.add("deny", deny)
        return GSON.toJson(root) + "\n"
    }

    /**
     * Aplica (ou tira) as regras no `.cursor/cli.json` do projeto em [root]. Quando o arquivo é criado pelo
     * Tomahawk, ele entra no `.git/info/exclude`, para não ir para os commits.
     */
    fun applyCursorConfig(root: Path, on: Boolean = enabled) {
        val file = root.resolve(".cursor").resolve("cli.json")
        val existed = Files.isRegularFile(file)
        if (!existed && !on) return
        val before = if (existed) Files.readString(file) else null
        val after = mergeCursorConfig(before, on)
        if (after == before) return
        Files.createDirectories(file.parent)
        Files.writeString(file, after)
        if (!existed) excludeFromGit(root, ".cursor/cli.json")
    }

    private fun excludeFromGit(root: Path, entry: String) {
        val exclude = root.resolve(".git").resolve("info").resolve("exclude")
        if (!Files.isDirectory(root.resolve(".git"))) return
        runCatching {
            val current = if (Files.isRegularFile(exclude)) Files.readString(exclude) else ""
            if (current.lines().any { it.trim() == entry }) return
            Files.createDirectories(exclude.parent)
            Files.writeString(exclude, current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + entry + "\n")
        }
    }

    /** Grava as regras do Claude Code em `tomahawk/protecoes/claude.json` e devolve o arquivo. */
    fun writeClaudeSettings(root: Path): Path {
        val file = root.resolve(RunStore.DIR).resolve("protecoes").resolve("claude.json")
        Files.createDirectories(file.parent)
        val text = claudeSettings()
        if (!Files.isRegularFile(file) || Files.readString(file) != text) Files.writeString(file, text)
        return file
    }

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}
