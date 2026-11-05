package com.mio.tomahawk.delivery.github

/** Repositório no GitHub, lido da URL de um remote do Git. */
data class GitHubRepo(val host: String, val owner: String, val name: String) {

    val fullName get() = "$owner/$name"

    companion object {
        private val SCP = Regex("""^[\w.-]+@([\w.-]+):(.+)$""")
        private val URL = Regex("""^(?:https?|ssh|git)://(?:[^@/]+@)?([\w.-]+)(?::\d+)?/(.+)$""")

        /** Aceita `https://github.com/dono/repo.git`, `git@github.com:dono/repo.git` e `ssh://git@github.com/dono/repo`. */
        fun fromRemoteUrl(url: String): GitHubRepo? {
            val text = url.trim()
            val (host, path) = (SCP.matchEntire(text) ?: URL.matchEntire(text))?.destructured ?: return null
            val parts = path.removeSuffix("/").removeSuffix(".git").split('/').filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            return GitHubRepo(host.lowercase(), parts[0], parts[1])
        }
    }
}
