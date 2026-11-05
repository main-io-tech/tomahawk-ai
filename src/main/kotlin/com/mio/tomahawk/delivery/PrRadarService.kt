package com.mio.tomahawk.delivery

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.TomahawkToolWindowFactory
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * O Rádio: vigia os PRs abertos pelo usuário e avisa quando outra pessoa comenta. Lembra, no workspace, o que já foi
 * lido e o que já foi avisado; a IA do Arsenal escreve as respostas, que o usuário revisa antes de enviar.
 */
@Service(Service.Level.PROJECT)
@State(name = "TomahawkRadio", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PrRadarService(private val project: Project) : PersistentStateComponent<PrRadarService.RadioState>, Disposable {

    class RadioState {
        var seen: MutableSet<String> = mutableSetOf()
        var notified: MutableSet<String> = mutableSetOf()
    }

    private var saved = RadioState()

    @Volatile
    var snapshot: RadarSnapshot? = null
        private set

    @Volatile
    var loading = false
        private set

    @Volatile
    var problem: String? = null
        private set

    @Volatile
    var checkedAt: Long = 0
        private set

    /** As conversas com a IA escrevendo a resposta e as com a resposta indo para o GitHub, pela chave. */
    val drafting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val sending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** O último erro de cada conversa, pela chave. */
    val errors: MutableMap<String, String> = ConcurrentHashMap()

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val lock = Any()

    val gateway: PullRequestGateway? get() = PullRequestGateway.available()

    override fun getState(): RadioState = saved

    override fun loadState(state: RadioState) {
        saved = state
    }

    /** Começa a vigiar: lê agora e depois a cada [POLL_MS]. */
    fun start() = refresh()

    fun refresh(delayMs: Int = 0) {
        if (gateway == null || alarm.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ poll() }, delayMs)
    }

    private fun poll() {
        val gateway = gateway ?: return
        synchronized(lock) {
            if (project.isDisposed) return
            loading = true
            publish()
            try {
                val radar = gateway.radar(project)
                snapshot = radar
                problem = null
                checkedAt = System.currentTimeMillis()
                radar?.let(::announce)
            } catch (e: Exception) {
                LOG.info("O Rádio do Tomahawk não leu os PRs", e)
                problem = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
                publish()
            }
        }
        if (!alarm.isDisposed) alarm.addRequest({ poll() }, POLL_MS)
    }

    /** Um aviso por PR com comentários de outras pessoas que ainda não foram avisados. */
    private fun announce(radar: RadarSnapshot) {
        radar.prs.forEach { pr ->
            val fresh = pr.threads.flatMap { it.fromOthers(radar.me) }.filter { it.key !in saved.notified && it.key !in saved.seen }
            if (fresh.isEmpty()) return@forEach
            saved.notified += fresh.map { it.key }
            val authors = fresh.map { "@${it.author}" }.distinct().joinToString(", ")
            val excerpt = fresh.maxBy { it.createdAt }.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140)
            notify(
                "PR #${pr.ref.number}: ${fresh.size} comentário(s) novo(s)",
                "$authors em ${pr.ref.title}" + if (excerpt.isNotEmpty()) "\n“$excerpt”" else "",
                NotificationType.INFORMATION,
                pr.ref.url,
                showRadio = true,
            )
        }
        trim(saved.notified)
    }

    fun isNew(comment: PrComment, me: String): Boolean = comment.key !in saved.seen && !comment.author.equals(me, ignoreCase = true)

    fun unread(thread: PrThread, me: String): Int = thread.comments.count { isNew(it, me) }

    /** As conversas que esperam a resposta do usuário, em todos os PRs. */
    fun pendingCount(): Int {
        val radar = snapshot ?: return 0
        return radar.prs.sumOf { pr -> pr.threads.count { it.awaitsReply(radar.me) } }
    }

    fun pendingCount(number: Int): Int {
        val radar = snapshot ?: return 0
        return radar.prs.firstOrNull { it.ref.number == number }?.threads?.count { it.awaitsReply(radar.me) } ?: 0
    }

    fun markSeen(threads: Collection<PrThread>) {
        threads.forEach { thread -> saved.seen += thread.comments.map { it.key } }
        trim(saved.seen)
        publish()
    }

    /**
     * A IA escreve a resposta à conversa [threadKey] do PR [number], usando [notes] do usuário como base. [onDone]
     * recebe o texto na EDT.
     */
    fun draftReply(number: Int, threadKey: String, notes: String, onDone: (String) -> Unit) {
        val radar = snapshot ?: return
        val pr = radar.prs.firstOrNull { it.ref.number == number } ?: return
        val thread = pr.threads.firstOrNull { it.key == threadKey } ?: return
        val root = project.basePath?.let { Path.of(it) } ?: return
        if (!drafting.add(threadKey)) return
        errors.remove(threadKey)
        publish()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Tomahawk IA: escrevendo a resposta no PR #$number", false) {
            override fun run(indicator: ProgressIndicator) {
                var file: Path? = null
                try {
                    val relative = RunStore.writeInput(root, "${System.currentTimeMillis()}-resposta-pr$number.md", ReplyContext.render(pr, thread, radar.me, notes))
                    file = root.resolve(relative)
                    indicator.text = "A IA está lendo a conversa e o código…"
                    val answer = DeliveryWriter.ask(root, DeliveryWriter.replyPrompt(relative, radar.me, number, pr.head, notes.isNotBlank()))
                    ApplicationManager.getApplication().invokeLater({ onDone(answer) }, project.disposed)
                } catch (e: Exception) {
                    if (e !is DeliveryException) LOG.warn("A IA do Tomahawk não escreveu a resposta", e)
                    errors[threadKey] = e.message ?: e.javaClass.simpleName
                } finally {
                    file?.let { runCatching { Files.deleteIfExists(it) } }
                    drafting.remove(threadKey)
                    publish()
                }
            }
        })
    }

    /** Publica [body] na conversa [threadKey] do PR [number]; [onDone] roda na EDT depois do envio. */
    fun send(number: Int, threadKey: String, body: String, onDone: () -> Unit) {
        val gateway = gateway ?: return
        val pr = snapshot?.prs?.firstOrNull { it.ref.number == number } ?: return
        val thread = pr.threads.firstOrNull { it.key == threadKey } ?: return
        if (body.isBlank()) {
            errors[threadKey] = "Escreva a resposta antes de enviar."
            publish()
            return
        }
        if (!sending.add(threadKey)) return
        errors.remove(threadKey)
        publish()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Tomahawk IA: respondendo no PR #$number", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val posted = gateway.reply(project, number, thread, body)
                    saved.seen += thread.comments.map { it.key } + posted.key
                    ApplicationManager.getApplication().invokeLater({ onDone() }, project.disposed)
                    notify("Resposta enviada ao PR #$number", posted.body.lineSequence().firstOrNull().orEmpty().take(140), NotificationType.INFORMATION, posted.url)
                } catch (e: Exception) {
                    if (e !is DeliveryException) LOG.warn("A resposta do Tomahawk não foi enviada", e)
                    errors[threadKey] = e.message ?: e.javaClass.simpleName
                } finally {
                    sending.remove(threadKey)
                    publish()
                    refresh(delayMs = 500)
                }
            }
        })
    }

    private fun trim(keys: MutableSet<String>) {
        if (keys.size <= MAX_KEYS) return
        val keep = keys.toList().takeLast(MAX_KEYS)
        keys.clear()
        keys += keep
    }

    private fun notify(title: String, content: String, type: NotificationType, url: String?, showRadio: Boolean = false) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(MissionRunner.NOTIFICATION_GROUP)
            .createNotification(title, escape(content).replace("\n", "<br>"), type)
            .setIcon(TomahawkIcons.radio)
        if (showRadio) {
            notification.addAction(NotificationAction.createSimpleExpiring("Ver no Rádio") {
                TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.RADIO)
            })
        }
        url?.let { notification.addAction(NotificationAction.createSimple("Abrir no GitHub") { BrowserUtil.browse(it) }) }
        notification.notify(project)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun publish() {
        ApplicationManager.getApplication().invokeLater({ project.messageBus.syncPublisher(CHANGED).changed() }, project.disposed)
    }

    override fun dispose() = Unit

    fun interface Listener {
        fun changed()
    }

    companion object {
        private const val POLL_MS = 180_000
        private const val MAX_KEYS = 5_000
        private val LOG = logger<PrRadarService>()

        @Topic.ProjectLevel
        val CHANGED = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): PrRadarService = project.service()
    }
}
