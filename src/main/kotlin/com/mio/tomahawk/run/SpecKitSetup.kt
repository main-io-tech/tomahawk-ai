package com.mio.tomahawk.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.messages.Topic
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.speckit.SpecKitService
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * O Spec Kit nesta máquina: o CLI `specify`, instalado com o `uv` como manda o README do Spec Kit, e o `uv` antes,
 * pelo instalador oficial da Astral, quando ele falta. Instalar e atualizar rodam no terminal da IDE, à vista do
 * usuário; a tela acompanha até o `specify` aparecer ou ser trocado.
 */
object SpecKitSetup {

    fun interface Listener {
        fun changed()

        companion object {
            @Topic.AppLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    /**
     * O `specify` encontrado, a versão dele e a mais nova publicada, quando há uma. [broken]: o executável existe, mas
     * não roda, como quando a instalação do `uv` perdeu o pacote.
     */
    data class Installed(val path: Path, val version: String?, val latest: String?, val broken: Boolean = false) {
        val outdated: Boolean get() = latest != null && latest != version
    }

    enum class Job(val label: String, val title: String) {
        INSTALL("Instalando o Spec Kit", "Spec Kit: instalar"),
        REPAIR("Reinstalando o Spec Kit", "Spec Kit: reinstalar"),
        UPGRADE("Atualizando o Spec Kit", "Spec Kit: atualizar"),
        INIT("Inicializando o Spec Kit no projeto", "Spec Kit: init"),
    }

    const val PACKAGE = "specify-cli"
    private const val UV_PS = "https://astral.sh/uv/install.ps1"
    private const val UV_SH = "https://astral.sh/uv/install.sh"
    private const val CHECK_TIMEOUT_MS = 30_000
    private const val WAIT_MS = 10 * 60_000L
    private const val POLL_MS = 3_000L
    private val VERSION = Regex("""v?(\d+\.\d+\.\d+[\w.+-]*)""")
    private val UPDATE = Regex("""Update available:\s*v?(\S+)\s*\S+\s*v?(\d+\.\d+\.\d+[\w.+-]*)""")
    private val CRASH = Regex("""Traceback \(most recent call last\)|ModuleNotFoundError|ImportError""")

    /** O Python do `specify` escreve UTF-8 mesmo num console em outra página de código, em vez de quebrar no Rich. */
    private val UTF8 = mapOf("PYTHONUTF8" to "1", "PYTHONIOENCODING" to "utf-8")

    @Volatile
    var job: Job? = null
        private set

    @Volatile
    var installed: Installed? = null
        private set

    @Volatile
    private var checkedPath: Path? = null

    @Volatile
    private var checking = false

    /** Cada espera nova ou cancelada muda a geração, e a espera antiga para. */
    @Volatile
    private var generation = 0

    /** O que [installed] diz do `specify` em [path]; nulo enquanto a versão ainda não foi lida. */
    fun infoFor(path: Path?): Installed? {
        if (path == null) return null
        if (path != checkedPath) check()
        return installed?.takeIf { it.path == path }
    }

    /** Relê a versão do `specify` quando ele mudou de lugar, ou sempre com [force]; roda fora da EDT e avisa as telas. */
    fun check(force: Boolean = false) {
        val path = TomahawkSettings.resolveSpecify()
        if (checking || (!force && path == checkedPath)) return
        checking = true
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                installed = path?.let {
                    val output = run(it, "--version")
                    val broken = output != null && brokenIn(output)
                    Installed(it, output?.takeUnless { broken }?.let(::versionIn), if (broken) null else latest(it), broken)
                }
                checkedPath = path
            } finally {
                checking = false
            }
            changed()
        }
    }

    /** Para de esperar o terminal e relê o `specify`. */
    fun recheck() {
        generation++
        job = null
        check(force = true)
    }

