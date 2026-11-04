package com.mio.tomahawk.run

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.messages.Topic
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.TomahawkToolWindowFactory
import com.mio.tomahawk.settings.TomahawkConfigurable
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.ui.openArtifact
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Dispara as etapas do Spec Kit pelo CLI de IA configurado, uma por vez por projeto. Na aba, o andamento fica em
 * [progress] para a Telemetria mostrar e cancelar; no terminal, o usuário conversa com a IA e o Tomahawk só registra.
 */
@Service(Service.Level.PROJECT)
class MissionRunner(private val project: Project) {

    @Volatile
    var progress: MissionProgress? = null
        private set

    /** A missão em voo ou a última que voou nesta sessão, para o log continuar visível depois do pouso. */
    @Volatile
    var latest: MissionProgress? = null
        private set

    /** Devolve false se a missão não saiu: faltou algo ou o usuário desistiu. */
    fun launch(mission: Mission): Boolean {
        val service = SpecKitService.getInstance(project)
        val snapshot = service.snapshot
        val root = service.root() ?: return false
        if (!snapshot.initialized) {
            notify("Spec Kit não inicializado", "Inicialize o Spec Kit no projeto antes de lançar missões.", NotificationType.WARNING)
            return false
        }
        if (mission.step.argsRequired && mission.arguments.isBlank()) {
            notify("${mission.step.codename} precisa de uma descrição", "Escreva a entrada de ${snapshot.invocation(mission.step)} antes de lançar.", NotificationType.WARNING)
            return false
        }
        progress?.let {
            notify("Já há uma missão em voo", "Espere ${it.mission.step.codename} terminar ou aborte na Telemetria.", NotificationType.WARNING)
            return false
        }
        val provider = TomahawkSettings.provider
        val launch = TomahawkSettings.launch()
        if (launch == null || !TomahawkSettings.ready) {
            notifyNotReady(provider, launch == null)
            return false
        }
        val mode = chooseMode(mission) ?: return false
        val terminal = if (mode == RunMode.TERMINAL) LaunchTerminal.available() ?: run {
            notify("Terminal indisponível", "O plugin de terminal da IDE está desligado: a missão roda na aba.", NotificationType.WARNING)
            null
        } else null

        FileDocumentManager.getInstance().saveAllDocuments()
        val invocation = snapshot.invocation(mission.step)
        val commandFile = snapshot.commands[mission.step]?.let { snapshot.relative(it.file) }
        val runName = RunStore.newName(mission.step)
        val input = mission.arguments.trim().takeIf { it.isNotEmpty() }?.let { RunStore.writeInput(root, runName, it) }
        if (mission.feature != null && mission.step != SddStep.SPECIFY) {
            runCatching { service.layout()?.activate(root.resolve(mission.feature)) }.onFailure { LOG.warn("Não deu para ativar ${mission.feature}", it) }
        }
        val prompt = MissionPrompt.build(mission, invocation, commandFile, input, interactive = terminal != null)
        val env = featureEnv(mission)
        val extra = AgentCommand.splitArgs(TomahawkSettings.extraArgs)

        if (terminal != null) {
            launchInTerminal(terminal, mission, invocation, provider, launch, prompt, env, extra, root, runName)
            return true
        }
        val current = MissionProgress(mission, invocation, provider)
        progress = current
        latest = current
        project.messageBus.syncPublisher(RUNS).changed()
        ApplicationManager.getApplication().executeOnPooledThread {
            val entry = try {
                execute(current, launch, prompt, env + AgentVerifier.apiKeyEnv(provider), extra, root)
            } catch (e: Throwable) {
                LOG.warn("A missão falhou", e)
                failure(current, prompt, e.message ?: "A execução não terminou.")
            }
            val file = runCatching { RunStore.writeRun(root, runName, Transcript.render(entry)) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({ finish(entry, file) }, project.disposed)
        }
        return true
    }

    /** O comando como o usuário colaria no chat do agente, para rodar fora do Tomahawk. */
    fun copyCommand(mission: Mission) {
        val invocation = SpecKitService.getInstance(project).snapshot.invocation(mission.step)
        val text = listOf(invocation, mission.arguments.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify("Comando copiado", "Cole no chat do agente: ${text.take(120)}", NotificationType.INFORMATION)
    }

    /** `specify init --here` no terminal da IDE, onde o usuário confirma; sem terminal, copia o comando. */
    fun initSpecKit() {
        val root = SpecKitService.getInstance(project).root() ?: return
        val specify = TomahawkSettings.resolveSpecify()?.toString() ?: "specify"
        val args = listOf(specify, "init", "--here", "--integration", TomahawkSettings.provider.integration, "--script", if (SystemInfo.isWindows) "ps" else "sh")
        val terminal = LaunchTerminal.available()
        if (terminal == null) {
            val line = args.joinToString(" ")
            CopyPasteManager.getInstance().setContents(StringSelection(line))
            notify("Rode no terminal", "Comando copiado: $line", NotificationType.INFORMATION)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val shell = ShellKind.of(terminal.shellPath(project), SystemInfo.isWindows)
            val quoted = args.map { AgentCommand.quote(it, shell) }
            val line = if (shell == ShellKind.POWERSHELL) "& " + quoted.joinToString(" ") else quoted.joinToString(" ")
            ApplicationManager.getApplication().invokeLater({
                runCatching { terminal.run(project, root.toString(), "Spec Kit: init", line) }
                    .onFailure { notify("O terminal não abriu", it.message ?: it.javaClass.simpleName, NotificationType.ERROR) }
            }, project.disposed)
        }
    }

    private fun featureEnv(mission: Mission): Map<String, String> {
        val feature = mission.feature?.takeIf { mission.step != SddStep.SPECIFY } ?: return emptyMap()
        return mapOf("SPECIFY_FEATURE_DIRECTORY" to feature, "SPECIFY_FEATURE" to feature.substringAfterLast('/'))
    }

    private fun chooseMode(mission: Mission): RunMode? {
        val mode = mission.mode ?: TomahawkSettings.runMode
        if (mode != RunMode.ASK) return mode
        if (LaunchTerminal.available() == null) return RunMode.BACKGROUND
        val hint = if (mission.step.interactive) " ${mission.step.codename} faz perguntas: no terminal dá para responder." else ""
        return when (
            Messages.showDialog(
                project,
                "Lançar ${mission.step.codename} na aba, monitorada pela Telemetria, ou no terminal da IDE, onde dá para conversar com a IA?$hint",
                "Onde Lançar",
                arrayOf("Na Aba", "No Terminal", "Cancelar"),
                if (mission.step.interactive) 1 else 0,
                Messages.getQuestionIcon(),
            )
        ) {
            0 -> RunMode.BACKGROUND
            1 -> RunMode.TERMINAL
            else -> null
        }
    }

    private fun launchInTerminal(
        terminal: LaunchTerminal,
        mission: Mission,
        invocation: String,
        provider: AgentProvider,
        launch: AgentLaunch,
        prompt: String,
        env: Map<String, String>,
        extra: List<String>,
        root: Path,
        runName: String,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val shell = ShellKind.of(terminal.shellPath(project), SystemInfo.isWindows)
            val command = launch.terminal(provider, prompt, TomahawkSettings.model, extra, shell, root.toString(), env)
            var error: Throwable? = null
            ApplicationManager.getApplication().invokeAndWait({
                error = runCatching { terminal.run(project, root.toString(), "Tomahawk: ${mission.step.codename}", command) }.exceptionOrNull()
            }, ModalityState.any())
            val entry = Transcript.Entry(
                step = mission.step,
                invocation = invocation,
                feature = mission.feature,
                status = if (error == null) RunStatus.TERMINAL else RunStatus.FAILED,
                error = error?.let { "O terminal não abriu: ${it.message ?: it.javaClass.simpleName}" },
                verdict = null,
                startedAt = System.currentTimeMillis(),
                durationSeconds = 0,
                provider = provider.title,
                model = TomahawkSettings.model,
                arguments = mission.arguments,
                prompt = prompt,
                files = emptyList(),
                steps = emptyList(),
                answer = "Lançada no terminal da IDE: acompanhe e responda por lá. A Avaliação se atualiza quando os artefatos mudarem.",
                stderr = "",
                command = listOf(command, prompt),
            )
            val file = runCatching { RunStore.writeRun(root, runName, Transcript.render(entry)) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                project.messageBus.syncPublisher(RUNS).changed()
                SpecKitService.getInstance(project).refresh()
                if (error != null) notify("O terminal não abriu", entry.error.orEmpty(), NotificationType.ERROR, file)
            }, project.disposed)
        }
    }

