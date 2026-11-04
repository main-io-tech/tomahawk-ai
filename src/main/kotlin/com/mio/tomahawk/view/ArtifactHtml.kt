package com.mio.tomahawk.view

import com.mio.tomahawk.speckit.Markdown
import com.mio.tomahawk.speckit.UserStory
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.nio.file.Files
import java.nio.file.Path

/** As cores da página, tiradas do tema da IDE. */
data class HtmlTheme(val dark: Boolean, val background: String, val foreground: String) {
    companion object {
        val LIGHT = HtmlTheme(false, "#FFFFFF", "#1F2328")
        val DARK = HtmlTheme(true, "#1E1F22", "#DFE1E5")
    }
}

/**
 * Transforma um Markdown do Spec Kit numa página: um cabeçalho com o tipo, o título e os metadados, um painel de
 * resumo próprio de cada artefato (histórias da spec, contexto técnico do plano, progresso das tarefas…), o
 * documento formatado com etiquetas para IDs, tarefas paralelas, histórias e pontos a esclarecer, e um sumário.
 */
object ArtifactHtml {

    private val flavour = GFMFlavourDescriptor()
    private val TITLE = Regex("""^#\s+(.+?)\s*$""")
    private val META = Regex("""\*\*([^*]{1,40}?)\*\*:\s*([^|]+)""")
    private val RUN_FIELD = Regex("""^- \*\*(.+?):\*\* (.*)$""")
    private val HEADING_HTML = Regex("""<h([1-4])>(.*?)</h\1>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG_OR_TEXT = Regex("""(<[^>]+>)|([^<]+)""")
    private val CHECKBOX_ITEM = Regex("""(<input type="checkbox"[^>]*/>)(.*?)(?=</li>|<ul>|<ol>)""", RegexOption.DOT_MATCHES_ALL)
    private val GWT = Regex("""<strong>(Given|When|Then|And|Dado|Quando|Então|E)</strong>""")
    private val NEEDS = Regex("""\[NEEDS CLARIFICATION:?\s*([^\]]*)]""", RegexOption.IGNORE_CASE)
    private val DIMENSIONS = setOf(
        "Gap", "Ambiguity", "Consistency", "Completeness", "Clarity", "Coverage", "Measurability",
        "Edge Case", "Assumption", "Dependency", "Conflict", "Traceability",
    )
    private val BRACKET_GROUP = Regex("""\[([^\[\]]+)]""")
    private val TOKENS = Regex(
        """(\[(?i:needs clarification):?\s*([^\]]*)])""" +
            """|(\((?i:priority):\s*(P\d+)\))""" +
            """|\[([^\[\]]*?(?:${DIMENSIONS.joinToString("|")}|§)[^\[\]]*)]""" +
            """|\[US(\d+)]""" +
            """|(\[P])""" +
            """|\b(T\d{3,})\b""" +
            """|\b(CHK\d+)\b""" +
            """|\b((FR|NFR|SC)-\d+)\b""",
    )
    private val CONSTITUTION_META = Regex("""\*\*(Version|Ratified|Last Amended)\*\*:\s*([^|\n]+)""")
    private val STORY_TONES = listOf("blue", "purple", "teal", "orange", "pink", "green")
    private val HEADING_1_3 = Regex("""^#{1,3}\s.*""")
    private val HEADING_2_MD = Regex("""^##\s+(.+)$""")
    private val CHECKBOX_MD = Regex("""^\s*[-*+]\s+\[([ xX])]\s+.*$""")
    private val GIVEN_STEP = Regex("""^\s*\d+\.\s+\*\*(Given|Dado)\*\*.*""")
    private const val CHIP_MAX = 60
    private val FIELD_LINE = Regex("""^\*\*[^*]{1,40}\*\*\s*:.*""")
    private val CODE_BLOCK = Regex("""<pre><code(?: class="language-([\w+.-]+)")?>(.*?)</code></pre>""", RegexOption.DOT_MATCHES_ALL)
    private val PLAIN_LANGS = setOf("", "text", "txt", "plaintext", "tree", "bash", "sh", "console", "ascii", "diagram")
    private val SCENARIO = Regex("""^\s*(\d+)\.\s+(\*\*(?:Given|Dado)\*\*.*)$""")
    private val GWT_SPLIT = Regex("""\*\*(Given|When|Then|And|But|Dado|Quando|Então|E|Mas)\*\*""")
    private val GWT_PT = mapOf("Given" to "Dado", "When" to "Quando", "Then" to "Então", "And" to "E", "But" to "Mas")
    private val MERMAID_ER = Regex("""```mermaid\s*\n\s*erDiagram""")
    private val LINK = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")

    private class Heading(val level: Int, val id: String, val text: String)

    /**
     * [displayPath] aparece no cabeçalho; [bridge] é a função JavaScript que recebe as mensagens da página (marcar
     * item, abrir link, rolagem), ou nula para uma página só de leitura; [scrollY] restaura a rolagem.
     */
    fun render(file: Path, text: String, theme: HtmlTheme, displayPath: String = file.fileName.toString(), bridge: String? = null, scrollY: Int = 0): String {
        val kind = ArtifactKind.of(file)
        val name = file.fileName.toString().lowercase()
        if (kind == ArtifactKind.CONTRACT && !name.endsWith(".md")) {
            OpenApi.parse(text, json = name.endsWith(".json"))?.let { return page(theme, kind, bridge, scrollY, contractBody(it, displayPath, text)) }
        }
        val lines = text.lines()
        val title = lines.firstNotNullOfOrNull { TITLE.matchEntire(it)?.groupValues?.get(1) }
        val (meta, body) = splitPreamble(kind, lines)
        val headings = mutableListOf<Heading>()
        val document = withIds(decorate(enrich(toHtml(scenarios(body)))), headings)
        val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }
        val content = buildString {
            append(hero(kind, cleanTitle(title, kind) ?: file.fileName.toString(), displayPath, meta, headings.count { it.level == 2 }, words))
            append(dashboard(kind, file, lines, meta))
            if (headings.size >= 3) {
                append("<details class=\"toc-inline\"><summary>Sumário · ${headings.size} seções</summary>").append(tocLinks(headings)).append("</details>")
            }
            append("<div class=\"layout").append(if (headings.size >= 3) " has-toc" else "").append("\"><article class=\"doc\">")
            append(document.ifBlank { "<p class=\"muted\">Documento vazio.</p>" })
            append("</article>")
            if (headings.size >= 3) append("<nav class=\"toc\"><div class=\"toc-title\">Sumário</div>").append(tocLinks(headings)).append("</nav>")
            append("</div>")
        }
        return page(theme, kind, bridge, scrollY, content)
    }

    private fun page(theme: HtmlTheme, kind: ArtifactKind, bridge: String?, scrollY: Int, content: String): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
        append("<style>").append(css(theme, kind)).append(Diagrams.CSS).append("</style></head>")
        append("<body class=\"").append(kind.css).append("\"><div class=\"wrap\">")
        append(content)
        append("</div>")
        if (content.contains("class=\"diagram\"")) append("<script>").append(Diagrams.mermaidScript).append("</script>")
        append("<script>").append(script(bridge, scrollY)).append("</script>")
        append("<script>").append(Diagrams.script(theme)).append("</script>")
        append("</body></html>")
    }

    /** Um contrato OpenAPI: cabeçalho, números, endpoints por tag, o diagrama dos schemas, as tabelas e o código-fonte. */
    private fun contractBody(spec: OpenApi.Spec, path: String, text: String): String = buildString {
        append("<header class=\"hero\"><div class=\"kind\"><span class=\"emoji\">${ArtifactKind.CONTRACT.emoji}</span>Contrato de API</div>")
        append("<h1>").append(esc(spec.title ?: path.substringAfterLast('/'))).append("</h1>")
        append("<div class=\"path\">").append(esc(path)).append("</div><div class=\"chips\">")
        spec.version?.let { append("<span class=\"chip\"><b>Versão</b>").append(esc(it)).append("</span>") }
        append("<span class=\"chip\"><b>OpenAPI</b>${spec.endpoints.size} endpoint(s)</span></div>")
        spec.description?.takeIf { it.isNotBlank() }?.let { append("<div class=\"note\">").append(inline(it.lines().first())).append("</div>") }
        append("</header>")
        val byMethod = spec.endpoints.groupingBy { it.method }.eachCount()
        append("<div class=\"tiles\">")
        append(tile(spec.endpoints.size, "endpoints", "blue"))
        append(tile(spec.schemas.entities.size, "schemas", "purple"))
        listOf("GET" to "green", "POST" to "blue", "PUT" to "orange", "PATCH" to "teal", "DELETE" to "red").forEach { (m, tone) ->
            byMethod[m]?.let { append(tile(it, m, tone)) }
        }
        append("</div>")
        append(OpenApi.endpointsHtml(spec, ::esc))
        if (!spec.schemas.isEmpty) {
            if (spec.schemas.relations.isNotEmpty()) {
                append("<div class=\"section-title\">Relações entre schemas</div>")
                append(Diagrams.block(DataModel.mermaidEr(spec.schemas), "Schemas e referências"))
            }
            append("<div class=\"section-title\">Schemas</div>")
            append(DataModel.tableCards(spec.schemas, ::esc, { decorate(inline(it)) }, Diagrams::block, keyLabel = "ref"))
        }
        append("<details class=\"source\"><summary>Código-fonte do contrato</summary><pre><code>").append(esc(text)).append("</code></pre></details>")
    }

    /** O documento formatado, sem cabeçalho nem painel: para a prévia sem JCEF. */
    fun simple(text: String): String = "<html><body style=\"font-family:sans-serif\">" + decorate(toHtml(text)) + "</body></html>"

    internal fun toHtml(markdown: String): String {
        val source = hardBreaks(markdown)
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        return HtmlGenerator(source, tree, flavour).generateHtml().removePrefix("<body>").removeSuffix("</body>")
            .replace("<table>", "<div class=\"table-wrap\"><table>").replace("</table>", "</table></div>")
    }

    /**
     * Os blocos de código que são desenhos: ```mermaid vira diagrama interativo, uma árvore de pastas vira a árvore
     * visual (com o texto ao lado, recolhido) e um desenho em ASCII ganha moldura de diagrama.
     */
    internal fun enrich(html: String): String = CODE_BLOCK.replace(html) { m ->
        val lang = m.groupValues[1].lowercase()
        val code = unescape(m.groupValues[2])
        when {
            lang == "mermaid" -> Diagrams.block(code.trimEnd(), Diagrams.title(code))
            lang in PLAIN_LANGS && Architecture.isTree(code) ->
                Architecture.treeHtml(Architecture.parseTree(code), ::esc) + "<details class=\"source\"><summary>Ver como texto</summary>${m.value}</details>"
            lang in PLAIN_LANGS && Diagrams.isAscii(code) -> "<div class=\"ascii\"><div class=\"ascii-head\">📐 Diagrama</div>${m.value}</div>"
            else -> m.value
        }
    }

    /** Cenários `1. **Given** …, **When** …, **Then** …` seguidos viram um bloco de fluxos, um por cenário. */
    internal fun scenarios(markdown: String): String {
        val lines = markdown.lines()
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (!SCENARIO.matches(lines[i])) {
                out += lines[i++]
                continue
            }
            val group = mutableListOf<MatchResult>()
            while (i < lines.size) {
                val match = SCENARIO.matchEntire(lines[i])
                when {
                    match != null -> group += match
                    lines[i].isBlank() && lines.getOrNull(i + 1)?.let(SCENARIO::matches) == true -> Unit
                    else -> break
                }
                i++
            }
            out += ""
            out += "<div class=\"flows\">" + group.joinToString("") { flow(it.groupValues[1], it.groupValues[2]) } + "</div>"
            out += ""
        }
        return out.joinToString("\n")
    }

    private fun flow(number: String, text: String): String = buildString {
        val parts = GWT_SPLIT.findAll(text).toList()
        append("<div class=\"flow\"><span class=\"flow-n\">").append(esc(number)).append("</span><div class=\"flow-steps\">")
        parts.forEachIndexed { i, part ->
            val end = parts.getOrNull(i + 1)?.range?.first ?: text.length
            val body = text.substring(part.range.last + 1, end).trim().trim(',', ';').trim()
            val keyword = part.groupValues[1]
            if (i > 0) append("<span class=\"flow-arrow\"></span>")
            append("<div class=\"step s-").append(gwtTone(keyword)).append("\"><span class=\"sk\">").append(GWT_PT[keyword] ?: keyword).append("</span>")
            append("<span class=\"st\">").append(inline(body)).append("</span></div>")
        }
        append("</div></div>")
    }

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    /** O conteúdo dos blocos de código cercados (```), na ordem. */
    private fun fenced(lines: List<String>): List<String> {
        val blocks = mutableListOf<String>()
        var current: MutableList<String>? = null
        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                if (current == null) current = mutableListOf() else {
                    blocks += current.joinToString("\n")
                    current = null
                }
            } else current?.add(line)
        }
        return blocks
    }

    /** Linhas seguidas de `**Chave**: valor`, como o contexto técnico do plano, ficam uma por linha, não num parágrafo só. */
    private fun hardBreaks(markdown: String): String {
        val lines = markdown.lines()
        return lines.mapIndexed { i, line ->
            val next = lines.getOrNull(i + 1)
            if (FIELD_LINE.matches(line) && next != null && FIELD_LINE.matches(next) && !line.endsWith("  ")) "$line  " else line
        }.joinToString("\n")
    }

    /** O título, os metadados do topo (`**Chave**: valor`) e o resto, que vira o documento. */
    private fun splitPreamble(kind: ArtifactKind, lines: List<String>): Pair<List<Pair<String, String>>, String> {
        val meta = mutableListOf<Pair<String, String>>()
        val firstSection = lines.indexOfFirst { it.startsWith("## ") }.let { if (it < 0) lines.size else it }
        val titleLine = lines.indexOfFirst { TITLE.matches(it) }.takeIf { it in 0 until firstSection }
        val kept = lines.mapIndexedNotNull { index, line ->
            when {
                index == titleLine -> null
                kind == ArtifactKind.RUN && RUN_FIELD.matches(line) -> {
                    RUN_FIELD.matchEntire(line)!!.let { meta += it.groupValues[1] to it.groupValues[2] }
                    null
                }
                index < firstSection && line.trimStart().startsWith("**") && META.containsMatchIn(line) -> {
                    META.findAll(line).forEach { meta += it.groupValues[1].trim() to it.groupValues[2].trim().trimEnd('\\', ' ') }
                    null
                }
                else -> line
            }
        }
        return meta to kept.joinToString("\n")
    }

    private fun cleanTitle(title: String?, kind: ArtifactKind): String? {
        title ?: return null
        val prefix = title.substringBefore(": ", "")
        return if (prefix.isNotEmpty() && prefix.length <= 32 && kind != ArtifactKind.DOCUMENT) title.substringAfter(": ") else title
    }

    // ---------------------------------------------------------------- cabeçalho

    private fun hero(kind: ArtifactKind, title: String, path: String, meta: List<Pair<String, String>>, sections: Int, words: Int): String = buildString {
        append("<header class=\"hero\"><div class=\"kind\"><span class=\"emoji\">${kind.emoji}</span>${esc(kind.label)}</div>")
        append("<h1>").append(inline(title)).append("</h1>")
        append("<div class=\"path\">").append(esc(path)).append("</div>")
        val (input, rest) = meta.partition { it.first.equals("Input", true) || it.first.equals("Entrada", true) }
        val shown = if (kind == ArtifactKind.RUN) emptyList() else rest
        val (chips, notes) = shown.partition { it.second.length <= CHIP_MAX }
        append("<div class=\"chips\">")
        chips.forEach { (key, value) -> append("<span class=\"chip\"><b>${esc(translate(key))}</b>${inline(value.removeSurrounding("`"))}</span>") }
        if (sections > 0) append("<span class=\"chip\"><b>Seções</b>$sections</span>")
        append("<span class=\"chip\"><b>Leitura</b>~${maxOf(1, words / 200)} min</span>")
        append("</div>")
        notes.forEach { (key, value) -> append("<div class=\"note\"><b>${esc(translate(key))}</b> ${decorate(inline(value))}</div>") }
        input.firstOrNull()?.let { (_, value) ->
            val label = if (kind == ArtifactKind.SPEC) "Entrada do usuário" else "Entrada"
            append("<div class=\"input\"><span class=\"input-label\">$label</span>")
            append(inline(value.removePrefix("User description:").trim().removeSurrounding("\"")))
            append("</div>")
        }
        append("</header>")
    }

    private fun translate(key: String): String = when (key.lowercase()) {
        "feature branch", "branch" -> "Branch"
        "created", "date" -> "Data"
        "status" -> "Status"
        "spec" -> "Spec"
        "prerequisites" -> "Pré-requisitos"
        "tests" -> "Testes"
        "organization" -> "Organização"
        "purpose" -> "Objetivo"
        "feature" -> "Feature"
        else -> key
    }

    // ---------------------------------------------------------------- painéis

    private fun dashboard(kind: ArtifactKind, file: Path, lines: List<String>, meta: List<Pair<String, String>>): String = when (kind) {
        ArtifactKind.SPEC -> specPanel(file, lines)
        ArtifactKind.PLAN -> planPanel(file, lines)
        ArtifactKind.TASKS -> tasksPanel(lines)
        ArtifactKind.CHECKLIST -> checklistPanel(lines)
        ArtifactKind.CONSTITUTION -> constitutionPanel(lines)
        ArtifactKind.RUN -> runPanel(meta)
        ArtifactKind.DATA_MODEL -> dataModelPanel(lines.joinToString("\n"))
        else -> ""
    }

    private fun specPanel(file: Path, lines: List<String>): String = buildString {
        val spec = Markdown.spec(file, lines)
        append("<div class=\"tiles\">")
        append(tile(spec.stories.size, "histórias de usuário", "blue"))
        append(tile(spec.requirements, "requisitos funcionais", "purple"))
        append(tile(spec.successCriteria, "critérios de sucesso", "teal"))
        append(tile(spec.clarifications, "perguntas esclarecidas", "green"))
        append(tile(spec.pending.size, if (spec.pending.isEmpty()) "pontos vagos" else "pontos a esclarecer", if (spec.pending.isEmpty()) "green" else "yellow"))
        append("</div>")
        if (spec.pending.isNotEmpty()) {
            append("<div class=\"callout warn\"><div class=\"callout-title\">⚠ ${spec.pending.size} ponto(s) a esclarecer antes do Plano de Voo</div><ul>")
            spec.pending.forEach { append("<li><span class=\"b id\">linha ${it.line + 1}</span> ${decorate(inline(it.text.trimStart('-', '*', ' ')))}</li>") }
            append("</ul><div class=\"hint\">Rode o Reconhecimento (clarify) ou edite os trechos marcados.</div></div>")
        }
        if (spec.stories.isNotEmpty()) {
            append("<div class=\"section-title\">Histórias de usuário</div><div class=\"cards\">")
            spec.stories.forEach { append(storyCard(it, lines)) }
            append("</div>")
        }
    }

    private fun storyCard(story: UserStory, lines: List<String>): String = buildString {
        val end = (story.line + 1 until lines.size).firstOrNull { lines[it].matches(HEADING_1_3) } ?: lines.size
        val block = lines.subList((story.line + 1).coerceAtMost(end), end)
        val description = block.dropWhile { it.isBlank() }.takeWhile { it.isNotBlank() && !it.trimStart().startsWith("**") && !it.trimStart().startsWith("<!--") }
            .joinToString(" ").trim()
        fun field(name: String) = block.firstOrNull { it.trimStart().startsWith("**$name**", ignoreCase = true) }?.substringAfter(":")?.trim()
        val why = field("Why this priority")
        val test = field("Independent Test")
        val scenarios = block.count { it.matches(GIVEN_STEP) }
        val tone = when (story.priority?.uppercase()) {
            "P1" -> "red"
            "P2" -> "orange"
            "P3" -> "blue"
            else -> "gray"
        }
        append("<div class=\"card story\" style=\"--t:var(--$tone)\"><h4>")
        story.priority?.let { append("<span class=\"b t-$tone\">$it</span>") }
        append("<span class=\"b us t-${STORY_TONES[story.number % STORY_TONES.size]}\">US${story.number}</span>")
        append("<span>").append(inline(story.title)).append("</span></h4>")
        if (description.isNotEmpty()) append("<p>").append(decorate(inline(description))).append("</p>")
        why?.let { append("<p class=\"why\"><b>Por que esta prioridade:</b> ").append(decorate(inline(it))).append("</p>") }
        append("<div class=\"foot\">")
        test?.let { append("<div>🧪 <b>Teste independente:</b> ").append(decorate(inline(it))).append("</div>") }
        if (scenarios > 0) append("<div>🎬 $scenarios cenário(s) de aceite</div>")
        append("</div></div>")
    }

    private fun planPanel(file: Path, lines: List<String>): String = buildString {
        val plan = Markdown.plan(file, lines)
        val dir = file.parent
        if (plan.context.isNotEmpty()) {
            append("<div class=\"section-title\">Contexto técnico</div><div class=\"grid\">")
            plan.context.forEach { (key, value) ->
                val pending = NEEDS.containsMatchIn(value)
                append("<div class=\"cell").append(if (pending) " pending" else "").append("\" title=\"").append(esc(key)).append("\">")
                append("<div class=\"cell-label\">").append(esc(contextLabel(key))).append("</div>")
                append("<div class=\"cell-value\">").append(decorate(inline(value))).append("</div></div>")
            }
            append("</div>")
        }
        append(architecture(lines, plan.context))
        if (dir != null) {
            val docs = listOf("research.md", "data-model.md", "quickstart.md", "contracts", "tasks.md")
            append("<div class=\"section-title\">Artefatos da feature</div><div class=\"chips\">")
            docs.forEach { name ->
                val exists = Files.exists(dir.resolve(name))
                val link = if (exists && name.endsWith(".md")) " href=\"$name\"" else ""
                append("<a class=\"chip ").append(if (exists) "ok" else "missing").append("\"$link>")
                append(if (exists) "✓ " else "○ ").append(esc(name)).append("</a>")
            }
            append("</div>")
        }
        if (plan.pending.isNotEmpty()) {
            append("<div class=\"callout warn\"><div class=\"callout-title\">⚠ ${plan.pending.size} ponto(s) do plano a esclarecer</div></div>")
        }
    }

    private fun contextLabel(key: String): String = when (key.lowercase()) {
        "language/version" -> "Linguagem"
        "primary dependencies" -> "Dependências"
        "storage" -> "Armazenamento"
        "testing" -> "Testes"
        "target platform" -> "Plataforma"
        "project type" -> "Tipo de projeto"
        "performance goals" -> "Metas de desempenho"
        "constraints" -> "Restrições"
        "scale/scope" -> "Escala"
        else -> key
    }

    private fun tasksPanel(lines: List<String>): String = buildString {
        val phases = Markdown.phases(lines)
        val all = phases.flatMap { it.tasks }
        val done = all.count { it.done }
        val pct = if (all.isEmpty()) 0 else done * 100 / all.size
        append("<div class=\"progress card\"><div class=\"pct\">$pct%</div><div class=\"grow\">")
        append("<div class=\"progress-label\">$done de ${all.size} tarefas concluídas</div>")
        append(bar(done, all.size, "green", "orange"))
        append("</div></div>")
        append("<div class=\"tiles\">")
        append(tile(all.size, "tarefas", "blue"))
        append(tile(done, "concluídas", "green"))
        append(tile(all.size - done, "abertas", "orange"))
        append(tile(all.count { it.parallel }, "paralelizáveis [P]", "purple"))
        append(tile(phases.size, "fases", "teal"))
        append("</div>")
        phaseFlow(phases)?.let { append("<div class=\"section-title\">Fluxo de execução</div>").append(it) }
        if (phases.isNotEmpty()) {
            append("<div class=\"section-title\">Fases</div><div class=\"rows card\">")
            phases.forEach { phase ->
                val complete = phase.done == phase.tasks.size
                append("<a class=\"row\" href=\"#").append(anchor(phase.title)).append("\"><span class=\"row-title\">")
                append(if (complete) "✅ " else "◻️ ").append(decorate(inline(phase.title))).append("</span>")
                append(bar(phase.done, phase.tasks.size, "green", "orange"))
                append("<span class=\"row-count\">${phase.done}/${phase.tasks.size}</span></a>")
            }
            append("</div>")
        }
        val stories = all.mapNotNull { task -> task.story?.let { it to task } }.groupBy({ it.first }, { it.second })
        if (stories.isNotEmpty()) {
            append("<div class=\"section-title\">Por história</div><div class=\"chips\">")
            stories.toSortedMap(compareBy { it.removePrefix("US").toIntOrNull() ?: 0 }).forEach { (story, tasks) ->
                val n = story.removePrefix("US").toIntOrNull() ?: 0
                append("<span class=\"chip\"><span class=\"b t-${STORY_TONES[n % STORY_TONES.size]}\">$story</span>${tasks.count { it.done }}/${tasks.size}</span>")
            }
            append("</div>")
        }
    }

    private fun checklistPanel(lines: List<String>): String = buildString {
        val groups = linkedMapOf<String, MutableList<Boolean>>()
        var current = "Itens"
        for ((_, line) in Markdown.visible(lines)) {
            HEADING_2_MD.matchEntire(line)?.let { current = it.groupValues[1].trim() }
            CHECKBOX_MD.matchEntire(line)?.let { groups.getOrPut(current) { mutableListOf() } += it.groupValues[1].isNotBlank() }
        }
        val items = groups.values.flatten()
        val done = items.count { it }
        val pct = if (items.isEmpty()) 0 else done * 100 / items.size
        append("<div class=\"progress card\"><div class=\"pct\">$pct%</div><div class=\"grow\">")
        append("<div class=\"progress-label\">$done de ${items.size} itens revisados</div>")
        append(bar(done, items.size, "green", "pink"))
        append("<div class=\"hint\">Os itens são marcados pelo revisor: clique na caixa para marcar ou desmarcar no arquivo.</div>")
        append("</div></div>")
        val text = lines.joinToString("\n")
        val tags = BRACKET_GROUP.findAll(text).flatMap { group -> group.groupValues[1].split(',').map(String::trim) }
            .filter { it in DIMENSIONS }.groupingBy { it }.eachCount()
        if (groups.size > 1) {
            append("<div class=\"section-title\">Categorias</div><div class=\"rows card\">")
            groups.forEach { (name, marks) ->
                append("<a class=\"row\" href=\"#").append(anchor(name)).append("\"><span class=\"row-title\">").append(inline(name)).append("</span>")
                append(bar(marks.count { it }, marks.size, "green", "pink"))
                append("<span class=\"row-count\">${marks.count { it }}/${marks.size}</span></a>")
            }
            append("</div>")
        }
        if (tags.isNotEmpty()) {
            append("<div class=\"section-title\">Dimensões de qualidade</div><div class=\"chips\">")
            tags.entries.sortedByDescending { it.value }.forEach { (tag, count) -> append("<span class=\"chip\"><span class=\"b q\">$tag</span>$count</span>") }
            append("</div>")
        }
    }

    private fun constitutionPanel(lines: List<String>): String = buildString {
        val text = lines.joinToString("\n")
        if (Regex("""\[(PROJECT_NAME|PRINCIPLE_\d+_NAME)]""").containsMatchIn(text)) {
            append("<div class=\"callout warn\"><div class=\"callout-title\">⚠ Ainda é o modelo da constituição</div>")
            append("<div class=\"hint\">Lance Regras de Engajamento (constitution) no Lançador para definir os princípios do projeto.</div></div>")
        }
        val meta = CONSTITUTION_META.findAll(text).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        if (meta.isNotEmpty()) {
            append("<div class=\"chips\">")
            meta.forEach { (key, value) ->
                val label = when (key) {
                    "Version" -> "Versão"
                    "Ratified" -> "Ratificada"
                    else -> "Última emenda"
                }
                append("<span class=\"chip\"><b>$label</b>${esc(value)}</span>")
            }
            append("</div>")
        }
        val start = lines.indexOfFirst { it.trim().matches(Regex("""^##\s+(Core Principles|Princípios).*""", RegexOption.IGNORE_CASE)) }
        if (start < 0) return@buildString
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        val section = lines.subList(start + 1, end)
        val principles = section.withIndex().filter { it.value.startsWith("### ") }
        if (principles.isEmpty()) return@buildString
        append("<div class=\"section-title\">Princípios</div><div class=\"cards\">")
        principles.forEachIndexed { i, (index, heading) ->
            val next = principles.getOrNull(i + 1)?.index ?: section.size
            val body = section.subList(index + 1, next).dropWhile { it.isBlank() }.takeWhile { it.isNotBlank() }.joinToString(" ")
            append("<div class=\"card principle\"><h4><span class=\"b t-purple\">${i + 1}</span><span>")
            append(inline(heading.removePrefix("### ").trim())).append("</span></h4>")
            if (body.isNotBlank()) append("<p>").append(decorate(inline(body))).append("</p>")
            append("</div>")
        }
        append("</div>")
    }

    private fun runPanel(meta: List<Pair<String, String>>): String = buildString {
        val fields = meta.toMap()
        val status = fields["Situação"].orEmpty()
        val tone = when {
            status.startsWith("concluída") -> "green"
            status.startsWith("falhou") -> "red"
            status.startsWith("no terminal") -> "purple"
            else -> "gray"
        }
        append("<div class=\"tiles\">")
        append(tileText(status.substringBefore(":").ifBlank { "-" }, "situação", tone))
        fields["Duração"]?.let { append(tileText(it, "duração", "blue")) }
        fields["Arquivos alterados"]?.let { append(tileText(it, "arquivos alterados", "purple")) }
        fields["Veredito"]?.let { append(tileText(it, "veredito", if (it.startsWith("Convergido") || it.startsWith("Sem")) "green" else "yellow")) }
        fields["Custo"]?.let { append(tileText(it, "custo", "teal")) }
        append("</div>")
        status.substringAfter(": ", "").takeIf { it.isNotBlank() }?.let {
            append("<div class=\"callout err\"><div class=\"callout-title\">✖ ").append(esc(it)).append("</div></div>")
        }
        append("<div class=\"chips\">")
        listOf("Etapa", "Feature", "CLI", "Início").forEach { key -> fields[key]?.let { append("<span class=\"chip\"><b>$key</b>${esc(it)}</span>") } }
        append("</div>")
    }

    // ---------------------------------------------------------------- peças

    internal fun tile(value: Int, label: String, tone: String) = tileText(value.toString(), label, tone)

    internal fun tileText(value: String, label: String, tone: String) =
        "<div class=\"tile\" style=\"--t:var(--$tone);--t-fg:var(--$tone-fg)\"><div class=\"n\">${esc(value)}</div><div class=\"l\">${esc(label)}</div></div>"

    internal fun bar(done: Int, total: Int, doneTone: String, openTone: String): String {
        if (total <= 0) return "<div class=\"bar\"></div>"
        val pct = done * 100.0 / total
        return "<div class=\"bar\"><span style=\"width:${"%.1f".format(java.util.Locale.ROOT, pct)}%;background:var(--$doneTone)\"></span>" +
            "<span style=\"width:${"%.1f".format(java.util.Locale.ROOT, 100 - pct)}%;background:var(--$openTone-bg)\"></span></div>"
    }

    private fun tocLinks(headings: List<Heading>): String = headings.joinToString("") {
        "<a class=\"h${it.level}\" href=\"#${it.id}\">${esc(it.text)}</a>"
    }

    /** Negrito e código de uma linha de Markdown, com o resto escapado. */
    internal fun inline(text: String): String = esc(text)
        .replace(LINK, "<a href=\"$2\">$1</a>")
        .replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
        .replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
        .replace(Regex("""(?<![\w*])\*([^*\s][^*]*?)\*(?!\w)"""), "<em>$1</em>")

    /**
     * Etiquetas nos textos (fora de código): pontos a esclarecer, IDs de tarefa, requisito e checklist, `[P]`,
     * `[USn]`, prioridades, dimensões de qualidade e referências; e Given/When/Then destacados.
     */
    internal fun decorate(html: String): String {
        var codeDepth = 0
        val out = StringBuilder()
        for (match in TAG_OR_TEXT.findAll(html)) {
            val tag = match.groups[1]?.value
            if (tag != null) {
                val lower = tag.lowercase()
                if (lower.startsWith("<code") || lower.startsWith("<pre")) codeDepth++
                if (lower.startsWith("</code") || lower.startsWith("</pre")) codeDepth = (codeDepth - 1).coerceAtLeast(0)
                out.append(tag)
                continue
            }
            val text = match.value
            out.append(if (codeDepth > 0) text else badges(text))
        }
        return out.toString()
            .replace(GWT) { "<strong class=\"gwt ${gwtTone(it.groupValues[1])}\">${it.groupValues[1]}</strong>" }
            .replace(CHECKBOX_ITEM) { "${it.groupValues[1]}<span class=\"txt\">${it.groupValues[2]}</span>" }
    }

    private fun gwtTone(word: String) = when (word) {
        "Given", "Dado" -> "given"
        "When", "Quando" -> "when"
        "Then", "Então" -> "then"
        else -> "and"
    }

    // ---------------------------------------------------------------- modelo de dados, arquitetura e fluxo das fases

    private fun dataModelPanel(text: String): String = buildString {
        val model = DataModel.parse(text)
        if (model.isEmpty) return@buildString
        append("<div class=\"tiles\">")
        append(tile(model.entities.size, "entidades", "blue"))
        append(tile(model.entities.sumOf { it.fields.size }, "campos", "purple"))
        append(tile(model.relations.size, "relacionamentos", "teal"))
        append(tile(model.entities.sumOf { e -> e.fields.count { it.foreignKey != null } }, "chaves estrangeiras", "orange"))
        append(tile(model.entities.sumOf { it.rules.size }, "regras de validação", "pink"))
        append(tile(model.entities.count { it.transitions.isNotEmpty() }, "ciclos de vida", "green"))
        append("</div>")
        if (!MERMAID_ER.containsMatchIn(text)) {
            append("<div class=\"section-title\">Diagrama entidade-relacionamento</div>")
            append(Diagrams.block(DataModel.mermaidEr(model), "Diagrama ER gerado do modelo"))
        }
        append("<div class=\"section-title\">Tabelas</div>")
        append(DataModel.tableCards(model, ::esc, { decorate(inline(it)) }, Diagrams::block))
    }

    private fun architecture(lines: List<String>, context: List<Pair<String, String>>): String {
        val tree = fenced(lines).firstOrNull(Architecture::isTree)?.let(Architecture::parseTree).orEmpty()
        val stack = Architecture.stackHtml(context, tree) { decorate(inline(it)) } ?: return ""
        return "<div class=\"section-title\">Arquitetura</div>$stack"
    }

    /** O fluxo das fases do tasks.md: em sequência, com as fases de história de usuário em paralelo entre si. */
    private fun phaseFlow(phases: List<com.mio.tomahawk.speckit.TaskPhase>): String? {
        if (phases.size < 2) return null
        val story = Regex("""user story|história|\bUS\d""", RegexOption.IGNORE_CASE)
        fun label(i: Int): String {
            val phase = phases[i]
            val clean = phase.title.replace(Regex("""\(Priority:[^)]*\)"""), "").replace(Regex("""[^\p{L}\p{N}\s:.,/&+()-]"""), "").trim()
            val head = clean.substringBefore(":", "").ifBlank { "Fase ${i + 1}" }.replace("Phase", "Fase")
            val rest = clean.substringAfter(":", clean).trim().let { if (it.length > 38) it.take(36) + "…" else it }
            val status = if (phase.done == phase.tasks.size) "✓ ${phase.tasks.size}/${phase.tasks.size}" else "${phase.done}/${phase.tasks.size}"
            return "<b>${head.replace("\"", "")}</b><br/>${rest.replace("\"", "")}<br/><small>$status</small>"
        }
        val code = buildString {
            appendLine("flowchart LR")
            phases.indices.forEach { appendLine("    P$it[\"${label(it)}\"]") }
            var previous = listOf<Int>()
            var i = 0
            while (i < phases.size) {
                val group = if (story.containsMatchIn(phases[i].title)) {
                    val start = i
                    while (i < phases.size && story.containsMatchIn(phases[i].title)) i++
                    (start until i).toList()
                } else listOf(i++)
                previous.forEach { from -> group.forEach { to -> appendLine("    P$from --> P$to") } }
                previous = group
            }
            appendLine("    classDef done fill:#2DA44E2E,stroke:#2DA44E,stroke-width:2px")
            appendLine("    classDef partial fill:#0969DA26,stroke:#2F81F7,stroke-width:2px")
            appendLine("    classDef todo fill:#8C959F1F,stroke:#8C959F,stroke-dasharray:4 3")
            phases.forEachIndexed { index, phase ->
                val cls = when {
                    phase.done == phase.tasks.size -> "done"
                    phase.done > 0 -> "partial"
                    else -> "todo"
                }
                appendLine("    class P$index $cls")
            }
        }
        return Diagrams.block(code, "Fluxo de execução das fases")
    }

    /** Uma passada só, para uma etiqueta não cair dentro de outra (como o `FR-001` de `[Spec §FR-001]`). */
    private fun badges(text: String): String = TOKENS.replace(text) { m ->
        val g = m.groups
        when {
            g[1] != null -> "<mark class=\"nc\" title=\"Precisa de esclarecimento\">⚠ ${m.groupValues[2].ifBlank { "precisa de esclarecimento" }}</mark>"
            g[3] != null -> priorityBadge(m.groupValues[4])
            g[5] != null -> m.groupValues[5].split(',').map(String::trim).filter(String::isNotEmpty).joinToString(" ") { token ->
                when {
                    token in DIMENSIONS -> "<span class=\"b q\">$token</span>"
                    '§' in token -> "<span class=\"b ref\">$token</span>"
                    else -> "<span class=\"b\">$token</span>"
                }
            }
            g[6] != null -> "<span class=\"b t-${STORY_TONES[(m.groupValues[6].toIntOrNull() ?: 0) % STORY_TONES.size]}\">US${m.groupValues[6]}</span>"
            g[7] != null -> "<span class=\"b par\" title=\"Pode rodar em paralelo\">∥ P</span>"
            g[8] != null || g[9] != null -> "<span class=\"b id\">${m.value}</span>"
            else -> "<span class=\"b ${if (m.groupValues[11] == "SC") "t-teal" else "t-purple"}\">${m.value}</span>"
        }
    }

    private fun priorityBadge(priority: String): String {
        val p = priority.uppercase()
        val tone = when (p) {
            "P1" -> "red"
            "P2" -> "orange"
            "P3" -> "blue"
            else -> "gray"
        }
        return "<span class=\"b t-$tone\">$p</span>"
    }

    private fun withIds(html: String, headings: MutableList<Heading>): String {
        val used = mutableMapOf<String, Int>()
        return HEADING_HTML.replace(html) { match ->
            val level = match.groupValues[1].toInt()
            val inner = match.groupValues[2]
            val plain = plainText(inner)
            val base = slug(plain)
            val count = used.merge(base, 1, Int::plus)!!
            val id = if (count == 1) base else "$base-$count"
            if (level in 2..3) headings += Heading(level, id, plain)
            "<h$level id=\"$id\">$inner</h$level>"
        }
    }

    private fun plainText(html: String) = html.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()

    /** O id que o título `## [text]` recebe no documento, com as mesmas etiquetas: para os atalhos do painel. */
    private fun anchor(text: String): String = slug(plainText(decorate(inline(text))))

    internal fun slug(text: String): String = java.text.Normalizer.normalize(text.replace(Regex("<[^>]+>"), ""), java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "secao" }

    internal fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------------------------------------------------------------- script e estilo

    private fun script(bridge: String?, scrollY: Int): String = """
        (function(){
          var send = ${bridge ?: "function(){}"};
          window.__tomahawkSend = send;
          var interactive = ${bridge != null};
          document.querySelectorAll('input.task-list-item-checkbox').forEach(function(cb, i){
            if (!interactive) return;
            cb.disabled = false;
            cb.title = cb.checked ? 'Desmarcar no arquivo' : 'Marcar no arquivo';
            cb.addEventListener('click', function(e){
              e.preventDefault();
              var li = cb.closest('li');
              send('toggle:' + i + ':' + (li ? li.textContent.trim().slice(0, 120) : ''));
            });
          });
          document.addEventListener('click', function(e){
            var a = e.target.closest('a[href]');
            if (!a) return;
            var href = a.getAttribute('href');
            if (href.charAt(0) === '#') {
              e.preventDefault();
              var el = document.getElementById(href.slice(1));
              if (el) el.scrollIntoView({behavior: 'smooth', block: 'start'});
              return;
            }
            e.preventDefault();
            send('open:' + href);
          });
          var timer;
          window.addEventListener('scroll', function(){
            clearTimeout(timer);
            timer = setTimeout(function(){ send('scroll:' + Math.round(window.scrollY)); }, 150);
          });
          window.scrollTo(0, $scrollY);
        })();
    """.trimIndent()

    private fun css(theme: HtmlTheme, kind: ArtifactKind): String = baseCss(
        theme,
        when (kind) {
            ArtifactKind.SPEC -> "orange"
            ArtifactKind.PLAN, ArtifactKind.CONTRACT, ArtifactKind.RUN -> "blue"
            ArtifactKind.TASKS -> "green"
            ArtifactKind.CHECKLIST -> "pink"
            ArtifactKind.CONSTITUTION -> "purple"
            ArtifactKind.RESEARCH, ArtifactKind.DATA_MODEL -> "teal"
            ArtifactKind.QUICKSTART -> "yellow"
            else -> "gray"
        },
    )

    /** As cores (claras ou escuras, como a IDE) e os componentes comuns às páginas do Tomahawk; [accent] é o tom da página. */
    internal fun baseCss(theme: HtmlTheme, accent: String): String {
        val d = theme.dark
        fun c(light: String, dark: String) = if (d) dark else light
        return """
        :root{
          --bg:${theme.background};--fg:${theme.foreground};
          --muted:${c("#656D76", "#9198A1")};--border:${c("#D8DEE4", "#393B40")};
          --card:${c("#F6F8FA", "#2B2D30")};--code-bg:${c("#EFF2F5", "#26282C")};
          --shadow:${c("0 1px 2px rgba(31,35,40,.06)", "0 1px 2px rgba(0,0,0,.35)")};
          --green:${c("#2DA44E", "#3FB950")};--green-bg:${c("#DAFBE1", "#13361F")};--green-fg:${c("#116329", "#56D364")};
          --red:${c("#CF222E", "#F85149")};--red-bg:${c("#FFEBE9", "#4A1A1E")};--red-fg:${c("#A40E26", "#FF7B72")};
          --yellow:${c("#D4A72C", "#BB8009")};--yellow-bg:${c("#FFF1C2", "#4A3D12")};--yellow-fg:${c("#7A5A00", "#E3C065")};
          --blue:${c("#0969DA", "#2F81F7")};--blue-bg:${c("#DDF4FF", "#12304F")};--blue-fg:${c("#0550AE", "#79C0FF")};
          --purple:${c("#8250DF", "#A371F7")};--purple-bg:${c("#FBEFFF", "#3A2350")};--purple-fg:${c("#6639BA", "#D2A8FF")};
          --orange:${c("#BC4C00", "#DB6D28")};--orange-bg:${c("#FFF1E5", "#4A2A12")};--orange-fg:${c("#953800", "#FFA657")};
          --pink:${c("#BF3989", "#DB61A2")};--pink-bg:${c("#FFEFF7", "#4A1A35")};--pink-fg:${c("#99286E", "#FF9BCE")};
          --teal:${c("#1B7C83", "#39C5CF")};--teal-bg:${c("#D8F5F7", "#10373A")};--teal-fg:${c("#0A5A60", "#56D4DD")};
          --gray:${c("#8C959F", "#6E7681")};--gray-bg:${c("#EAEEF2", "#30363D")};--gray-fg:${c("#57606A", "#9198A1")};
          --accent:var(--$accent);--accent-bg:var(--$accent-bg);--accent-fg:var(--$accent-fg);
        }
        *{box-sizing:border-box}
        html,body{margin:0;background:var(--bg);color:var(--fg);font:13px/1.6 -apple-system,"Segoe UI",Inter,Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased}
        .wrap{max-width:1180px;margin:0 auto;padding:18px 24px 64px}
        a{color:var(--blue-fg)}
        .muted{color:var(--muted)}
        .hero{position:relative;border-radius:16px;padding:20px 22px;background:linear-gradient(135deg,var(--accent-bg) 0%,var(--card) 75%);border:1px solid var(--border);overflow:hidden}
        .hero::after{content:"";position:absolute;right:-60px;top:-60px;width:200px;height:200px;border-radius:50%;background:var(--accent);opacity:.08}
        .hero .kind{display:inline-flex;gap:6px;align-items:center;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--accent-fg)}
        .hero .emoji{font-size:15px}
        .hero h1{margin:6px 0 2px;font-size:24px;line-height:1.25;font-weight:750;word-break:break-word}
        .hero .path{color:var(--muted);font:12px "JetBrains Mono",Consolas,monospace;word-break:break-all}
        .chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}
        .chip{display:inline-flex;gap:6px;align-items:center;padding:3px 10px;border-radius:999px;background:var(--bg);border:1px solid var(--border);font-size:12px;color:var(--fg);text-decoration:none;max-width:100%}
        .chip b{color:var(--muted);font-weight:600;white-space:nowrap}
        a.chip.ok{border-color:var(--green);color:var(--green-fg)} .chip.missing{color:var(--muted);border-style:dashed}
        .input{position:relative;margin-top:14px;padding:10px 14px;border-left:3px solid var(--accent);background:var(--bg);border-radius:8px;font-style:italic}
        .input-label{display:block;font-style:normal;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:var(--muted);margin-bottom:2px}
        .tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(150px,100%),1fr));gap:10px;margin:16px 0}
        .tile{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:12px 14px 10px 16px;position:relative;overflow:hidden;box-shadow:var(--shadow)}
        .tile::before{content:"";position:absolute;left:0;top:0;bottom:0;width:4px;background:var(--t)}
        .tile .n{font-size:24px;font-weight:750;line-height:1.2;color:var(--t-fg);word-break:break-word}
        .tile .l{color:var(--muted);font-size:12px}
        .section-title{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);margin:20px 0 8px}
        .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr));gap:12px}
        .card{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:14px 16px;box-shadow:var(--shadow)}
        .card.story{border-top:3px solid var(--t)}
        .card h4{margin:0 0 6px;font-size:14px;display:flex;gap:6px;align-items:center;flex-wrap:wrap;line-height:1.4}
        .card p{margin:6px 0}
        .card .why{color:var(--muted);font-size:12px}
        .card .foot{margin-top:10px;padding-top:8px;border-top:1px dashed var(--border);color:var(--muted);font-size:12px;display:grid;gap:4px}
        .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(220px,100%),1fr));gap:10px}
        .wrap,.doc,.card,.cell,.tile,.callout{min-width:0;overflow-wrap:anywhere}
        .cell{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:10px 12px}
        .cell.pending{border-color:var(--yellow);background:var(--yellow-bg)}
        .cell-label{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:var(--muted)}
        .cell-value{margin-top:2px;word-break:break-word}
        .progress{display:flex;align-items:center;gap:16px;margin-top:16px}
        .progress .pct{font-size:30px;font-weight:800;color:var(--green-fg);min-width:72px}
        .progress .grow{flex:1;min-width:0}
        .progress-label{margin-bottom:6px;font-weight:600}
        .bar{height:8px;border-radius:99px;background:var(--gray-bg);overflow:hidden;display:flex;min-width:60px}
        .bar span{display:block;height:100%}
        .rows{padding:4px 14px}
        .row{display:grid;grid-template-columns:minmax(0,1fr) minmax(60px,140px) 52px;gap:12px;align-items:center;padding:8px 0;border-bottom:1px dashed var(--border);color:var(--fg);text-decoration:none}
        .row:last-child{border-bottom:none} .row:hover .row-title{color:var(--accent-fg)}
        .row-title{min-width:0;word-break:break-word} .row-count{text-align:right;color:var(--muted);font-variant-numeric:tabular-nums}
        .hint{color:var(--muted);font-size:12px;margin-top:6px}
        .callout{border-radius:10px;padding:10px 14px;margin:14px 0;border:1px solid}
        .callout ul{margin:6px 0 0;padding-left:18px}
        .callout-title{font-weight:700}
        .callout.warn{background:var(--yellow-bg);border-color:var(--yellow);color:var(--yellow-fg)}
        .callout.err{background:var(--red-bg);border-color:var(--red);color:var(--red-fg)}
        .b{display:inline-block;padding:0 7px;border-radius:999px;font-size:11px;font-weight:700;line-height:18px;vertical-align:1px;background:var(--gray-bg);color:var(--gray-fg);white-space:nowrap}
        .b.id{font-family:"JetBrains Mono",Consolas,monospace;font-weight:600;background:transparent;border:1px solid var(--border);color:var(--muted);line-height:16px}
        .b.par{background:var(--blue-bg);color:var(--blue-fg)}
        .b.q{background:var(--pink-bg);color:var(--pink-fg)}
        .b.ref{background:var(--teal-bg);color:var(--teal-fg);font-weight:600}
        .t-green{background:var(--green-bg)!important;color:var(--green-fg)!important}
        .t-red{background:var(--red-bg)!important;color:var(--red-fg)!important}
        .t-yellow{background:var(--yellow-bg)!important;color:var(--yellow-fg)!important}
        .t-blue{background:var(--blue-bg)!important;color:var(--blue-fg)!important}
        .t-purple{background:var(--purple-bg)!important;color:var(--purple-fg)!important}
        .t-orange{background:var(--orange-bg)!important;color:var(--orange-fg)!important}
        .t-pink{background:var(--pink-bg)!important;color:var(--pink-fg)!important}
        .t-teal{background:var(--teal-bg)!important;color:var(--teal-fg)!important}
        .t-gray{background:var(--gray-bg)!important;color:var(--gray-fg)!important}
        mark.nc{background:var(--yellow-bg);color:var(--yellow-fg);padding:1px 7px;border-radius:6px;font-weight:600;border:1px solid var(--yellow)}
        strong.gwt{font-size:11px;text-transform:uppercase;letter-spacing:.05em;padding:1px 6px;border-radius:5px}
        .gwt.given{background:var(--blue-bg);color:var(--blue-fg)} .gwt.when{background:var(--purple-bg);color:var(--purple-fg)}
        .gwt.then{background:var(--green-bg);color:var(--green-fg)} .gwt.and{background:var(--gray-bg);color:var(--gray-fg)}
        details.toc-inline{margin:18px 0 0;background:var(--card);border:1px solid var(--border);border-radius:10px;padding:8px 14px}
        details.toc-inline summary{cursor:pointer;font-weight:600;color:var(--muted)}
        details.toc-inline a{display:block;padding:2px 0;color:var(--fg);text-decoration:none} details.toc-inline a.h3{padding-left:14px;color:var(--muted)}
        .layout{display:grid;grid-template-columns:minmax(0,1fr);gap:28px;margin-top:8px}
        .toc{display:none}
        @media (min-width:1100px){
          .layout.has-toc{grid-template-columns:minmax(0,1fr) 230px}
          .toc{display:block;position:sticky;top:16px;align-self:start;max-height:calc(100vh - 32px);overflow:auto;font-size:12px;border-left:2px solid var(--border);padding:4px 0 4px 14px;margin-top:24px}
          details.toc-inline{display:none}
        }
        .toc-title{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);margin-bottom:6px}
        .toc a{display:block;color:var(--muted);text-decoration:none;padding:3px 0;line-height:1.35} .toc a.h3{padding-left:12px;font-size:11.5px}
        .toc a:hover{color:var(--accent-fg)}
        .doc{min-width:0;word-wrap:break-word}
        .note{margin-top:8px;font-size:12px;color:var(--fg)} .note b{color:var(--muted);margin-right:4px}
        .doc h1{font-size:22px}
        .doc h2{font-size:18px;margin:30px 0 10px;padding-bottom:6px;border-bottom:1px solid var(--border);display:flex;gap:10px;align-items:center;flex-wrap:wrap;scroll-margin-top:12px}
        .doc h2::before{content:"";width:4px;height:18px;border-radius:2px;background:var(--accent);flex:none}
        .doc h3{font-size:15px;margin:22px 0 8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap;scroll-margin-top:12px}
        .doc h4{font-size:12px;margin:16px 0 6px;color:var(--muted);text-transform:uppercase;letter-spacing:.05em}
        .doc p{margin:8px 0}
        .doc ul,.doc ol{padding-left:22px;margin:6px 0} .doc li{margin:4px 0}
        .doc li.task-list-item{list-style:none;margin:2px 0 2px -22px;padding:6px 10px;border-radius:8px;border:1px solid transparent;display:flex;gap:10px;align-items:flex-start}
        .doc li.task-list-item:hover{background:var(--card);border-color:var(--border)}
        .doc li.task-list-item .txt{flex:1;min-width:0}
        .doc li.task-list-item:has(input:checked) .txt{color:var(--muted)}
        input.task-list-item-checkbox{appearance:none;-webkit-appearance:none;width:17px;height:17px;border:2px solid var(--gray);border-radius:5px;margin:2px 0 0;flex:none;cursor:pointer;background:var(--bg);position:relative;transition:all .15s}
        input.task-list-item-checkbox:hover{border-color:var(--green)}
        input.task-list-item-checkbox:checked{background:var(--green);border-color:var(--green)}
        input.task-list-item-checkbox:checked::after{content:"";position:absolute;left:4px;top:0;width:4px;height:9px;border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg)}
        input.task-list-item-checkbox:disabled{cursor:default}
        .doc code{font-family:"JetBrains Mono",Consolas,monospace;font-size:12px;background:var(--code-bg);padding:1px 6px;border-radius:5px;word-break:break-word}
        .doc pre{background:var(--code-bg);border:1px solid var(--border);border-radius:10px;padding:12px 14px;overflow:auto;line-height:1.45}
        .doc pre code{background:none;padding:0;word-break:normal}
        .doc .table-wrap{overflow-x:auto;margin:12px 0;border:1px solid var(--border);border-radius:10px}
        .doc table{border-collapse:separate;border-spacing:0;width:100%}
        .doc th{background:var(--card);text-align:left;font-weight:700;padding:8px 12px;border-bottom:1px solid var(--border)}
        .doc td{padding:8px 12px;border-bottom:1px solid var(--border);vertical-align:top}
        .doc tr:last-child td{border-bottom:none} .doc tbody tr:nth-child(even) td{background:var(--card)}
        .doc blockquote{margin:12px 0;padding:8px 14px;border-left:4px solid var(--accent);background:var(--card);border-radius:0 10px 10px 0}
        .doc hr{border:none;border-top:1px dashed var(--border);margin:22px 0}
        .doc img{max-width:100%}
        @media (max-width:560px){
          .wrap{padding:12px 12px 40px}
          .hero{padding:16px}
          .hero h1{font-size:20px}
          .tiles{grid-template-columns:repeat(2,minmax(0,1fr))}
          .row{grid-template-columns:minmax(0,1fr) 44px;gap:4px 10px}
          .row .bar{grid-column:1 / -1;grid-row:2}
          .progress .pct{font-size:24px;min-width:56px}
        }
        """.trimIndent()
    }
}
