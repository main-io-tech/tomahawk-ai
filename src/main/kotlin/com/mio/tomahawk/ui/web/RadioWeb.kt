package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.mio.tomahawk.base.TomahawkToolWindowFactory
import com.mio.tomahawk.delivery.PrRadarService
import com.mio.tomahawk.delivery.PrThread
import com.mio.tomahawk.delivery.PullRequestService
import com.mio.tomahawk.delivery.RadarSnapshot
import com.mio.tomahawk.delivery.ThreadKind
import com.mio.tomahawk.delivery.WatchedPr
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.ui.openFile
import com.mio.tomahawk.view.ArtifactHtml
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * O Rádio: os comentários dos PRs abertos pelo usuário, por PR e por conversa, com o trecho do diff e o que é novo.
 * Cada conversa tem a caixa de resposta, que a IA pode escrever a partir da conversa e do código.
 */
class RadioWeb(project: Project) : WebTab(project) {

    override val accent = "purple"

    private val radar = PrRadarService.getInstance(project)
    private var filter = PENDING
    /** O que o usuário escreveu em cada conversa, pela chave dela. */
    private val replies = mutableMapOf<String, String>()
    /** O que o usuário recolheu ou abriu, pela chave da dobra; o que não está aqui segue o padrão de cada bloco. */
    private val folds = mutableMapOf<String, Boolean>()

    init {
        project.messageBus.connect(this).subscribe(PrRadarService.CHANGED, PrRadarService.Listener { refresh() })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        if (radar.snapshot == null && !radar.loading) radar.refresh()
        refresh()
    }

    override fun body(): String = buildString {
        if (radar.gateway == null) {
            append(WebUi.header("📻", "Rádio", "Comentários dos seus PRs", "O Rádio usa os plugins Git e GitHub da IDE, que estão desligados."))
            return@buildString
        }
        val snap = radar.snapshot
        if (snap == null) {
            append(WebUi.header("📻", "Rádio", "Comentários dos seus PRs", if (radar.loading) "Sintonizando os PRs no GitHub…" else null))
            radar.problem?.let { append(notice("err", "⚠️", "O GitHub não respondeu: ${esc(it)}", WebUi.button("Tentar de novo", "refresh"))) }
            if (!radar.loading && radar.problem == null) {
                append(notice("warn", "🔑", "O Rádio precisa de um repositório com remote do GitHub e da conta do GitHub da IDE.", WebUi.button("🔑 Entrar no GitHub", "login")))
            }
            return@buildString
        }
        val pending = snap.prs.sumOf { pr -> pr.threads.count { it.awaitsReply(snap.me) } }
        val unread = snap.prs.sumOf { pr -> pr.threads.sumOf { radar.unread(it, snap.me) } }
        val chips = buildString {
            append(WebUi.chip("PRs abertos", "${snap.prs.size}"))
            append(WebUi.chip("Novos", "$unread", if (unread > 0) "purple" else null))
            append(WebUi.chip("Aguardando resposta", "$pending", if (pending > 0) "yellow" else "green"))
            append(WebUi.chip("GitHub", "@" + esc(snap.me)))
            append(
                if (TomahawkSettings.ready) WebUi.chip("IA", "${esc(TomahawkSettings.provider.title)} ✓", "green", cmd = "arsenal")
                else WebUi.chip("IA", "testar integração", "yellow", cmd = "arsenal"),
            )
        }
        val title = if (pending > 0) "$pending conversa(s) aguardando sua resposta" else "Nenhuma conversa aguardando resposta"
        val checked = if (radar.checkedAt > 0) " · checado às ${TIME.format(Instant.ofEpochMilli(radar.checkedAt).atZone(ZoneId.systemDefault()))}" else ""
        val subtitle = "Vigiando ${snap.prs.size} PR(s) abertos por você em ${esc(snap.repo)}$checked · a cada 3 min" + if (radar.loading) " · atualizando…" else ""
        append(WebUi.header("📻", "Rádio · Comentários dos seus PRs", title, subtitle, chips))
        radar.problem?.let { append(notice("err", "⚠️", "A última leitura falhou: ${esc(it)}", WebUi.button("Tentar de novo", "refresh"))) }

        append(DiffHunk.css())
        append(
            WebUi.section(
                "Conversas",
                WebUi.button("▸ Recolher tudo", "fold-all", "ghost", title = "Recolhe todas as conversas") +
                    WebUi.button("▾ Expandir tudo", "unfold-all", "ghost", title = "Abre todas as conversas") +
                    WebUi.button("↻ Atualizar", "refresh", "ghost") +
                    WebUi.button("✓ Marcar tudo como lido", "seen-all", "ghost", enabled = unread > 0),
            ),
        )
        append("<div class=\"row-btns\" style=\"margin:0 0 12px\">")
        append(WebUi.seg("filter", listOf(PENDING to "⏳ Aguardando resposta", OTHERS to "👥 De outros devs", ALL to "Todas"), filter, send = "filter"))
        append("</div>")
        if (snap.prs.isEmpty()) {
            append("<div class=\"empty2\"><div class=\"big\">📭</div><h3>Nenhum PR aberto por você</h3>")
            append("<p>Abra um pela Extração, no Lançador: o Rádio passa a vigiar os comentários dele.</p>")
            append(WebUi.button("🚀 Ir para o Lançador", "launcher")).append("</div>")
            return@buildString
        }
        snap.prs.forEach { append(pullRequest(it, snap)) }
    }

