package com.mio.tomahawk.delivery.github

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mio.tomahawk.delivery.DeliveryException
import com.mio.tomahawk.delivery.PullRequestRef
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A API REST do GitHub com o token da conta da IDE, só no que o PR precisa. Faz rede de forma síncrona: fora da EDT. */
class GitHubClient(private val apiUrl: String, private val token: String) {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun defaultBranch(repo: GitHubRepo): String = request("GET", repoPath(repo)).asJsonObject.string("default_branch")

    fun branches(repo: GitHubRepo): List<String> {
        val names = mutableListOf<String>()
        for (page in 1..MAX_PAGES) {
            val batch = request("GET", "${repoPath(repo)}/branches?per_page=100&page=$page").asJsonArray
            batch.forEach { names += it.asJsonObject.string("name") }
            if (batch.size() < 100) break
        }
        return names
    }

    /** O PR aberto com a [branch] do próprio repositório como head, se houver. */
    fun openPullRequest(repo: GitHubRepo, branch: String): PullRequestRef? =
        request("GET", "${repoPath(repo)}/pulls?state=open&head=${encode("${repo.owner}:$branch")}&per_page=1")
            .asJsonArray.firstOrNull()?.asJsonObject?.let(::pullRequest)

    fun createPullRequest(repo: GitHubRepo, head: String, base: String, title: String, body: String, draft: Boolean): PullRequestRef {
        val payload = JsonObject().apply {
            addProperty("title", title)
            addProperty("head", head)
            addProperty("base", base)
            addProperty("body", body)
            addProperty("draft", draft)
        }
        return pullRequest(request("POST", "${repoPath(repo)}/pulls", payload).asJsonObject)
    }

    private fun pullRequest(json: JsonObject) = PullRequestRef(
        number = json["number"].asInt,
        url = json.string("html_url"),
        title = json.string("title"),
        base = json.getAsJsonObject("base").string("ref"),
        draft = json.get("draft")?.takeUnless { it is JsonNull }?.asBoolean == true,
    )

    private fun request(method: String, path: String, payload: JsonObject? = null): JsonElement {
        val body = payload?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI(apiUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", "Tomahawk-IA")
            .apply { if (payload != null) header("Content-Type", "application/json") }
            .method(method, body)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response)
        return runCatching { JsonParser.parseString(response.body()) }.getOrNull()
            ?: throw DeliveryException("Resposta inesperada do GitHub (HTTP ${response.statusCode()}).")
    }

    private fun check(response: HttpResponse<String>) {
        val status = response.statusCode()
        if (status in 200..299) return
        throw DeliveryException(errorMessage(status, response.body()))
    }

    private fun repoPath(repo: GitHubRepo) = "/repos/${repo.owner}/${repo.name}"

    private fun encode(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8)

    companion object {
        private const val API_VERSION = "2022-11-28"
        private const val MAX_PAGES = 10

        /** O erro do GitHub em português, com o que fazer nos casos comuns ao abrir um PR. */
        internal fun errorMessage(status: Int, body: String): String {
            val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            val details = json?.getAsJsonArray("errors")?.mapNotNull { error ->
                if (error.isJsonObject) error.asJsonObject.optString("message") else error.asString
            }.orEmpty()
            val message = listOfNotNull(json?.optString("message")).plus(details).joinToString(": ").ifEmpty { "HTTP $status" }
            return when {
                "OAuth App access restrictions" in message ->
                    "A organização bloqueia o login da IDE pelo navegador. Entre com um token pessoal (escopo repo) em Settings > Version Control > GitHub."
                "Draft pull requests are not supported" in message ->
                    "O GitHub não aceita PR em rascunho neste repositório (no plano gratuito, só nos públicos). Desmarque Rascunho e tente de novo."
                "No commits between" in message -> "Não há commits entre a base e a branch: não há o que revisar no PR."
                "A pull request already exists" in message -> "Já existe um PR aberto para esta branch."
                status == 401 -> "O token do GitHub foi recusado. Entre de novo em Settings > Version Control > GitHub."
                status == 403 -> "O GitHub negou o acesso: $message"
                status == 404 -> "Não encontrado no GitHub, ou a conta não tem acesso ao repositório: $message"
                else -> "GitHub: $message"
            }
        }

        private fun JsonObject.string(key: String): String = optString(key) ?: throw DeliveryException("Resposta do GitHub sem o campo $key.")

        private fun JsonObject.optString(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.asString
    }
}
