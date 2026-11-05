package com.mio.tomahawk.delivery.github

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.mio.tomahawk.delivery.DeliveryException
import com.mio.tomahawk.delivery.DeliveryMode
import com.mio.tomahawk.delivery.DeliveryRequest
import com.mio.tomahawk.delivery.DeliveryResult
import com.mio.tomahawk.delivery.LocalState
import com.mio.tomahawk.delivery.PrComment
import com.mio.tomahawk.delivery.PrThread
import com.mio.tomahawk.delivery.PullRequestGateway
import com.mio.tomahawk.delivery.RadarSnapshot
import com.mio.tomahawk.delivery.RemoteInfo
import com.mio.tomahawk.delivery.ThreadKind
import com.mio.tomahawk.run.RunStore
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.util.GHCompatibilityUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** [PullRequestGateway] pelo Git da IDE e pela conta do GitHub dela; registrado em `tomahawk-github.xml`. */
class GitHubPullRequests : PullRequestGateway {

    private class Context(val repository: GitRepository, val remote: GitRemote, val repo: GitHubRepo)

    /** O login da conta em cada servidor, que não muda durante a sessão. */
    private val logins = ConcurrentHashMap<String, String>()

    override fun local(project: Project): LocalState? {
        val context = context(project) ?: return null
        val root = context.repository.root
        val branch = context.repository.currentBranchName
        val upstream = branch?.let {
            output(project, root, GitCommand.REV_PARSE, listOf("--abbrev-ref", "--symbolic-full-name", "@{u}"), failOnError = false)
                .firstOrNull()?.trim()?.ifEmpty { null }
        }
        return LocalState(
            host = context.repo.host,
            repo = context.repo.fullName,
            remote = context.remote.name,
            branch = branch,
            changes = changes(project, root),
            ahead = ahead(project, root, context.remote.name),
            upstream = upstream,
            account = accountFor(project, context.repo)?.name,
        )
    }

    override fun remote(project: Project, branch: String?): RemoteInfo {
        val context = context(project) ?: throw DeliveryException(NO_REPO)
        val client = client(project, context.repo)
        return RemoteInfo(
            branch = branch,
            defaultBranch = client.defaultBranch(context.repo),
            branches = client.branches(context.repo),
            openPr = branch?.let { client.openPullRequest(context.repo, it) },
        )
    }

    override fun deliver(project: Project, request: DeliveryRequest, step: (String) -> Unit): DeliveryResult {
        val context = context(project) ?: throw DeliveryException(NO_REPO)
        val repository = context.repository
        val root = repository.root
        val mode = request.mode
        val opensPr = mode == DeliveryMode.PULL_REQUEST
        val branch = request.branch.trim()
        val base = request.base.trim()
        if (branch.isEmpty()) throw DeliveryException("Informe a branch.")
        if (opensPr && base.isEmpty()) throw DeliveryException("Escolha a branch base do PR.")
        if (opensPr && branch == base) throw DeliveryException("A branch do PR é a própria base ($base): informe uma branch de trabalho, como a da feature.")

        val changes = changes(project, root)
        val message = request.commitMessage.trim()
        if (mode == DeliveryMode.COMMIT && changes.isEmpty()) throw DeliveryException("Não há alterações para commitar.")
        if (changes.isNotEmpty() && message.isEmpty()) {
            throw DeliveryException("Escreva a mensagem do commit: há ${changes.size} arquivo(s) alterado(s).")
        }
        val client = if (opensPr) client(project, context.repo) else null
        val existing = client?.let {
            step("Conferindo o PR no GitHub…")
            it.openPullRequest(context.repo, branch)
        }
        if (opensPr && existing == null && request.title.isBlank()) throw DeliveryException("Escreva o título do PR.")

        if (branch != repository.currentBranchName) {
            step("Criando a branch $branch…")
            git(project, root, GitCommand.CHECKOUT, listOf("-b", branch))
            repository.update()
        }
        var committed = false
        if (changes.isNotEmpty()) {
            step("Commitando ${changes.size} arquivo(s)…")
            git(project, root, GitCommand.ADD, listOf("-A", "--") + workPaths(project, root))
            val file = Files.createTempFile("tomahawk-commit", ".txt")
            try {
                Files.writeString(file, message, StandardCharsets.UTF_8)
                git(project, root, GitCommand.COMMIT, listOf("-F", file.toString()))
            } finally {
                Files.deleteIfExists(file)
            }
            committed = true
            repository.update()
        }
        if (mode == DeliveryMode.COMMIT) return DeliveryResult(branch, committed = committed, pushed = false, remote = context.remote.name)

        step("Enviando $branch para ${context.remote.name}…")
        val url = context.remote.firstUrl ?: throw DeliveryException("O remote ${context.remote.name} não tem URL.")
        val pushed = Git.getInstance().push(repository, context.remote.name, url, "refs/heads/$branch:refs/heads/$branch", true)
        repository.update()
        if (!pushed.success()) {
            throw DeliveryException("O push falhou: ${pushed.errorOutputAsJoinedString.ifBlank { "o Git não disse o motivo" }}")
        }

        val pushedResult = DeliveryResult(branch, committed = committed, pushed = true, remote = context.remote.name)
        if (client == null) return pushedResult
        if (existing != null) return pushedResult.copy(pr = existing)
        step("Abrindo o PR de $branch para $base…")
        val pr = client.createPullRequest(context.repo, branch, base, request.title.trim(), request.body.trim(), request.draft)
        return pushedResult.copy(pr = pr, created = true)
    }