    /**
     * O comando que instala o `specify` em [shell]: com o [uv] dado, ou instalando o `uv` antes. Com [force], instala
     * por cima de uma instalação com defeito.
     */
    fun installCommand(shell: ShellKind, uv: String?, force: Boolean = false): String {
        val tool = if (force) "tool install --force $PACKAGE" else "tool install $PACKAGE"
        if (uv != null) return line(shell, listOf(uv) + tool.split(' '))
        return when (shell) {
            ShellKind.POWERSHELL ->
                "powershell -NoProfile -ExecutionPolicy ByPass -c \"irm $UV_PS | iex\"; " +
                    "if (\$LASTEXITCODE -eq 0) { \$env:Path = \"\$env:USERPROFILE\\.local\\bin;\$env:Path\"; uv $tool }"
            ShellKind.CMD ->
                "powershell -NoProfile -ExecutionPolicy ByPass -c \"irm $UV_PS | iex\" && " +
                    "set \"PATH=%USERPROFILE%\\.local\\bin;%PATH%\" && uv $tool"
            ShellKind.POSIX -> "curl -LsSf $UV_SH | sh && export PATH=\"\$HOME/.local/bin:\$PATH\" && uv $tool"
        }
    }

    fun upgradeCommand(shell: ShellKind, specify: String): String = specifyLine(shell, listOf(specify, "self", "upgrade"))

    fun integrationCommand(shell: ShellKind, specify: String, key: String): String =
        specifyLine(shell, listOf(specify, "integration", "install", key, "--script", scriptType()))

    /**
     * `specify init` na pasta do projeto, sem as perguntas que travariam o terminal: `--force` junta com os arquivos
     * que já estão lá (o Tomahawk confirma antes) e `--ignore-agent-tools` não exige o CLI da IA no PATH, porque o
     * Tomahawk já o encontrou e testou.
     */
    fun initCommand(shell: ShellKind, specify: String, integration: String, script: String = scriptType()): String =
        specifyLine(shell, listOf(specify, "init", "--here", "--force", "--integration", integration, "--script", script, "--ignore-agent-tools"))

    /** A saída do `specify` mostra que ele nem chegou a rodar. */
    fun brokenIn(text: String): Boolean = CRASH.containsMatchIn(text)

    /** O shell escolhido para os comandos do Spec Kit; no Windows, também quando o terminal da IDE abre o WSL, onde o `specify` daqui não roda. */
    fun windowsShell(shellPath: String?): WindowsShell? {
        if (!SystemInfo.isWindows) return null
        TomahawkSettings.specKitShell?.let { return it }
        val name = shellPath.orEmpty().replace('\\', '/').substringAfterLast('/').lowercase()
        return if (name.startsWith("wsl") || name.startsWith("ubuntu")) WindowsShell.POWERSHELL else null
    }

    /** Na EDT. `specify init` no projeto, no terminal escolhido; espera a pasta `.specify/` aparecer. */
    fun init(project: Project) {
        if (job != null) return
        val service = SpecKitService.getInstance(project)
        val root = service.root() ?: return
        val specify = TomahawkSettings.resolveSpecify() ?: return install(project)
        if (installed?.takeIf { it.path == specify }?.broken == true) return repair(project)
        val provider = TomahawkSettings.provider
        val confirm = { command: String ->
            Messages.showOkCancelDialog(
                project,
                "O Spec Kit vai ser instalado na pasta do projeto, junto com os arquivos que já estão lá: a pasta .specify/ e os comandos de SDD do ${provider.title}.\n\n" +
                    "No terminal vai rodar:\n\n$command",
                "Inicializar o Spec Kit",
                "Inicializar",
                Messages.getCancelButton(),
                Messages.getQuestionIcon(),
            ) == Messages.OK
        }
        inTerminal(project, Job.INIT, { shell -> initCommand(shell, specify.toString(), provider.integration) }, confirm) { started ->
            watch(started, { Files.isDirectory(root.resolve(".specify")) }) { ok ->
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
                service.refresh(0)
                notify(
                    if (ok) "Spec Kit inicializado" else "O Spec Kit não foi inicializado",
                    if (ok) "O projeto tem a pasta .specify/ e os comandos de SDD do ${provider.title}." else
                        "Confira a aba \"${Job.INIT.title}\" do terminal. Se o shell deu erro, escolha PowerShell ou CMD no Arsenal, em Spec Kit, e tente de novo.",
                    if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                )
            }
        }
    }

