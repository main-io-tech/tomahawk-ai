package com.mio.tomahawk.delivery.github

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.mio.tomahawk.delivery.DeliveryException
import com.mio.tomahawk.delivery.DeliveryRequest
import com.mio.tomahawk.delivery.DeliveryResult
import com.mio.tomahawk.delivery.LocalState
import com.mio.tomahawk.delivery.PullRequestGateway
import com.mio.tomahawk.delivery.RemoteInfo
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

/** [PullRequestGateway] pelo Git da IDE e pela conta do GitHub dela; registrado em `tomahawk-github.xml`. */
class GitHubPullRequests : PullRequestGateway {

    private class Context(val repository: GitRepository, val remote: GitRemote, val repo: GitHubRepo)

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
        val branch = request.branch.trim()
        val base = request.base.trim()
        if (branch.isEmpty()) throw DeliveryException("Informe a branch do PR.")
        if (base.isEmpty()) throw DeliveryException("Escolha a branch base do PR.")
        if (branch == base) throw DeliveryException("A branch do PR é a própria base ($base): informe uma branch de trabalho, como a da feature.")

        step("Conferindo o PR no GitHub…")
        val client = client(project, context.repo)
        val existing = client.openPullRequest(context.repo, branch)
        if (existing == null && request.title.isBlank()) throw DeliveryException("Escreva o título do PR.")
        val changes = changes(project, root)
        val message = request.commitMessage.trim()
        if (changes.isNotEmpty() && message.isEmpty()) {
            throw DeliveryException("Escreva a mensagem do commit: há ${changes.size} arquivo(s) alterado(s).")
        }

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
        }

        step("Enviando $branch para ${context.remote.name}…")
        val url = context.remote.firstUrl ?: throw DeliveryException("O remote ${context.remote.name} não tem URL.")
        val pushed = Git.getInstance().push(repository, context.remote.name, url, "refs/heads/$branch:refs/heads/$branch", true)
        repository.update()
        if (!pushed.success()) {
            throw DeliveryException("O push falhou: ${pushed.errorOutputAsJoinedString.ifBlank { "o Git não disse o motivo" }}")
        }

        if (existing != null) return DeliveryResult(existing, created = false, committed = committed)
        step("Abrindo o PR de $branch para $base…")
        val pr = client.createPullRequest(context.repo, branch, base, request.title.trim(), request.body.trim(), request.draft)
        return DeliveryResult(pr, created = true, committed = committed)
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
        return GitHubClient(account.server.toApiUrl(), token)
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

        /** As execuções do Tomahawk e as configurações da IDE não entram no PR. */
        val KEPT_OUT = listOf(RunStore.DIR, ".idea")
    }
}
