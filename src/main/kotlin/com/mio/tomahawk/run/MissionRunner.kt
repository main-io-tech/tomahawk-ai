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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Dispara as etapas do Spec Kit pelo CLI de IA configurado, uma por vez por projeto. Na aba, a IA roda sem perguntar,
 * e por isso só no devcontainer do projeto ([DevContainer]), com o andamento em [progress] para a Telemetria mostrar e
 * cancelar; no terminal, nesta máquina, o usuário conversa com a IA e aprova os comandos, e o Tomahawk só registra.
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
        if (terminal == null) {
            DevContainer.problem(root, provider)?.let { why ->
                notifyNoContainer(mission, root, why)
                return false
            }
        }

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
            var point: Checkpoints.Point? = null
            val entry = try {
                current.step = "Pré-voo: preparando as proteções"
                val guard = guard(root, runName, mission, provider) { current.log(LogKind.INFO, it) }
                point = guard.point
                current.step = "Pré-voo: subindo o devcontainer"
                current.log(LogKind.INFO, "Subindo o devcontainer do projeto: a IA só roda sem perguntar lá dentro")
                val session = DevContainer.up(root, { current.log(LogKind.INFO, it) }, { current.cancelled })
                current.log(LogKind.INFO, "Devcontainer de pé e isolado: a IA só enxerga ${session.folder}")
                val inside = AgentLaunch.container(session, provider.executables.first())
                execute(current, inside, prompt, env + DevContainer.credentials(provider), extra, root, guard.settings)
            } catch (e: Throwable) {
                LOG.warn("A missão falhou", e)
                failure(current, prompt, e.message ?: "A execução não terminou.")
            }
            val file = runCatching { RunStore.writeRun(root, runName, Transcript.render(entry)) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({ finish(entry, file, point) }, project.disposed)
        }
        return true
    }

    private class Guard(val settings: Path?, val point: Checkpoints.Point?)

    /**
     * As proteções antes da missão, fora da EDT: as regras do CLI (o `.cursor/cli.json` do projeto ou o arquivo do
     * `--settings` do Claude Code) e o ponto de restauração no Git. Desligadas, as regras do Tomahawk saem do Cursor.
     */
    private fun guard(root: Path, runName: String, mission: Mission, provider: AgentProvider, log: (String) -> Unit): Guard {
        if (!Guardrails.enabled) {
            runCatching { Guardrails.applyCursorConfig(root, on = false) }
            log("Proteções desligadas no Arsenal: a IA roda sem as regras do Tomahawk e sem ponto de restauração")
            return Guard(null, null)
        }
        var settings: Path? = null
        when (provider) {
            AgentProvider.CURSOR -> runCatching { Guardrails.applyCursorConfig(root) }
                .onSuccess { log("Proteções: comandos perigosos barrados no .cursor/cli.json") }
                .onFailure { LOG.warn("Não deu para gravar o .cursor/cli.json", it); log("Não deu para gravar as regras no .cursor/cli.json: ${it.message}") }
            AgentProvider.CLAUDE -> settings = runCatching { Guardrails.writeClaudeSettings(root) }
                .onSuccess { log("Proteções: comandos perigosos e escrita fora do projeto barrados no Claude Code") }
                .onFailure { LOG.warn("Não deu para gravar as regras do Claude Code", it) }
                .getOrNull()
        }
        val label = mission.step.codename + mission.feature?.let { " em ${it.substringAfterLast('/')}" }.orEmpty()
        val point = runCatching { Checkpoints.create(root, runName, label) }.getOrNull()
        log(if (point != null) "Ponto de restauração criado: dá para desfazer o que esta missão mudar" else "Sem ponto de restauração: o projeto não é um repositório Git ou o Git falhou")
        return Guard(settings, point)
    }

    /**
     * Volta o projeto para antes de uma missão ([point], ou a última), depois de mostrar o que mudou desde lá. Não
     * roda com uma missão em voo.
     */
    fun undo(point: Checkpoints.Point? = null) {
        val root = SpecKitService.getInstance(project).root() ?: return
        if (progress != null) {
            notify("Missão em voo", "Espere a missão terminar ou aborte na Telemetria antes de desfazer.", NotificationType.WARNING)
            return
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        ApplicationManager.getApplication().executeOnPooledThread {
            val target = point ?: Checkpoints.latest(root)
            val changes = target?.let { Checkpoints.changes(root, it) }
            ApplicationManager.getApplication().invokeLater({
                when {
                    target == null -> notify("Nada para desfazer", "Nenhum ponto de restauração neste projeto: as missões só ganham um quando o projeto é um repositório Git.", NotificationType.INFORMATION)
                    changes == null -> notify("Não deu para comparar", "O Git não conseguiu comparar o projeto com o ponto de antes de ${target.label}.", NotificationType.ERROR)
                    changes.isEmpty() -> notify("Nada para desfazer", "O projeto está igual a antes de ${target.label}.", NotificationType.INFORMATION)
                    else -> confirmUndo(root, target, changes)
                }
            }, project.disposed)
        }
    }

    private fun confirmUndo(root: Path, point: Checkpoints.Point, changes: List<String>) {
        val shown = changes.take(15).joinToString("\n") { "  • $it" } + if (changes.size > 15) "\n  … e mais ${changes.size - 15}" else ""
        val answer = Messages.showOkCancelDialog(
            project,
            "Voltar o projeto para antes de ${point.label}? ${changes.size} arquivo(s) mudaram desde então:\n\n$shown\n\n" +
                "Os alterados voltam como eram e os criados depois são apagados. O estado de agora fica guardado no Git, para dar para voltar atrás.",
            "Desfazer a Missão",
            "Desfazer",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        )
        if (answer != Messages.OK) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { Checkpoints.restore(root, point) }
            ApplicationManager.getApplication().invokeLater({
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
                SpecKitService.getInstance(project).refresh()
                result.fold(
                    onSuccess = { undo ->
                        notify(
                            "Missão desfeita",
                            "O projeto voltou para antes de ${point.label}: ${undo.restored} arquivo(s) restaurado(s), ${undo.removed} apagado(s). " +
                                "O estado anterior ficou em ${undo.safety?.ref}.",
                            NotificationType.INFORMATION,
                        )
                    },
                    onFailure = { notify("Não deu para desfazer", it.message ?: it.javaClass.simpleName, NotificationType.ERROR) },
                )
            }, project.disposed)
        }
    }

    /** O comando como o usuário colaria no chat do agente, para rodar fora do Tomahawk. */
    fun copyCommand(mission: Mission) {
        val invocation = SpecKitService.getInstance(project).snapshot.invocation(mission.step)
        val text = listOf(invocation, mission.arguments.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify("Comando copiado", "Cole no chat do agente: ${text.take(120)}", NotificationType.INFORMATION)
    }

    /** `specify init --here` no terminal escolhido para o Spec Kit ([SpecKitSetup.init]); sem terminal, copia o comando. */
    fun initSpecKit() = SpecKitSetup.init(project)

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
            val guard = guard(root, runName, mission, provider) {}
            val shell = ShellKind.of(terminal.shellPath(project), SystemInfo.isWindows)
            val command = launch.terminal(provider, prompt, TomahawkSettings.model, extra, shell, root.toString(), env, settings = guard.settings)
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

    private fun execute(
        state: MissionProgress,
        launch: AgentLaunch,
        prompt: String,
        env: Map<String, String>,
        extra: List<String>,
        root: Path,
        settings: Path?,
    ): Transcript.Entry {
        val started = System.nanoTime()
        val timeout = TomahawkSettings.timeoutMinutes
        val provider = state.provider
        val executable = launch.description
        val workspace = launch.workspace(root.toString())
        val guarded = Guardrails.enabled
        val command = AgentCommand.headless(
            provider, launch.executable, prompt, workspace, TomahawkSettings.model, extra, settings = settings?.let { launch.workspace(it.toString()) },
        )
        val blocked = AtomicReference<String?>(null)
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
            val process = launch.process(command, root.toString(), env, scrub = guarded).createProcess()
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
                                if (guarded) {
                                    Guardrails.verdict(event.kind, event.target, workspace)?.let { why ->
                                        if (blocked.compareAndSet(null, why)) state.log(LogKind.ERROR, "🛡️ Proteção: $why. A missão foi abortada.")
                                    }
                                }
                                val target = event.target?.let { if (event.kind == "shell") it.take(120) else shortPath(it) }
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
            val stop = {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                launch.stop()
            }
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                blocked.get()?.let { why ->
                    error = "Abortada pela proteção do Tomahawk: $why."
                    stop()
                }
                if (error != null) break
                if (state.cancelled) {
                    cancelled = true
                    stop()
                    break
                }
                if (System.nanoTime() > deadline) {
                    error = "Passou do limite de $timeout minutos: a missão foi abortada."
                    stop()
                    break
                }
            }
            reader.join(2_000)
            errors.join(1_000)
            if (error == null) blocked.get()?.let { error = "Abortada pela proteção do Tomahawk: $it." }
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
        state.mission.step, state.invocation, state.mission.feature, if (state.cancelled) RunStatus.CANCELLED else RunStatus.FAILED, error, null, state.startedAt,
        (System.currentTimeMillis() - state.startedAt) / 1000, state.provider.title, TomahawkSettings.model,
        state.mission.arguments, prompt, emptyList(), emptyList(), "", "", emptyList(),
    )

    private fun finish(entry: Transcript.Entry, file: Path?, point: Checkpoints.Point?) {
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
        notify(
            title, summary, if (entry.status == RunStatus.OK) NotificationType.INFORMATION else NotificationType.WARNING, file,
            evaluate = entry.status == RunStatus.OK, restorePoint = point,
        )
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

    /** Sem o devcontainer pronto, a missão não roda sem perguntar: cria o devcontainer ou lança no terminal. */
    private fun notifyNoContainer(mission: Mission, root: Path, why: String) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Missões na aba só rodam no devcontainer",
                escape("$why A IA só roda sem perguntar num container que enxerga apenas o projeto. No terminal da IDE, você aprova os comandos dela."),
                NotificationType.WARNING,
            )
            .setIcon(TomahawkIcons.lancador)
        if (DevContainer.cli() != null && !Files.isRegularFile(DevContainer.config(root))) {
            notification.addAction(NotificationAction.createSimpleExpiring("Criar o devcontainer") {
                runCatching { DevContainer.create(root) }
                    .onSuccess { open(it) }
                    .onFailure { notify("Não deu para criar o devcontainer", it.message ?: it.javaClass.simpleName, NotificationType.ERROR) }
            })
        }
        if (LaunchTerminal.available() != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("Lançar no terminal") { launch(mission.copy(mode = RunMode.TERMINAL)) })
        }
        notification.addAction(NotificationAction.createSimpleExpiring("Abrir Arsenal") {
            TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.ARSENAL)
        })
        notification.notify(project)
    }

    private fun notify(
        title: String,
        content: String,
        type: NotificationType,
        transcript: Path? = null,
        evaluate: Boolean = false,
        restorePoint: Checkpoints.Point? = null,
    ) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, escape(content), type)
            .setIcon(TomahawkIcons.lancador)
        transcript?.let { path -> notification.addAction(NotificationAction.createSimple("Ver Registro") { open(path) }) }
        if (evaluate) {
            notification.addAction(NotificationAction.createSimpleExpiring("Avaliar") {
                TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.EVALUATION)
            })
        }
        restorePoint?.let { point -> notification.addAction(NotificationAction.createSimple("Desfazer a missão") { undo(point) }) }
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
