package com.mio.tomahawk.delivery

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** O repositório Git do projeto e o que falta subir: [changes] entram no commit, [ahead] commits ainda não estão no remote. */
data class LocalState(
    val host: String,
    /** `dono/nome` no GitHub. */
    val repo: String,
    val remote: String,
    /** Nula sem branch (HEAD destacado). */
    val branch: String?,
    val changes: List<String>,
    val ahead: Int,
    /** A branch remota que a atual acompanha, como `origin/001-feature`; nula antes do primeiro push. */
    val upstream: String?,
    /** A conta do GitHub da IDE que acessa o repositório; nula sem login. */
    val account: String?,
)

/** O que o GitHub diz da [branch]: as branches que podem ser a base e o PR que já está aberto para ela. */
data class RemoteInfo(val branch: String?, val defaultBranch: String, val branches: List<String>, val openPr: PullRequestRef?)

data class PullRequestRef(val number: Int, val url: String, val title: String, val base: String, val draft: Boolean)

/**
 * A entrega: [branch] diferente da atual é criada a partir dela; com alterações, o commit usa [commitMessage].
 * O push vai para a [branch] no remote e o PR, de [branch] para [base].
 */
data class DeliveryRequest(
    val branch: String,
    val base: String,
    val commitMessage: String,
    val title: String,
    val body: String,
    val draft: Boolean,
)

/** [created] é falso quando o PR já existia e só recebeu os commits novos. */
data class DeliveryResult(val pr: PullRequestRef, val created: Boolean, val committed: Boolean)

class DeliveryException(message: String) : Exception(message)

/**
 * O Git e o GitHub da IDE, para subir o que a missão gerou e abrir o PR. Vem dos plugins Git e GitHub, que são
 * opcionais: sem eles, não há extensão e o Lançador não oferece a entrega.
 */
interface PullRequestGateway {

    /** Fora da EDT. Nulo quando o projeto não é um repositório Git com remote do GitHub. */
    fun local(project: Project): LocalState?

    /** Fora da EDT; faz rede. */
    fun remote(project: Project, branch: String?): RemoteInfo

    /** Fora da EDT; faz Git e rede. [step] conta o passo em andamento. */
    fun deliver(project: Project, request: DeliveryRequest, step: (String) -> Unit): DeliveryResult

    /** Na EDT. Abre o login do GitHub da IDE; devolve true se uma conta entrou. */
    fun login(project: Project): Boolean

    companion object {
        val EP = ExtensionPointName<PullRequestGateway>("com.mio.tomahawk.pullRequestGateway")

        fun available(): PullRequestGateway? = EP.extensionList.firstOrNull()
    }
}
