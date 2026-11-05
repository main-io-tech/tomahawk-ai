package com.mio.tomahawk.delivery

/** [key] identifica o comentário entre os tipos do GitHub: `c:` nos de código, `i:` nos da conversa, `r:` nas revisões. */
data class PrComment(val key: String, val id: Long, val author: String, val body: String, val createdAt: String, val url: String)

enum class ThreadKind(val label: String, val icon: String) {
    CODE("Código", "📝"),
    CONVERSATION("Conversa", "💬"),
    REVIEW("Revisão", "🔎"),
}

/**
 * Uma conversa do PR. Nas de código, [comments] é a conversa inteira na linha e [replyTo] o primeiro comentário,
 * onde a resposta entra; nas outras, um comentário só, respondido na conversa geral citando o autor.
 * [reviewState] é o estado da revisão (`APPROVED`, `CHANGES_REQUESTED`, `COMMENTED`).
 */
data class PrThread(
    val key: String,
    val kind: ThreadKind,
    val comments: List<PrComment>,
    val replyTo: Long,
    val path: String? = null,
    val line: Int? = null,
    val diffHunk: String? = null,
    val resolved: Boolean = false,
    val outdated: Boolean = false,
    val reviewState: String? = null,
    val nodeId: String? = null,
) {
    val last: PrComment get() = comments.last()

    fun fromOthers(me: String): List<PrComment> = comments.filterNot { it.author.equals(me, ignoreCase = true) }

    /** A última palavra é de outra pessoa e a conversa não foi resolvida: espera a resposta do autor do PR. */
    fun awaitsReply(me: String): Boolean = !resolved && !last.author.equals(me, ignoreCase = true)

    val reviewLabel: String?
        get() = when (reviewState) {
            "APPROVED" -> "Aprovou"
            "CHANGES_REQUESTED" -> "Solicitou alterações"
            "COMMENTED" -> "Comentou"
            "DISMISSED" -> "Revisão descartada"
            else -> null
        }
}

/** Um PR aberto pelo usuário, com as conversas como estão agora no GitHub. */
data class WatchedPr(
    val ref: PullRequestRef,
    val head: String,
    val updatedAt: String,
    val threads: List<PrThread>,
)

/** O que o Rádio sabe dos PRs do usuário: [me] é o login dele no GitHub. */
data class RadarSnapshot(val me: String, val repo: String, val prs: List<WatchedPr>)

/** O texto que a IA lê para responder a uma conversa do PR. */
object ReplyContext {

    fun render(pr: WatchedPr, thread: PrThread, me: String, notes: String): String = buildString {
        append("# PR #").append(pr.ref.number).append(": ").append(pr.ref.title).append('\n')
        append("\nAutor do PR: @").append(me).append(" · branch `").append(pr.head).append("` → `").append(pr.ref.base).append("`\n")
        append(pr.ref.url).append('\n')
        append("\n## ").append(thread.kind.label)
        thread.path?.let { append(" em `").append(it).append('`').append(thread.line?.let { line -> ", linha $line" }.orEmpty()) }
        if (thread.outdated) append(" (desatualizada: o código mudou depois do comentário)")
        thread.reviewLabel?.let { append(" · ").append(it) }
        append('\n')
        thread.diffHunk?.takeIf { it.isNotBlank() }?.let { append("\n```diff\n").append(it.trimEnd()).append("\n```\n") }
        thread.comments.forEach { comment ->
            append("\n### @").append(comment.author)
            if (comment.author.equals(me, ignoreCase = true)) append(" (autor do PR)")
            append(" em ").append(comment.createdAt).append("\n\n")
            append(comment.body.ifBlank { "(sem texto)" }.trim()).append('\n')
        }
        if (notes.isNotBlank()) {
            append("\n## Anotações do autor para a resposta\n\n").append(notes.trim()).append('\n')
        }
    }
}