    /** Copia o `specify init` do shell escolhido, para rodar fora da IDE. */
    fun copyInit(project: Project) {
        val specify = TomahawkSettings.resolveSpecify() ?: return install(project)
        val terminal = LaunchTerminal.available()
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = terminal?.let { runCatching { it.shellPath(project) }.getOrNull() }
            val shell = windowsShell(path)?.kind ?: ShellKind.of(path, SystemInfo.isWindows)
            val command = initCommand(shell, specify.toString(), TomahawkSettings.provider.integration)
            ApplicationManager.getApplication().invokeLater({
                CopyPasteManager.getInstance().setContents(StringSelection(command))
                notify("Comando copiado", "Rode na pasta do projeto: $command", NotificationType.INFORMATION)
            }, project.disposed)
        }
    }

    /** Na EDT. Reinstala o `specify` com defeito pelo `uv`; espera ele voltar a responder a versão. */
    fun repair(project: Project) {
        if (job != null) return
        val specify = TomahawkSettings.resolveSpecify() ?: return install(project)
        val uv = TomahawkSettings.resolveUv()
        val confirm = { command: String ->
            Messages.showOkCancelDialog(
                project,
                "O specify desta máquina não roda: a instalação dele no uv perdeu o pacote $PACKAGE. Reinstalar por cima, com a versão mais nova?\n\n" +
                    "No terminal vai rodar:\n\n$command",
                "Reinstalar o Spec Kit",
                "Reinstalar",
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) == Messages.OK
        }
        inTerminal(project, Job.REPAIR, { shell -> installCommand(shell, uv?.toString(), force = true) }, confirm) { started ->
            watch(started, { version(specify) != null }) { ok ->
                notify(
                    if (ok) "Spec Kit reinstalado" else "O specify continua com defeito",
                    if (ok) "O specify voltou a funcionar nesta máquina." else "Confira a aba \"${Job.REPAIR.title}\" do terminal.",
                    if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                )
            }
        }
    }

    /** Na EDT. Mostra o comando, roda no terminal da IDE e espera o `specify` aparecer. */
    fun install(project: Project) {
        if (job != null) return
        val uv = TomahawkSettings.resolveUv()
        val confirm = { command: String ->
            val what = if (uv != null) "O uv já está nesta máquina: ele instala o specify-cli." else
                "O uv não foi encontrado: o comando roda antes o instalador oficial da Astral ($UV_PS) e depois instala o specify-cli com ele."
            Messages.showOkCancelDialog(
                project,
                "$what\n\nNo terminal da IDE vai rodar:\n\n$command",
                "Instalar o Spec Kit",
                "Instalar",
                Messages.getCancelButton(),
                Messages.getQuestionIcon(),
            ) == Messages.OK
        }
        inTerminal(project, Job.INSTALL, { shell -> installCommand(shell, uv?.toString()) }, confirm) { started ->
            watch(started, { TomahawkSettings.resolveSpecify() != null }) { ok ->
                notify(
                    if (ok) "Spec Kit instalado" else "O specify não apareceu",
                    if (ok) "O specify está pronto nesta máquina." else
                        "Confira a aba \"${Job.INSTALL.title}\" do terminal. Se ele foi instalado em outra pasta, informe o caminho do specify nas configurações do Tomahawk.",
                    if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                )
            }
        }
    }

    /** Na EDT. `specify self upgrade` no terminal da IDE; espera o executável ser trocado. */
    fun upgrade(project: Project) {
        if (job != null) return
        val specify = TomahawkSettings.resolveSpecify() ?: return install(project)
        if (installed?.takeIf { it.path == specify }?.broken == true) return repair(project)
        val before = modified(specify)
        inTerminal(project, Job.UPGRADE, { shell -> upgradeCommand(shell, specify.toString()) }) { started ->
            watch(started, { modified(specify) != before }) { ok ->
                if (ok) notify("Spec Kit atualizado", "O specify foi trocado pela versão nova.", NotificationType.INFORMATION)
            }
        }
    }

    /** Na EDT. Instala no projeto a integração da IA configurada, no terminal da IDE. */
    fun installIntegration(project: Project) {
        val specify = TomahawkSettings.resolveSpecify() ?: return install(project)
        val key = TomahawkSettings.provider.integration
        inTerminal(project, null, { shell -> integrationCommand(shell, specify.toString(), key) })
    }

    /**
     * Monta o comando para o shell do terminal (fora da EDT), pede [confirm] e roda na pasta do projeto. Sem o plugin
     * de terminal, copia o comando. [onStarted] recebe a geração da espera de [job], quando há um.
     */
    private fun inTerminal(
        project: Project,
        job: Job?,
        build: (ShellKind) -> String,
        confirm: (String) -> Boolean = { true },
        onStarted: (Int) -> Unit = {},
    ) {
        val root = SpecKitService.getInstance(project).root()?.toString() ?: System.getProperty("user.home")
        val terminal = LaunchTerminal.available()
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = terminal?.let { runCatching { it.shellPath(project) }.getOrNull() }
            val chosen = windowsShell(path)
            val shell = chosen?.kind ?: ShellKind.of(path, SystemInfo.isWindows)
            val command = build(shell)
            ApplicationManager.getApplication().invokeLater({
                if (!confirm(command)) return@invokeLater
                if (terminal == null) {
                    CopyPasteManager.getInstance().setContents(StringSelection(command))
                    notify("Rode no terminal", "O plugin de terminal da IDE está desligado. Comando copiado: $command", NotificationType.INFORMATION)
                    return@invokeLater
                }
                runCatching { terminal.run(project, root, job?.title ?: "Spec Kit", command, chosen) }.onFailure {
                    notify("O terminal não abriu", it.message ?: it.javaClass.simpleName, NotificationType.ERROR)
                    return@invokeLater
                }
                if (job == null) return@invokeLater
                this.job = job
                changed()
                onStarted(++generation)
            }, project.disposed)
        }
    }

    private fun watch(started: Int, done: () -> Boolean, onEnd: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val deadline = System.currentTimeMillis() + WAIT_MS
            var ok = false
            while (generation == started && System.currentTimeMillis() < deadline) {
                if (runCatching(done).getOrDefault(false)) {
                    ok = true
                    break
                }
                Thread.sleep(POLL_MS)
            }
            if (generation != started) return@executeOnPooledThread
            if (ok) Thread.sleep(POLL_MS)
            job = null
            check(force = true)
            changed()
            ApplicationManager.getApplication().invokeLater { onEnd(ok) }
        }
    }

    /** A versão na saída do `specify --version`, como `specify 0.11.8`. */
    fun versionIn(text: String): String? = VERSION.find(text)?.groupValues?.get(1)

    /** A versão mais nova que o `specify self check` anuncia; nula se ele já está na última. */
    fun latestIn(text: String): String? = UPDATE.find(text)?.groupValues?.get(2)

    private fun version(path: Path): String? = run(path, "--version")?.let(::versionIn)

    private fun latest(path: Path): String? = run(path, "self", "check")?.let(::latestIn)

    private fun run(path: Path, vararg args: String): String? = runCatching {
        val line = GeneralCommandLine(listOf(path.toString()) + args)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(mapOf("PYTHONIOENCODING" to "utf-8", "PYTHONUTF8" to "1", "NO_COLOR" to "1"))
        val output = ExecUtil.execAndGetOutput(line, CHECK_TIMEOUT_MS)
        if (output.isTimeout) null else output.stdout + "\n" + output.stderr
    }.getOrNull()

    private fun modified(path: Path) = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    private fun line(shell: ShellKind, args: List<String>): String {
        val quoted = args.joinToString(" ") { AgentCommand.quote(it, shell) }
        return if (shell == ShellKind.POWERSHELL) "& $quoted" else quoted
    }

    private fun specifyLine(shell: ShellKind, args: List<String>) = AgentCommand.withEnv(shell, UTF8, line(shell, args))

    private fun scriptType() = if (SystemInfo.isWindows) "ps" else "sh"

    private fun changed() {
        ApplicationManager.getApplication()?.messageBus?.syncPublisher(Listener.TOPIC)?.changed()
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(MissionRunner.NOTIFICATION_GROUP)
            .createNotification(title, content.replace("<", "&lt;"), type)
            .setIcon(TomahawkIcons.arsenal)
            .notify(null)
    }
}