    override fun changesContext(project: Project): String {
        val context = context(project) ?: throw DeliveryException(NO_REPO)
        val root = context.repository.root
        return uncommitted(project, root).ifBlank { throw DeliveryException("Não há alterações para commitar.") }
    }

    override fun branchContext(project: Project, base: String): String {
        val context = context(project) ?: throw DeliveryException(NO_REPO)
        val root = context.repository.root
        val ref = "${context.remote.name}/$base"
        val known = output(project, root, GitCommand.REV_PARSE, listOf("--verify", "--quiet", "refs/remotes/$ref"), failOnError = false).isNotEmpty()
        val branch = context.repository.currentBranchName ?: "HEAD"
        return buildString {
            append("# Pull request de ").append(branch).append(" para ").append(base).append("\n\n")
            if (known) {
                section("Commits (git log $ref..HEAD)", output(project, root, GitCommand.LOG, listOf("--no-merges", "--format=- %s%n%b", "$ref..HEAD"), failOnError = false))
                section("git diff --stat $ref...HEAD", output(project, root, GitCommand.DIFF, listOf("--stat", "$ref...HEAD", "--") + workPaths(project, root), failOnError = false))
                section("git diff $ref...HEAD", output(project, root, GitCommand.DIFF, listOf("$ref...HEAD", "--") + workPaths(project, root), failOnError = false), MAX_DIFF)
            } else {
                append("A base ").append(ref).append(" não está no repositório local: só as alterações não commitadas abaixo.\n\n")
            }
            uncommitted(project, root).takeIf { it.isNotBlank() }?.let { append("# Ainda não commitado (entra no PR)\n\n").append(it) }
        }
    }

    /** O status, o resumo e o diff do que não foi commitado, com a lista dos arquivos novos. */
    private fun uncommitted(project: Project, root: VirtualFile): String {
        val paths = workPaths(project, root)
        val status = output(project, root, GitCommand.STATUS, listOf("--porcelain", "--untracked-files=all", "--") + paths)
        if (status.isEmpty()) return ""
        val hasHead = output(project, root, GitCommand.REV_PARSE, listOf("--verify", "--quiet", "HEAD"), failOnError = false).isNotEmpty()
        val against = if (hasHead) listOf("HEAD") else listOf("--cached")
        return buildString {
            section("git status", status)
            section("git diff --stat", output(project, root, GitCommand.DIFF, listOf("--stat") + against + listOf("--") + paths, failOnError = false))
            section("git diff", output(project, root, GitCommand.DIFF, against + listOf("--") + paths, failOnError = false), MAX_DIFF)
            val untracked = status.filter { it.startsWith("??") }.map { it.substring(3) }
            if (untracked.isNotEmpty()) section("Arquivos novos, fora do diff (leia se precisar)", untracked.map { "- $it" })
        }
    }

    private fun StringBuilder.section(title: String, lines: List<String>, limit: Int = Int.MAX_VALUE) {
        if (lines.isEmpty()) return
        val text = lines.joinToString("\n")
        append("## ").append(title).append("\n\n```\n")
        append(if (text.length > limit) text.take(limit) + "\n… (cortado: ${text.length - limit} caracteres a mais)" else text)
        append("\n```\n\n")
    }

    override fun login(project: Project): Boolean {
        val server = context(project)?.repo?.host?.let { runCatching { GithubServerPath.from(it) }.getOrNull() }
        return GHAccountsUtil.requestNewAccount(server = server, project = project) != null
    }

    /** O repositório da pasta do projeto, ou o que a contém, com o primeiro remote do GitHub (o `origin` antes). */
    private fun context(project: Project): Context? {
        val root = project.basePath?.replace('\\', '/')
        val ordered = GitRepositoryManager.getInstance(project).repositories.sortedWith(
            compareByDescending<GitRepository> { it.root.path == root }.thenByDescending { root != null && root.startsWith(it.root.path + "/") },
        )
        for (repository in ordered) {
            for (remote in repository.remotes.sortedByDescending { it.name == "origin" }) {
                val repo = remote.urls.firstNotNullOfOrNull(GitHubRepo::fromRemoteUrl) ?: continue
                return Context(repository, remote, repo)
            }
        }
        return null
    }