    private fun pullRequest(pr: WatchedPr, snap: RadarSnapshot): String = buildString {
        val shown = pr.threads.filter { thread ->
            when (filter) {
                PENDING -> thread.awaitsReply(snap.me)
                OTHERS -> thread.fromOthers(snap.me).isNotEmpty()
                else -> true
            }
        }
        val unread = pr.threads.sumOf { radar.unread(it, snap.me) }
        val pending = pr.threads.count { it.awaitsReply(snap.me) }
        val fold = prFold(pr)
        append("<div class=\"card2 rpr").append(if (folded(fold, false)) " folded" else "").append("\" data-fold=\"").append(WebUi.attr(fold)).append("\">")
        append("<div class=\"rpr-head\" data-fold-toggle title=\"Recolher ou expandir o PR\"><span class=\"fold-ic\"></span>")
        append("<div class=\"grow\"><div class=\"rpr-title\"><span class=\"num\">#${pr.ref.number}</span> ").append(esc(pr.ref.title))
        if (pr.ref.draft) append(" <span class=\"tag\">rascunho</span>")
        append("</div><div class=\"chips\" style=\"margin-top:6px\">")
        append(WebUi.chip("Branch", "${esc(pr.head)} → ${esc(pr.ref.base)}"))
        append(WebUi.chip("Conversas", "${pr.threads.size}"))
        if (unread > 0) append(WebUi.chip("Novos", "$unread", "purple"))
        append(WebUi.chip("Aguardando", "$pending", if (pending > 0) "yellow" else "green"))
        append("</div></div><div class=\"row-btns\">")
        append(WebUi.button("🌐 GitHub", "url", "ghost", arg = pr.ref.url))
        append(WebUi.button("✓ Lido", "seen-pr", "ghost", arg = "${pr.ref.number}", enabled = unread > 0))
        append("</div></div><div class=\"rpr-body\">")
        if (shown.isEmpty()) {
            val why = when (filter) {
                PENDING -> "Nenhuma conversa esperando você neste PR."
                OTHERS -> "Ninguém comentou neste PR ainda."
                else -> "Este PR ainda não tem conversas."
            }
            append("<div class=\"status-line\">").append(why).append("</div>")
        }
        shown.forEach { append(thread(pr, it, snap.me)) }
        append("</div></div>")
    }

