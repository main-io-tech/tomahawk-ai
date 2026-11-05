package com.mio.tomahawk.delivery

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.run.MissionRunner

/**
 * A entrega da missão: o estado do repositório para o Lançador mostrar e o commit, o push e o PR, fora da EDT.
 * O que o GitHub diz ([RemoteInfo]) só é relido ao trocar de branch, depois de uma entrega ou a pedido.
 */
@Service(Service.Level.PROJECT)
class PullRequestService(private val project: Project) : Disposable {

    /** [local] nulo: o projeto não é um repositório Git com remote do GitHub. [problem]: o GitHub não respondeu. */
    class State(val local: LocalState?, val remote: RemoteInfo?, val problem: String?)

    @Volatile
    var state: State? = null
        private set

    @Volatile
    var loading = false
        private set

    /** O passo da entrega em andamento; nulo sem entrega. */
    @Volatile
    var step: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var remoteWanted = false

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    val gateway: PullRequestGateway? get() = PullRequestGateway.available()

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (step == null && events.any { relevant(it.path) }) refresh(delayMs = FILES_DELAY_MS)
                }
            },
        )
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener { if (MissionRunner.getInstance(project).progress == null) refresh() },
        )
    }

    /** Relê o repositório; com [remote], também o GitHub. */
    fun refresh(remote: Boolean = false, delayMs: Int = REFRESH_DELAY_MS) {
        val gateway = gateway ?: return
        if (alarm.isDisposed) return
        if (remote) remoteWanted = true
        alarm.cancelAllRequests()
        alarm.addRequest({ load(gateway) }, delayMs)
    }

    private fun load(gateway: PullRequestGateway) {
        val withRemote = remoteWanted
        remoteWanted = false
        loading = true
        publish()
        val previous = state
        state = try {
            val local = gateway.local(project)
            val known = previous?.local
            when {
                local == null -> State(null, null, null)
                local.account == null -> State(local, null, null)
                !withRemote && known != null && known.branch == local.branch && known.account == local.account ->
                    State(local, previous.remote, previous.problem)
                else -> try {
                    State(local, gateway.remote(project, local.branch), null)
                } catch (e: Exception) {
                    LOG.info("O GitHub não respondeu ao Tomahawk", e)
                    State(local, previous?.remote?.takeIf { it.branch == local.branch }, e.message ?: e.javaClass.simpleName)
                }
            }
        } catch (e: Exception) {
            LOG.warn("O Tomahawk não conseguiu ler o repositório Git", e)
            State(previous?.local, previous?.remote, e.message ?: e.javaClass.simpleName)
        }
        loading = false
        publish()
    }

    /** Commit, push e PR em segundo plano; o resultado vira notificação. */
    fun deliver(request: DeliveryRequest) {
        val gateway = gateway ?: return
        if (step != null) return
        FileDocumentManager.getInstance().saveAllDocuments()
        step = "Preparando a extração…"
        lastError = null
        publish()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Tomahawk IA: abrindo o pull request", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = gateway.deliver(project, request) { text ->
                        step = text
                        indicator.text = text
                        publish()
                    }
                    notifyDelivered(result)
                } catch (e: Exception) {
                    if (e !is DeliveryException) LOG.warn("A entrega do Tomahawk falhou", e)
                    lastError = e.message ?: e.javaClass.simpleName
                    notify("O pull request não foi aberto", lastError.orEmpty(), NotificationType.ERROR, null)
                } finally {
                    step = null
                    refresh(remote = true, delayMs = 0)
                }
            }
        })
    }

    fun login() {
        val gateway = gateway ?: return
        if (gateway.login(project)) refresh(remote = true, delayMs = 0)
    }

    private fun notifyDelivered(result: DeliveryResult) {
        val pr = result.pr
        val title = if (result.created) "PR #${pr.number} aberto" else "PR #${pr.number} atualizado"
        val content = buildString {
            append(pr.title)
            append(if (result.created) " → ${pr.base}" else ": os commits novos foram enviados")
            if (pr.draft) append(" (rascunho)")
        }
        notify(title, content, NotificationType.INFORMATION, pr.url)
    }

    private fun notify(title: String, content: String, type: NotificationType, url: String?) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(MissionRunner.NOTIFICATION_GROUP)
            .createNotification(title, escape(content), type)
            .setIcon(TomahawkIcons.lancador)
        url?.let { notification.addAction(NotificationAction.createSimple("Abrir no GitHub") { BrowserUtil.browse(it) }) }
        notification.notify(project)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun publish() {
        ApplicationManager.getApplication().invokeLater({ project.messageBus.syncPublisher(CHANGED).changed() }, project.disposed)
    }

    /** Arquivos do projeto e as refs do Git; o resto de `.git/` muda a todo comando e não interessa. */
    private fun relevant(path: String): Boolean {
        val base = project.basePath?.replace('\\', '/') ?: return false
        val normalized = path.replace('\\', '/')
        if (!normalized.startsWith("$base/")) return false
        val relative = normalized.removePrefix("$base/")
        if (relative == ".git" || relative.startsWith(".git/")) {
            return relative == ".git/HEAD" || relative.startsWith(".git/refs/")
        }
        return !relative.startsWith(".idea/") && !relative.startsWith(".gradle/") && !relative.startsWith("build/")
    }

    override fun dispose() = Unit

    fun interface Listener {
        fun changed()
    }

    companion object {
        private const val REFRESH_DELAY_MS = 300
        private const val FILES_DELAY_MS = 1_500
        private val LOG = logger<PullRequestService>()

        @Topic.ProjectLevel
        val CHANGED = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): PullRequestService = project.service()
    }
}