    private fun execute(state: MissionProgress, launch: AgentLaunch, prompt: String, env: Map<String, String>, extra: List<String>, root: Path): Transcript.Entry {
        val started = System.nanoTime()
        val timeout = TomahawkSettings.timeoutMinutes
        val provider = state.provider
        val executable = launch.description
        val command = AgentCommand.headless(provider, launch.executable, prompt, launch.workspace(root.toString()), TomahawkSettings.model, extra)
        val text = StringBuilder()
        var answer: String? = null
        var failed = false
        var cost: Double? = null
        val steps = mutableListOf<String>()
        val files = linkedSetOf<String>()
        val stderr = StringBuilder()
        var cancelled = false
        var error: String? = null
        state.step = "Ignição: iniciando o ${provider.title}"
        state.log(LogKind.INFO, "Lançando ${state.invocation} com o ${provider.title}")
        try {
            val process = launch.process(command, root.toString(), env).createProcess()
            process.outputStream.close()
            val errors = Thread {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    synchronized(stderr) { stderr.appendLine(line) }
                    if (line.isNotBlank()) state.log(LogKind.ERROR, line)
                }
            }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeout.toLong())
            val reader = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { raw ->
                    for (event in AgentEvents.parse(raw)) {
                        when (event) {
                            is AgentEvent.Init -> {
                                state.model = event.model
                                state.step = "Em voo: ${provider.title} conectado"
                                state.log(LogKind.INFO, "Sessão iniciada${event.model?.let { " · modelo $it" }.orEmpty()}")
                            }
                            is AgentEvent.Text -> {
                                text.append(event.text).append('\n')
                                state.detail = event.text.lines().lastOrNull { it.isNotBlank() }?.take(160).orEmpty()
                                state.log(LogKind.TEXT, event.text.trim())
                            }
                            is AgentEvent.Tool -> {
                                val target = event.target?.let { if (event.kind == "shell") it else shortPath(it) }
                                val what = listOfNotNull(TOOL_LABELS[event.kind] ?: event.kind, target).joinToString(" ")
                                steps += what
                                state.advance(what)
                                state.log(LogKind.TOOL, what)
                                if (event.kind in WRITE_TOOLS) event.target?.let { files += shortPath(it) }
                                state.filesTouched = files.size
                            }
                            is AgentEvent.Result -> {
                                answer = event.text
                                failed = event.error
                                cost = event.costUsd
                                state.log(if (event.error) LogKind.ERROR else LogKind.RESULT, if (event.error) "Falhou" else "Resposta final recebida")
                            }
                            is AgentEvent.Other -> {
                                text.append(event.line).append('\n')
                                state.log(LogKind.TEXT, event.line)
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (state.cancelled) {
                    cancelled = true
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    break
                }
                if (System.nanoTime() > deadline) {
                    error = "Passou do limite de $timeout minutos: a missão foi abortada."
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    break
                }
            }
            reader.join(2_000)
            errors.join(1_000)
            if (!cancelled && error == null && process.exitValue() != 0) {
                error = synchronized(stderr) { stderr.toString() }.lines().lastOrNull { it.isNotBlank() }
                    ?: "O ${provider.title} terminou com o código ${process.exitValue()}."
            }
            if (!cancelled && error == null && failed) error = answer?.takeIf { it.isNotBlank() } ?: "A IA informou um erro."
        } catch (e: Exception) {
            LOG.warn("Falha ao rodar ${command.firstOrNull()}", e)
            error = "Não foi possível rodar $executable: ${e.message ?: e.javaClass.simpleName}"
        }
        val finalAnswer = answer?.takeIf { it.isNotBlank() } ?: text.toString().trim()
        val status = when {
            cancelled -> RunStatus.CANCELLED
            error != null -> RunStatus.FAILED
            else -> RunStatus.OK
        }
        return Transcript.Entry(
            step = state.mission.step,
            invocation = state.invocation,
            feature = state.mission.feature,
            status = status,
            error = error,
            verdict = if (status == RunStatus.OK) Verdict.of(state.mission.step, finalAnswer) else null,
            startedAt = state.startedAt,
            durationSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started),
            provider = provider.title,
            model = state.model ?: TomahawkSettings.model,
            arguments = state.mission.arguments,
            prompt = prompt,
            files = files,
            steps = steps,
            answer = finalAnswer,
            stderr = synchronized(stderr) { stderr.toString() },
            command = command,
            costUsd = cost,
        )
    }