    private fun thread(pr: WatchedPr, thread: PrThread, me: String): String = buildString {
        val arg = "${pr.ref.number}|${thread.key}"
        val unread = radar.unread(thread, me)
        val drafting = thread.key in radar.drafting
        val sending = thread.key in radar.sending
        val fold = threadFold(thread)
        val awaits = thread.awaitsReply(me)
        append("<div class=\"rthread").append(if (unread > 0) " new" else "").append(if (awaits) " wait" else "").append(if (thread.resolved) " done" else "")
        append(if (folded(fold, thread.resolved)) " folded" else "").append("\" data-form=\"reply\" data-fold=\"").append(WebUi.attr(fold)).append("\">")
        append("<div class=\"rt-head\" data-fold-toggle title=\"Recolher ou expandir a conversa\"><span class=\"fold-ic\"></span>")
        append("<span class=\"rt-kind\">").append(thread.kind.icon).append(' ').append(thread.kind.label)
        thread.reviewLabel?.let { append(": ").append(esc(it)) }
        append("</span>")
        thread.path?.let { path ->
            val where = path.substringAfterLast('/') + thread.line?.let { ":$it" }.orEmpty()
            append("<span class=\"st-cmd clickable\" data-cmd=\"file\" data-arg=\"").append(WebUi.attr(path)).append("\"")
            thread.line?.let { append(" data-line=\"$it\"") }
            append(" title=\"").append(WebUi.attr(path)).append(" · abrir no editor\">").append(esc(where)).append("</span>")
        }
        append("<span class=\"tags\">")
        if (unread > 0) append("<span class=\"tag t-new\">$unread novo(s)</span>")
        if (awaits) append("<span class=\"tag t-wait\">aguardando resposta</span>")
        if (thread.resolved) append("<span class=\"tag t-done\">✓ resolvida</span>")
        if (thread.outdated) append("<span class=\"tag\">desatualizada</span>")
        append("</span><span class=\"grow\"></span>")
        if (thread.comments.size > 1) append("<span class=\"rt-count\" title=\"Comentários na conversa\">💬 ${thread.comments.size}</span>")
        append("<span class=\"rt-time\">").append(esc(date(thread.last.createdAt))).append("</span></div>")
        append("<div class=\"rt-peek\" data-fold-toggle>").append(avatar(thread.last.author, me))
        append("<b>@").append(esc(thread.last.author)).append("</b><span class=\"txt\">").append(esc(peek(thread.last.body))).append("</span></div>")
        append("<div class=\"rt-body\">")
        thread.diffHunk?.takeIf { it.isNotBlank() && thread.kind == ThreadKind.CODE }?.let {
            val key = "h|${thread.key}"
            append(DiffHunk.html(project, it, thread.path, thread.line, key, folded(key, true), HUNK_LINES))
        }
        append("<div class=\"rcs\">")
        thread.comments.forEach { comment ->
            val mine = comment.author.equals(me, ignoreCase = true)
            val fresh = radar.isNew(comment, me)
            append("<div class=\"rc").append(if (mine) " me" else "").append(if (fresh) " new" else "").append("\">")
            append(avatar(comment.author, me)).append("<div class=\"rc-main\">")
            append("<div class=\"rc-head\"><b>@").append(esc(comment.author)).append("</b>")
            if (mine) append("<span class=\"tag\">você</span>")
            if (fresh) append("<span class=\"tag t-new\">novo</span>")
            append("<span class=\"grow\"></span><span class=\"rc-time\">").append(esc(date(comment.createdAt))).append("</span>")
            comment.url.takeIf { it.isNotBlank() }?.let {
                append("<span class=\"rc-link clickable\" data-cmd=\"url\" data-arg=\"").append(WebUi.attr(it)).append("\" title=\"Abrir o comentário no GitHub\">↗</span>")
            }
            append("</div>")
            append("<div class=\"rc-body\">").append(if (comment.body.isBlank()) "<i>(sem texto)</i>" else ArtifactHtml.toHtml(comment.body)).append("</div></div></div>")
        }
        append("</div>")
        radar.errors[thread.key]?.let { append(notice("err", "⚠️", esc(it))) }
        if (drafting) append(notice("info", "✨", "A IA está lendo a conversa e o código para escrever a resposta…"))
        append("<div class=\"rt-reply\">")
        append("<div class=\"field\" style=\"margin:0 0 8px\"><textarea class=\"in\" name=\"reply\" data-draft=\"reply|${WebUi.attr(thread.key)}\" ")
        append("placeholder=\"Escreva a resposta, ou anotações para a IA (por exemplo: concordo, vou corrigir no próximo commit)\" style=\"min-height:80px\">")
        append(esc(replies[thread.key].orEmpty())).append("</textarea></div>")
        append("<div class=\"st-tools\" style=\"margin-top:0\"><div class=\"row-btns\">")
        val aiWhy = when {
            drafting -> "A IA já está escrevendo"
            sending -> "A resposta está sendo enviada"
            !TomahawkSettings.ready -> "Teste a integração com a IA no Arsenal"
            else -> null
        }
        append(WebUi.button(if (drafting) "⏳ Escrevendo…" else "✨ Responder com IA", "ai", arg = arg, enabled = aiWhy == null,
            title = aiWhy ?: "A IA do Arsenal escreve a resposta a partir da conversa e do código; o que estiver na caixa vira a orientação dela"))
        append(WebUi.button("✓ Marcar como lida", "seen", "ghost", arg = arg, enabled = unread > 0))
        append(WebUi.button("🌐 GitHub", "url", "ghost", arg = thread.last.url.ifBlank { pr.ref.url }))
        append("</div><div class=\"row-btns\">")
        append(WebUi.button(if (sending) "⏳ Enviando…" else "📨 Enviar resposta", "send", "primary", arg = arg, enabled = !sending && !drafting,
            title = if (thread.kind == ThreadKind.CODE) "Responde na conversa da linha" else "Responde na conversa do PR, citando @${thread.last.author}"))
        append("</div></div></div></div></div>")
    }

