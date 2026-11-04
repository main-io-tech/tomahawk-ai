package com.mio.tomahawk.view

/** Um item da árvore de pastas do plano: [depth] começa em 0; [note] é o comentário `# …` da linha. */
data class TreeEntry(val name: String, val depth: Int, val directory: Boolean, val note: String?)

/**
 * A arquitetura que o plano descreve: a árvore de pastas da seção "Project Structure" vira uma árvore visual, e o
 * contexto técnico com as pastas de primeiro nível vira um diagrama em camadas (plataforma, interface, aplicação,
 * dados), com testes, metas e restrições ao lado.
 */
object Architecture {

    private val TREE_GLYPHS = Regex("""[├└│]|^\s*[|`]--""")
    private val APP_ROLES = setOf("modelo", "serviço", "API", "CLI", "biblioteca", "dados", "back-end")
    private val PREFIX = Regex("""^[\s│├└─|`+\\-]*""")

    /** Um bloco de código parece uma árvore de pastas: tem os traços de árvore, ou várias linhas terminadas em `/`. */
    fun isTree(code: String): Boolean {
        val lines = code.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return false
        return lines.count { TREE_GLYPHS.containsMatchIn(it) } >= 2 ||
            lines.count { it.substringBefore('#').trim().endsWith("/") } >= 2 && lines.none { it.contains("=") || it.contains("(") && it.contains(")") && !it.contains("#") }
    }

    fun parseTree(code: String): List<TreeEntry> {
        val raw = code.lines().filter { it.isNotBlank() }.map { line ->
            val prefix = PREFIX.find(line)?.value.orEmpty()
            val rest = line.substring(prefix.length)
            val name = rest.substringBefore("  #").substringBefore(" #").trim()
            val note = rest.substringAfter("#", "").trim().takeIf { rest.contains("#") && it.isNotEmpty() }
            Triple(prefix.length, name, note)
        }.filter { it.second.isNotEmpty() }
        if (raw.isEmpty()) return emptyList()
        val indents = raw.map { it.first }.distinct().sorted()
        val entries = raw.map { (indent, name, note) -> TreeEntry(name.trimEnd('/'), indents.indexOf(indent), name.endsWith("/"), note) }
        return entries.mapIndexed { i, entry ->
            val hasChildren = entries.getOrNull(i + 1)?.let { it.depth > entry.depth } == true
            if (hasChildren && !entry.directory) entry.copy(directory = true) else entry
        }
    }

    fun treeHtml(entries: List<TreeEntry>, esc: (String) -> String): String = buildString {
        val dirs = entries.count { it.directory }
        val files = entries.size - dirs
        append("<div class=\"ftree\"><div class=\"ftree-head\"><span>📂 Estrutura do projeto</span>")
        append("<span class=\"b\">$dirs pasta(s)</span><span class=\"b\">$files arquivo(s)</span></div><ul class=\"ftree-root\">")
        var depth = 0
        for ((i, entry) in entries.withIndex()) {
            while (depth < entry.depth) { append("<ul>"); depth++ }
            while (depth > entry.depth) { append("</ul></li>"); depth-- }
            val hasChildren = entries.getOrNull(i + 1)?.let { it.depth > entry.depth } == true
            append("<li class=\"").append(if (entry.directory) "dir" else "file").append("\"><span class=\"fnode\">")
            append("<span class=\"ficon\">").append(icon(entry)).append("</span>")
            append("<span class=\"fname-tree\">").append(esc(entry.name)).append(if (entry.directory) "/" else "").append("</span>")
            role(entry)?.let { (label, tone) -> append("<span class=\"b t-$tone\">").append(label).append("</span>") }
            entry.note?.let { append("<span class=\"fnote\">").append(esc(it)).append("</span>") }
            append("</span>")
            if (!hasChildren) append("</li>")
        }
        while (depth > 0) { append("</ul></li>"); depth-- }
        append("</ul></div>")
    }

