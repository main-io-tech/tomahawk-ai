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

    /** O `specify` encontrado, a versão dele e a mais nova publicada, quando há uma. */
    data class Installed(val path: Path, val version: String?, val latest: String?) {
        val outdated: Boolean get() = latest != null && latest != version
    }

    enum class Job(val label: String, val title: String) {
        INSTALL("Instalando o Spec Kit", "Spec Kit: instalar"),
        UPGRADE("Atualizando o Spec Kit", "Spec Kit: atualizar"),
    }

    const val PACKAGE = "specify-cli"
    private const val UV_PS = "https://astral.sh/uv/install.ps1"
    private const val UV_SH = "https://astral.sh/uv/install.sh"
    private const val CHECK_TIMEOUT_MS = 30_000
    private const val WAIT_MS = 10 * 60_000L
    private const val POLL_MS = 3_000L
    private val VERSION = Regex("""v?(\d+\.\d+\.\d+[\w.+-]*)""")
    private val UPDATE = Regex("""Update available:\s*v?(\S+)\s*\S+\s*v?(\d+\.\d+\.\d+[\w.+-]*)""")

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
                installed = path?.let { Installed(it, version(it), latest(it)) }
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

    /** O comando que instala o `specify` em [shell]: com o [uv] dado, ou instalando o `uv` antes. */
    fun installCommand(shell: ShellKind, uv: String?): String {
        if (uv != null) return line(shell, listOf(uv, "tool", "install", PACKAGE))
        return when (shell) {
            ShellKind.POWERSHELL ->
                "powershell -NoProfile -ExecutionPolicy ByPass -c \"irm $UV_PS | iex\"; " +
                    "if (\$LASTEXITCODE -eq 0) { \$env:Path = \"\$env:USERPROFILE\\.local\\bin;\$env:Path\"; uv tool install $PACKAGE }"
            ShellKind.CMD ->
                "powershell -NoProfile -ExecutionPolicy ByPass -c \"irm $UV_PS | iex\" && " +
                    "set \"PATH=%USERPROFILE%\\.local\\bin;%PATH%\" && uv tool install $PACKAGE"
            ShellKind.POSIX -> "curl -LsSf $UV_SH | sh && export PATH=\"\$HOME/.local/bin:\$PATH\" && uv tool install $PACKAGE"
        }
    }

    fun upgradeCommand(shell: ShellKind, specify: String): String = line(shell, listOf(specify, "self", "upgrade"))

    fun integrationCommand(shell: ShellKind, specify: String, key: String): String =
        line(shell, listOf(specify, "integration", "install", key, "--script", if (SystemInfo.isWindows) "ps" else "sh"))

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
            val shell = ShellKind.of(terminal?.let { runCatching { it.shellPath(project) }.getOrNull() }, SystemInfo.isWindows)
            val command = build(shell)
            ApplicationManager.getApplication().invokeLater({
                if (!confirm(command)) return@invokeLater
                if (terminal == null) {
                    CopyPasteManager.getInstance().setContents(StringSelection(command))
                    notify("Rode no terminal", "O plugin de terminal da IDE está desligado. Comando copiado: $command", NotificationType.INFORMATION)
                    return@invokeLater
                }
                runCatching { terminal.run(project, root, job?.title ?: "Spec Kit", command) }.onFailure {
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