    private fun prFold(pr: WatchedPr) = "pr|${pr.ref.number}"

    private fun threadFold(thread: PrThread) = "t|${thread.key}"

    private fun folded(key: String, default: Boolean) = folds[key] ?: default

    /** A inicial do autor num círculo com a cor dele; o do usuário leva o tom da página. */
    private fun avatar(author: String, me: String): String {
        val mine = author.equals(me, ignoreCase = true)
        val hue = Math.floorMod(author.lowercase().hashCode(), 360)
        val style = if (mine) "" else " style=\"--av:hsl($hue 55% 52%)\""
        return "<span class=\"rc-av\"$style title=\"@${WebUi.attr(author)}\">${esc(author.take(1).uppercase())}</span>"
    }

    /** O começo do comentário numa linha, sem a marcação do Markdown. */
    private fun peek(body: String): String {
        val text = body.replace(Regex("```[\\s\\S]*?(```|$)"), " [código] ").replace(Regex("[`*_>#~]+"), "").replace(Regex("\\s+"), " ").trim()
        return when {
            text.isEmpty() -> "(sem texto)"
            text.length > PEEK_CHARS -> text.take(PEEK_CHARS).trimEnd() + "…"
            else -> text
        }
    }

    private fun date(iso: String): String =
        runCatching { DATE.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(iso)

    private fun notice(kind: String, icon: String, html: String, action: String = ""): String =
        "<div class=\"notice $kind\" style=\"margin:8px 0\"><span class=\"ni\">$icon</span><div class=\"nb\">$html" +
            (if (action.isNotEmpty()) "<div>$action</div>" else "") + "</div></div>"

    private fun target(data: JsonObject): Pair<WatchedPr, PrThread>? {
        val (number, key) = data.str("arg")?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return null
        val pr = radar.snapshot?.prs?.firstOrNull { it.ref.number.toString() == number } ?: return null
        return pr to (pr.threads.firstOrNull { it.key == key } ?: return null)
    }

    override fun onMessage(command: String, data: JsonObject) {
        when (command) {
            "draft" -> data.str("arg")?.takeIf { it.startsWith("reply|") }?.let { replies[it.removePrefix("reply|")] = data.str("text").orEmpty() }
            "filter" -> {
                filter = data.str("value") ?: PENDING
                refresh(force = true)
            }
            "fold" -> data.str("arg")?.let { folds[it] = data.str("folded") == "true" }
            "fold-all", "unfold-all" -> radar.snapshot?.prs?.let { prs ->
                prs.forEach { pr ->
                    folds[prFold(pr)] = false
                    pr.threads.forEach { folds[threadFold(it)] = command == "fold-all" }
                }
                refresh(force = true)
            }
            "refresh" -> radar.refresh()
            "login" -> PullRequestService.getInstance(project).login().also { radar.refresh() }
            "arsenal" -> TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.ARSENAL)
            "launcher" -> TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.LAUNCHER)
            "url" -> data.str("arg")?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
            "file" -> {
                val root = project.basePath ?: return
                val path = data.str("arg") ?: return
                openFile(project, Path.of(root).resolve(path), ((data.int("line") ?: 1) - 1).coerceAtLeast(0))
            }
            "seen" -> target(data)?.let { (_, thread) -> radar.markSeen(listOf(thread)) }
            "seen-pr" -> radar.snapshot?.prs?.firstOrNull { it.ref.number.toString() == data.str("arg") }?.let { radar.markSeen(it.threads) }
            "seen-all" -> radar.snapshot?.prs?.let { prs -> radar.markSeen(prs.flatMap { it.threads }) }
            "ai" -> target(data)?.let { (pr, thread) ->
                val notes = data.str("reply").orEmpty()
                replies[thread.key] = notes
                radar.draftReply(pr.ref.number, thread.key, notes) { answer ->
                    replies[thread.key] = answer
                    refresh(force = true)
                }
            }
            "send" -> target(data)?.let { (pr, thread) ->
                val text = data.str("reply").orEmpty()
                replies[thread.key] = text
                radar.send(pr.ref.number, thread.key, text) {
                    replies.remove(thread.key)
                    refresh(force = true)
                }
            }
        }
    }

    private companion object {
        const val PENDING = "pending"
        const val OTHERS = "others"
        const val ALL = "all"
        const val HUNK_LINES = 8
        const val PEEK_CHARS = 140
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