    private fun icon(entry: TreeEntry): String {
        if (entry.directory) return if (entry.depth == 0) "🗂️" else "📁"
        val name = entry.name.lowercase()
        return when {
            "test" in name || "spec." in name -> "🧪"
            name.endsWith(".kt") || name.endsWith(".kts") -> "🟣"
            name.endsWith(".java") -> "☕"
            name.endsWith(".ts") || name.endsWith(".tsx") -> "🔷"
            name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".mjs") -> "🟨"
            name.endsWith(".py") -> "🐍"
            name.endsWith(".go") -> "🐹"
            name.endsWith(".rs") -> "🦀"
            name.endsWith(".cs") -> "🟪"
            name.endsWith(".rb") -> "💎"
            name.endsWith(".sql") || name.endsWith(".db") || name.endsWith(".sqlite") -> "🗃️"
            name.endsWith(".md") -> "📝"
            name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".toml") || name.endsWith(".xml") -> "⚙️"
            name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".scss") || name.endsWith(".vue") || name.endsWith(".svelte") -> "🎨"
            name.endsWith(".sh") || name.endsWith(".ps1") -> "💻"
            name.startsWith("dockerfile") || name.contains("docker") -> "🐳"
            else -> "📄"
        }
    }

    /** O papel de uma pasta pelo nome, para a etiqueta e para as camadas do diagrama. */
    private fun role(entry: TreeEntry): Pair<String, String>? {
        if (!entry.directory) return null
        return when (entry.name.lowercase()) {
            "models", "model", "entities", "domain" -> "modelo" to "purple"
            "services", "service", "usecases", "application" -> "serviço" to "blue"
            "api", "routes", "controllers", "handlers", "endpoints", "rest" -> "API" to "orange"
            "components", "pages", "views", "ui", "screens", "frontend", "web", "app" -> "interface" to "pink"
            "tests", "test", "__tests__", "spec" -> "testes" to "green"
            "contract", "contracts" -> "contrato" to "teal"
            "integration", "e2e" -> "integração" to "teal"
            "unit" -> "unitário" to "green"
            "cli", "bin", "cmd" -> "CLI" to "yellow"
            "lib", "libs", "core", "shared", "common" -> "biblioteca" to "gray"
            "db", "database", "migrations", "repositories", "repository", "persistence", "storage" -> "dados" to "teal"
            "backend", "server" -> "back-end" to "blue"
            "ios", "android", "mobile" -> "mobile" to "pink"
            else -> null
        }
    }

    /**
     * O diagrama em camadas a partir do contexto técnico do plano e das pastas da estrutura. Nulo se o plano não tiver
     * contexto técnico suficiente para desenhar.
     */
    fun stackHtml(context: List<Pair<String, String>>, tree: List<TreeEntry>, inline: (String) -> String): String? {
        val map = context.associate { (k, v) -> k.lowercase() to v }
        fun value(vararg keys: String) = keys.firstNotNullOfOrNull { key -> map.entries.firstOrNull { it.key.contains(key) }?.value }
            ?.takeIf { it.isNotBlank() && !it.equals("N/A", true) }
        val platform = value("target platform", "plataforma")
        val language = value("language", "linguagem")
        val deps = value("primary dependencies", "dependências", "dependencies")
        val storage = value("storage", "armazenamento")
        val type = value("project type", "tipo de projeto")
        if (listOfNotNull(platform, language, deps, storage).size < 2) return null
        val names = tree.filter { it.directory }.map { it.name.lowercase() }
        val hasFrontend = names.any { it in setOf("frontend", "web", "client", "ui", "components", "pages") } || type?.contains("web", true) == true ||
            type?.contains("mobile", true) == true
        val modules = tree.filter { it.directory && it.depth >= 1 && role(it)?.first in APP_ROLES }.map { it.name }.distinct().take(8)
        return buildString {
            append("<div class=\"arch\"><div class=\"arch-main\">")
            layer("👤", "Usuários e plataforma", platform?.let(inline) ?: "—", "pink")
            arrow()
            if (hasFrontend) {
                val ui = tree.filter { it.directory && role(it)?.first == "interface" }.map { it.name }.distinct().take(5)
                layer("🖥️", "Interface", if (ui.isEmpty()) inline(type ?: "Interface do usuário") else ui.joinToString(" ") { "<span class=\"mod\">$it/</span>" }, "orange")
                arrow()
            }
            append("<div class=\"layer app\" style=\"--t:var(--blue);--t-bg:var(--blue-bg);--t-fg:var(--blue-fg)\">")
            append("<div class=\"layer-icon\">⚙️</div><div class=\"layer-body\"><div class=\"layer-title\">Aplicação</div>")
            language?.let { append("<div class=\"layer-value\"><b>").append(inline(it)).append("</b></div>") }
            deps?.let { d ->
                append("<div class=\"deps\">")
                d.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { append("<span class=\"dep\">").append(inline(it)).append("</span>") }
                append("</div>")
            }
            if (modules.isNotEmpty()) append("<div class=\"mods\">").append(modules.joinToString("") { "<span class=\"mod\">$it/</span>" }).append("</div>")
            append("</div></div>")
            arrow()
            layer("🗄️", "Dados", storage?.let(inline) ?: "—", "teal")
            append("</div><div class=\"arch-side\">")
            listOf(
                Triple("🧪", "Testes", value("testing", "testes")),
                Triple("🎯", "Metas de desempenho", value("performance", "desempenho")),
                Triple("⛓️", "Restrições", value("constraints", "restrições")),
                Triple("📈", "Escala", value("scale", "escala")),
                Triple("🧱", "Tipo de projeto", type),
            ).filter { it.third != null }.forEach { (icon, title, text) ->
                append("<div class=\"side-card\"><div class=\"side-title\">$icon $title</div><div>").append(inline(text!!)).append("</div></div>")
            }
            append("</div></div>")
        }
    }

    private fun StringBuilder.layer(icon: String, title: String, body: String, tone: String) {
        append("<div class=\"layer\" style=\"--t:var(--$tone);--t-bg:var(--$tone-bg);--t-fg:var(--$tone-fg)\">")
        append("<div class=\"layer-icon\">$icon</div><div class=\"layer-body\"><div class=\"layer-title\">$title</div>")
        append("<div class=\"layer-value\">$body</div></div></div>")
    }

    private fun StringBuilder.arrow() {
        append("<div class=\"arch-arrow\"><span></span></div>")
    }
}