    private fun failure(state: MissionProgress, prompt: String, error: String) = Transcript.Entry(
        state.mission.step, state.invocation, state.mission.feature, RunStatus.FAILED, error, null, state.startedAt,
        (System.currentTimeMillis() - state.startedAt) / 1000, state.provider.title, TomahawkSettings.model,
        state.mission.arguments, prompt, emptyList(), emptyList(), "", "", emptyList(),
    )

    private fun finish(entry: Transcript.Entry, file: Path?) {
        progress = null
        val base = SpecKitService.getInstance(project).root()?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (base != null) VfsUtil.markDirtyAndRefresh(true, true, true, base)
        SpecKitService.getInstance(project).refresh()
        project.messageBus.syncPublisher(RUNS).changed()
        val title = when (entry.status) {
            RunStatus.CANCELLED -> "${entry.step.codename} abortada"
            RunStatus.FAILED -> "${entry.step.codename} falhou"
            else -> "${entry.step.codename} concluída" + entry.verdict?.let { ": ${it.label}" }.orEmpty()
        }
        val summary = buildString {
            entry.error?.let { append(it.lines().first()).append(' ') }
            if (entry.files.isNotEmpty()) append("${entry.files.size} arquivo(s) alterado(s). ")
            append(entry.answer.lineSequence().firstOrNull { it.isNotBlank() }?.take(240).orEmpty())
        }.trim().ifEmpty { "Sem resposta." }
        notify(title, summary, if (entry.status == RunStatus.OK) NotificationType.INFORMATION else NotificationType.WARNING, file, evaluate = entry.status == RunStatus.OK)
    }

