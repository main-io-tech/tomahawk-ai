package com.mio.tomahawk.speckit

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import com.mio.tomahawk.run.RunRecord
import com.mio.tomahawk.run.RunStore
import com.mio.tomahawk.run.TomahawkSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** O estado do Spec Kit no projeto num instante: o que as abas mostram e o que o Lançador usa para disparar. */
class Snapshot(
    val layout: SpecKitLayout?,
    val integration: IntegrationInfo,
    val constitutionReady: Boolean,
    val commands: Map<SddStep, CommandRef>,
    val features: List<FeatureReport>,
    val active: FeatureReport?,
    val runs: List<RunRecord>,
) {
    val initialized: Boolean get() = layout?.initialized == true

    /** `-` nas skills (Spec Kit 1.x) e `.` nos arquivos de comando (versões antigas), a menos que a integração diga. */
    val separator: String get() = integration.separator ?: commands.values.firstOrNull()?.separator ?: "-"

    fun invocation(step: SddStep): String = step.invocation(separator)

    fun relative(path: Path): String = layout?.relative(path) ?: path.toString()

    /** As execuções de [feature], da mais recente para a mais antiga; sem feature, as que não tinham feature. */
    fun runsOf(feature: FeatureReport?): List<RunRecord> {
        val name = feature?.let { relative(it.dir) }
        val short = feature?.name
        return runs.filter { it.feature == name || (short != null && it.feature?.substringAfterLast('/') == short) }
    }

    fun statuses(feature: FeatureReport? = active): List<StepStatus> =
        SddPipeline.evaluate(constitutionReady, feature, if (initialized) commands.keys else emptySet(), runsOf(feature))

    companion object {
        val EMPTY = Snapshot(null, IntegrationInfo.NONE, false, emptyMap(), emptyList(), null, emptyList())
    }
}

/**
 * Lê o Spec Kit do projeto fora da EDT e avisa as abas quando muda. Os arquivos de `.specify/`, `specs/`,
 * `tomahawk/` e das pastas de comandos dos agentes disparam uma nova leitura.
 */
@Service(Service.Level.PROJECT)
class SpecKitService(private val project: Project) : Disposable {

    @Volatile
    var snapshot: Snapshot = Snapshot.EMPTY
        private set

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { relevant(it.path) }) refresh()
                }
            },
        )
        refresh(0)
    }

    fun root(): Path? = project.basePath?.let(Path::of)

    fun layout(): SpecKitLayout? = root()?.let(::SpecKitLayout)

    fun refresh(delayMs: Int = REFRESH_DELAY_MS) {
        if (alarm.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ refreshNow() }, delayMs)
    }

    /** Fora da EDT. */
    fun refreshNow() {
        val loaded = try {
            load()
        } catch (e: Exception) {
            LOG.warn("O Tomahawk não conseguiu ler o Spec Kit do projeto", e)
            return
        }
        snapshot = loaded
        ApplicationManager.getApplication().invokeLater({ project.messageBus.syncPublisher(CHANGED).changed(loaded) }, project.disposed)
    }

    /** Grava a feature ativa em `.specify/feature.json` e relê. */
    fun activate(feature: Path) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { layout()?.activate(feature) }.onFailure { LOG.warn("Não deu para gravar a feature ativa", it) }
            refreshNow()
        }
    }

    private fun load(): Snapshot {
        val layout = layout() ?: return Snapshot.EMPTY
        val runs = RunStore.load(layout.root)
        if (!layout.initialized) {
            return Snapshot(layout, IntegrationInfo.NONE, false, emptyMap(), emptyList(), null, runs)
        }
        val integration = layout.integration()
        val preferred = listOf(TomahawkSettings.provider.homeDir)
        val features = layout.features().map(FeatureReport::read)
        val activeDir = layout.activeFeature(gitBranch(layout.root))
        return Snapshot(
            layout = layout,
            integration = integration,
            constitutionReady = layout.constitutionReady(),
            commands = layout.commands(preferred),
            features = features,
            active = features.firstOrNull { it.dir == activeDir },
            runs = runs,
        )
    }

    private fun gitBranch(root: Path): String? {
        val head = root.resolve(".git").resolve("HEAD").takeIf { it.isRegularFile() } ?: return null
        return runCatching { Files.readString(head).trim() }.getOrNull()?.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")
    }

    private fun relevant(path: String): Boolean {
        val base = project.basePath?.replace('\\', '/') ?: return false
        val normalized = path.replace('\\', '/')
        if (!normalized.startsWith("$base/")) return false
        val relative = normalized.removePrefix("$base/")
        return WATCHED.any { relative == it || relative.startsWith("$it/") } || relative == ".git/HEAD"
    }

    override fun dispose() = Unit

    fun interface Listener {
        fun changed(snapshot: Snapshot)
    }

    companion object {
        private const val REFRESH_DELAY_MS = 400
        private val WATCHED = listOf(".specify", "specs", RunStore.DIR, ".cursor", ".claude", ".github", ".agents", ".codex", ".gemini")
        private val LOG = logger<SpecKitService>()

        @Topic.ProjectLevel
        val CHANGED = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): SpecKitService = project.service()
    }
}