    private fun accountFor(project: Project, repo: GitHubRepo): GithubAccount? {
        val accounts = GHAccountsUtil.accounts.filter { it.server.host.equals(repo.host, ignoreCase = true) }
        val preferred = GHAccountsUtil.getSingleOrDefaultAccount(project)
        return preferred?.takeIf { it in accounts } ?: accounts.firstOrNull()
    }

    /** Pode abrir o login do GitHub se o token tiver expirado. */
    private fun client(project: Project, repo: GitHubRepo): GitHubClient {
        val account = accountFor(project, repo)
            ?: throw DeliveryException("Nenhuma conta do GitHub em ${repo.host}. Entre em Settings > Version Control > GitHub.")
        val token = GHCompatibilityUtil.getOrRequestToken(account, project)
            ?: throw DeliveryException("Sem token para a conta ${account.name}. Entre de novo em Settings > Version Control > GitHub.")
        return GitHubClient(account.server.toApiUrl(), token, account.server.toGraphQLUrl())
    }

    override fun radar(project: Project): RadarSnapshot? {
        val context = context(project) ?: return null
        accountFor(project, context.repo) ?: return null
        val client = client(project, context.repo)
        val me = logins.getOrPut(context.repo.host) { client.currentUser() }
        val prs = client.openPullRequestsBy(context.repo, me).map { it.copy(threads = client.threads(context.repo, it.ref.number)) }
        return RadarSnapshot(me, context.repo.fullName, prs)
    }

    override fun reply(project: Project, number: Int, thread: PrThread, body: String): PrComment {
        val context = context(project) ?: throw DeliveryException(NO_REPO)
        val client = client(project, context.repo)
        val text = body.trim().ifEmpty { throw DeliveryException("Escreva a resposta.") }
        return when (thread.kind) {
            ThreadKind.CODE -> client.replyToReviewComment(context.repo, number, thread.replyTo, text)
            else -> client.addConversationComment(context.repo, number, quote(thread.last, text))
        }
    }

    /** Na conversa geral não há resposta encadeada: a resposta cita o começo do comentário e menciona o autor. */
    private fun quote(to: PrComment, body: String): String {
        val lines = to.body.trim().lines().filter { it.isNotBlank() }
        val quoted = lines.take(QUOTE_LINES).joinToString("\n") { "> $it" } + if (lines.size > QUOTE_LINES) "\n> …" else ""
        return listOf(quoted.takeIf { lines.isNotEmpty() }, "@${to.author} $body").filterNotNull().joinToString("\n\n")
    }

    private fun changes(project: Project, root: VirtualFile): List<String> =
        output(project, root, GitCommand.STATUS, listOf("--porcelain", "--untracked-files=all", "--") + workPaths(project, root))
            .filter { it.length > 3 }
            .map { it.substring(3) }

    /** Commits da branch atual que ainda não estão em nenhuma branch do [remote]. */
    private fun ahead(project: Project, root: VirtualFile, remote: String): Int =
        output(project, root, GitCommand.REV_LIST, listOf("--count", "HEAD", "--not", "--remotes=$remote"), failOnError = false)
            .firstOrNull()?.trim()?.toIntOrNull() ?: 0

    /**
     * O projeto menos [KEPT_OUT]. Os que o `.gitignore` já ignora ficam sem o exclude, que nem é preciso e, no
     * stash, faz o Git falhar.
     */
    private fun workPaths(project: Project, root: VirtualFile): List<String> {
        val ignored = output(project, root, GitCommand.CHECK_IGNORE, listOf("--") + KEPT_OUT, failOnError = false)
            .map { it.trim().trimEnd('/') }.toSet()
        return listOf(".") + KEPT_OUT.filterNot { it in ignored }.map { ":(exclude)$it" }
    }

    private fun git(project: Project, root: VirtualFile, command: GitCommand, parameters: List<String>) {
        output(project, root, command, parameters)
    }

    private fun output(project: Project, root: VirtualFile, command: GitCommand, parameters: List<String>, failOnError: Boolean = true): List<String> {
        val handler = GitLineHandler(project, root, command).apply {
            setSilent(command != GitCommand.COMMIT && command != GitCommand.CHECKOUT)
            addParameters(parameters)
        }
        val result = Git.getInstance().runCommand(handler)
        if (failOnError && !result.success()) {
            throw DeliveryException("Git: ${result.errorOutputAsJoinedString.ifBlank { "o comando ${command.name()} falhou" }}")
        }
        return if (result.success()) result.output else emptyList()
    }

    private companion object {
        const val NO_REPO = "O projeto não é um repositório Git com remote do GitHub."
        const val MAX_DIFF = 60_000
        const val QUOTE_LINES = 4

        /** As execuções do Tomahawk e as configurações da IDE não entram no PR. */
        val KEPT_OUT = listOf(RunStore.DIR, ".idea")
    }
}