    fun open(path: Path) = openArtifact(project, path)

    /** Sem o CLI, ou sem o teste com a configuração de agora, as missões não saem: leva ao Arsenal para resolver. */
    private fun notifyNotReady(provider: AgentProvider, missing: Boolean) {
        val (title, content) = if (missing) {
            "CLI do ${provider.title} não encontrado" to provider.installHint
        } else {
            "Teste a integração com a IA" to "As missões só são lançadas depois que a integração é testada com a configuração atual."
        }
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.WARNING)
            .setIcon(TomahawkIcons.lancador)
            .addAction(NotificationAction.createSimpleExpiring("Abrir Arsenal") {
                TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.ARSENAL)
            })
            .addAction(NotificationAction.createSimpleExpiring("Configurações") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, TomahawkConfigurable::class.java)
            })
            .notify(project)
    }

    private fun notify(title: String, content: String, type: NotificationType, transcript: Path? = null, evaluate: Boolean = false) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, escape(content), type)
            .setIcon(TomahawkIcons.lancador)
        transcript?.let { path -> notification.addAction(NotificationAction.createSimple("Ver Registro") { open(path) }) }
        if (evaluate) {
            notification.addAction(NotificationAction.createSimpleExpiring("Avaliar") {
                TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.EVALUATION)
            })
        }
        notification.notify(project)
    }

    fun interface Listener {
        fun changed()
    }

    companion object {
        const val NOTIFICATION_GROUP = "Tomahawk IA"

        /** Uma missão começou ou terminou. */
        @Topic.ProjectLevel
        val RUNS = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        private val LOG = logger<MissionRunner>()
        private val WRITE_TOOLS = setOf("write", "edit", "delete", "applypatch", "editnotebook")
        private val TOOL_LABELS = mapOf(
            "read" to "Lendo",
            "write" to "Escrevendo",
            "edit" to "Editando",
            "delete" to "Apagando",
            "grep" to "Buscando",
            "glob" to "Procurando arquivos",
            "ls" to "Listando",
            "shell" to "Rodando",
            "todo" to "Atualizando a lista de tarefas",
        )

        fun getInstance(project: Project): MissionRunner = project.service()

        private fun shortPath(path: String): String {
            val normalized = path.replace('\\', '/')
            val specs = normalized.indexOf("specs/")
            if (specs >= 0) return normalized.substring(specs)
            val specify = normalized.indexOf(".specify/")
            if (specify >= 0) return normalized.substring(specify)
            return normalized.substringAfterLast('/').takeIf { normalized.length > 60 } ?: normalized
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
