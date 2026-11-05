package com.mio.tomahawk.delivery

import com.mio.tomahawk.delivery.github.GitHubClient
import com.mio.tomahawk.delivery.github.GitHubRepo
import com.mio.tomahawk.speckit.FeatureReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class PullRequestDraftTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun feature(): FeatureReport {
        val dir = temp.root.toPath().resolve("specs/001-photo-albums")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("spec.md"),
            """
            # Feature Specification: Álbuns de fotos

            ### User Story 1 - Criar álbum (Priority: P1)
            ### User Story 2 - Compartilhar álbum (Priority: P2)
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("tasks.md"),
            """
            ## Phase 1: Setup
            - [x] T001 Criar o projeto
            ## Phase 2: US1
            - [x] T002 [US1] Tela do álbum
            - [ ] T003 [US1] Salvar o álbum
            """.trimIndent(),
        )
        return FeatureReport.read(dir)
    }

    private fun relative(path: Path) = temp.root.toPath().relativize(path).toString().replace('\\', '/')

    @Test
    fun `branch da feature na principal e a atual nas de trabalho`() {
        val feature = feature()
        assertEquals("001-photo-albums", PullRequestDraft.branch(feature, "main", "main"))
        assertEquals("001-photo-albums", PullRequestDraft.branch(feature, "develop", "main"))
        assertEquals("001-photo-albums", PullRequestDraft.branch(feature, null, "main"))
        assertEquals("feature/albuns", PullRequestDraft.branch(feature, "feature/albuns", "main"))
        assertEquals("main", PullRequestDraft.branch(null, "main", "main"))
    }

    @Test
    fun `base prefere a do PR aberto, depois a develop, depois a padrao`() {
        val remote = RemoteInfo("001-x", "main", listOf("main", "develop", "001-x"), null)
        assertEquals("develop", PullRequestDraft.base(remote, "001-x"))
        assertEquals("main", PullRequestDraft.base(remote, "develop"))
        assertEquals("main", PullRequestDraft.base(remote.copy(branches = listOf("main")), "001-x"))
        val open = PullRequestRef(7, "https://github.com/o/r/pull/7", "feat: x", "release", false)
        assertEquals("release", PullRequestDraft.base(remote.copy(openPr = open), "001-x"))
    }

    @Test
    fun `titulo e descricao saem da spec e das tarefas`() {
        val feature = feature()
        val specTitle = PullRequestDraft.specTitle(Files.readAllLines(feature.spec!!.file))
        assertEquals("Álbuns de fotos", specTitle)
        assertEquals("feat: Álbuns de fotos", PullRequestDraft.title(feature, specTitle))
        assertEquals("feat: Photo albums", PullRequestDraft.title(feature, null))
        assertNull(PullRequestDraft.specTitle(listOf("# [FEATURE NAME]")))

        val body = PullRequestDraft.body(feature, specTitle, ::relative)
        assertTrue(body, body.startsWith("## Álbuns de fotos"))
        assertTrue(body, "`specs/001-photo-albums`" in body)
        assertTrue(body, "- **US1** Criar álbum _(P1)_" in body)
        assertTrue(body, "- 2 de 3 concluída(s)" in body)
        assertTrue(body, "- Phase 2: US1: 1/2" in body)
        assertTrue(body, "- `specs/001-photo-albums/spec.md`" in body)
        assertTrue(body, "- `specs/001-photo-albums/tasks.md`" in body)
    }

    @Test
    fun `le o repositorio do GitHub das URLs do remote`() {
        val repo = GitHubRepo("github.com", "main-io-tech", "tomahawk-ai")
        assertEquals(repo, GitHubRepo.fromRemoteUrl("https://github.com/main-io-tech/tomahawk-ai.git"))
        assertEquals(repo, GitHubRepo.fromRemoteUrl("git@github.com:main-io-tech/tomahawk-ai.git"))
        assertEquals(repo, GitHubRepo.fromRemoteUrl("ssh://git@github.com/main-io-tech/tomahawk-ai"))
        assertNull(GitHubRepo.fromRemoteUrl("https://github.com/main-io-tech"))
    }

    @Test
    fun `respostas da IA viram mensagem do commit e titulo e descricao do PR`() {
        val commit = "```text\nfeat: adiciona a Extracao\n\n- commit e push\n```"
        assertEquals("feat: adiciona a Extracao\n\n- commit e push", DeliveryWriter.clean(commit))

        val (title, body) = DeliveryWriter.pullRequest("\n**Título:** feat: Extração no Lançador\n\n## Resumo\nCommit, push e PR.\n")
        assertEquals("feat: Extração no Lançador", title)
        assertEquals("## Resumo\nCommit, push e PR.", body)

        val (plain, rest) = DeliveryWriter.pullRequest("```markdown\nfix: corrige o push\n\nCorpo\n```")
        assertEquals("fix: corrige o push", plain)
        assertEquals("Corpo", rest)
        assertEquals("" to "", DeliveryWriter.pullRequest("  "))
    }

    @Test
    fun `prompts mandam ler o contexto e nao alterar nada`() {
        val commit = DeliveryWriter.commitPrompt("tomahawk/pedidos/x.md")
        assertTrue("tomahawk/pedidos/x.md" in commit && "Do not modify any file" in commit && "Conventional Commits" in commit)
        val pr = DeliveryWriter.pullRequestPrompt("tomahawk/pedidos/y.md", "001-x", "develop", "specs/001-x")
        assertTrue("from the branch 001-x into develop" in pr && "specs/001-x" in pr && "Como testar" in pr)
    }

    @Test
    fun `erros do GitHub viram o que fazer`() {
        val draft = GitHubClient.errorMessage(422, """{"message":"Validation Failed","errors":[{"message":"Draft pull requests are not supported in this repository."}]}""")
        assertTrue(draft, "Desmarque Rascunho" in draft)
        val empty = GitHubClient.errorMessage(422, """{"message":"Validation Failed","errors":[{"message":"No commits between develop and 001-x"}]}""")
        assertTrue(empty, "Não há commits" in empty)
        assertTrue("Settings > Version Control > GitHub" in GitHubClient.errorMessage(401, """{"message":"Bad credentials"}"""))
        assertEquals("GitHub: HTTP 500", GitHubClient.errorMessage(500, "oops"))
    }
}
