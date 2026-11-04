package com.mio.tomahawk.view

/** Um campo de uma entidade ou tabela, com o que dá para inferir do texto: tipo, chaves e restrições. */
data class Field(
    val name: String,
    val type: String,
    val primaryKey: Boolean = false,
    val foreignKey: String? = null,
    val unique: Boolean = false,
    val required: Boolean = false,
    val optional: Boolean = false,
    val indexed: Boolean = false,
    val default: String? = null,
    val enumValues: List<String> = emptyList(),
    val note: String = "",
)

enum class Cardinality(val mermaid: String, val label: String) {
    ONE_TO_MANY("||--o{", "1:N"),
    MANY_TO_ONE("}o--||", "N:1"),
    ONE_TO_ONE("||--||", "1:1"),
    MANY_TO_MANY("}o--o{", "N:M"),
}

data class Relation(val from: String, val to: String, val cardinality: Cardinality, val label: String)

data class Transition(val from: String, val to: String, val event: String?)

data class Entity(
    val name: String,
    val description: String,
    val fields: List<Field>,
    val rules: List<String>,
    val transitions: List<Transition>,
    val line: Int,
)

data class DataModelInfo(val entities: List<Entity>, val relations: List<Relation>) {
    val isEmpty: Boolean get() = entities.isEmpty()
}

/**
 * Lê as entidades que o `/speckit-plan` escreve no `data-model.md`, nos formatos que as IAs costumam usar: uma
 * seção `### Entidade` com uma tabela (Campo | Tipo | Restrições | Descrição) ou uma lista (`- **campo**: tipo,
 * restrições`), e as linhas de relacionamentos, regras de validação e transições de estado.
 */
object DataModel {

    private val HEADING = Regex("""^(#{2,4})\s+(.+?)\s*$""")
    private val LABEL = Regex("""^\s*(?:[-*]\s+)?\*\*([^*]+?)\*\*\s*:?\s*(.*)$""")
    private val LIST_ITEM = Regex("""^\s*[-*+]\s+(.*)$""")
    private val FIELD_BOLD = Regex("""^\*\*`?([A-Za-z_][\w.\[\]]*)`?\*\*\s*(?:\(([^)]*)\))?\s*[:—–-]\s*(.*)$""")
    private val FIELD_CODE = Regex("""^`([A-Za-z_][\w.\[\]]*)`\s*(?:\(([^)]*)\))?\s*[:—–-]?\s*(.*)$""")
    private val FIELD_PLAIN = Regex("""^([a-z_][\w]*)\s*(?:\(([^)]*)\))?\s*[:—–]\s*(.*)$""")
    private val TYPE = Regex(
        """\b(uuid|guid|ulid|string|text|varchar(?:\(\d+\))?|char(?:\(\d+\))?|int(?:eger)?|int32|int64|bigint|smallint|long|short|number|numeric|""" +
            """decimal(?:\([\d,\s]+\))?|float|double|real|money|bool(?:ean)?|date|datetime|timestamp(?:tz)?|time|instant|localdate(?:time)?|""" +
            """duration|enum|json|jsonb|object|map|dict|array|list|set|blob|binary|bytes|url|uri|email|path|file|hash)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val GENERIC_TYPE = Regex("""\b((?:List|Array|Set|Map|Optional)<[^>]+>|\w+\[])""")
    private val PK = Regex("""primary key|chave primária|\bPK\b""", RegexOption.IGNORE_CASE)
    private val FK = Regex(
        """(?:foreign key|chave estrangeira|\bFK\b|references?|referencia|→|->)\s*(?:to\s+|para\s+)?[`*]*([A-Z][A-Za-z0-9_]*)""",
        RegexOption.IGNORE_CASE,
    )
    private val FK_WORD = Regex("""foreign key|chave estrangeira|\bFK\b""", RegexOption.IGNORE_CASE)
    private val UNIQUE = Regex("""\bunique\b|únic""", RegexOption.IGNORE_CASE)
    private val REQUIRED = Regex("""required|not null|mandatory|obrigat|non-null|\bNN\b""", RegexOption.IGNORE_CASE)
    private val OPTIONAL = Regex("""optional|nullable|opcional|\bnull\b(?! *\))""", RegexOption.IGNORE_CASE)
    private val INDEX = Regex("""\bindex(?:ed)?\b|índice|indexad""", RegexOption.IGNORE_CASE)
    private val DEFAULT = Regex("""(?:default|padrão)\s*[:=]?\s*`?([^,;)`]+)`?""", RegexOption.IGNORE_CASE)
    private val ENUM = Regex("""enum\s*[:(\[]?\s*([^)\]]+)""", RegexOption.IGNORE_CASE)
    private val ARROW_CHAIN = Regex("""`?([\w\- ]+?)`?\s*(?:→|->|⟶|=>)\s*`?([\w\- ]+?)`?(?=\s*(?:→|->|⟶|=>|\(|:|,|;|$))""")
    private val EVENT = Regex("""\(([^)]+)\)|:\s*(.+)$""")
    private val NOT_ENTITY = Regex(
        """^(entities|entidades|overview|visão geral|relationships|relacionamentos|validation.*|regras.*|state.*|estados?.*|""" +
            """enums?|notes?|notas|indexes|índices|constraints|data model.*|modelo de dados.*|summary|resumo|diagram.*|er diagram|""" +
            """fields|campos|attributes|atributos|columns|colunas|schema|properties|propriedades)$""",
        RegexOption.IGNORE_CASE,
    )
    private val FIELD_SECTION = Regex("""fields|campos|attributes|atributos|columns|colunas|schema|properties|propriedades""", RegexOption.IGNORE_CASE)

    fun parse(text: String): DataModelInfo {
        val lines = masked(text.lines())
        val headings = lines.indices.mapNotNull { i -> HEADING.matchEntire(lines[i])?.let { i to it } }
        val entities = mutableListOf<Entity>()
        val globalRelations = mutableListOf<String>()
        for ((index, entry) in headings.withIndex()) {
            val (line, match) = entry
            val level = match.groupValues[1].length
            val title = match.groupValues[2]
            val end = headings.drop(index + 1).firstOrNull { it.second.groupValues[1].length <= level }?.first ?: lines.size
            val nextHeading = headings.getOrNull(index + 1)?.first ?: lines.size
            val block = lines.subList(line + 1, end)
            val own = lines.subList(line + 1, nextHeading)
            val name = entityName(title)
            if (NOT_ENTITY.matches(name) || NOT_ENTITY.matches(title.trim())) {
                if (title.contains("relation", true) || title.contains("relacion", true)) {
                    globalRelations += own.mapNotNull { LIST_ITEM.matchEntire(it)?.groupValues?.get(1) }
                    globalRelations += own.filter { it.trimStart().startsWith("|") }
                }
                continue
            }
            val fields = fields(own).ifEmpty {
                fields(block.takeWhile { line -> HEADING.matchEntire(line)?.groupValues?.get(2)?.let { NOT_ENTITY.matches(it.trim()) && !FIELD_SECTION.matches(it.trim()) } != true })
            }
            if (fields.isEmpty()) continue
            entities += Entity(
                name = name,
                description = own.dropWhile { it.isBlank() }.takeWhile { it.isNotBlank() && !it.trimStart().startsWith("|") && !it.trimStart().startsWith("-") && !it.trimStart().startsWith("**") }
                    .joinToString(" ").trim(),
                fields = fields,
                rules = labeled(block, listOf("validation", "validação", "rules", "regras", "constraints", "invariants")),
                transitions = transitions(labeled(block, listOf("state", "estado", "lifecycle", "ciclo de vida", "transitions", "transições"), keepInline = true)),
                line = line,
            )
        }
        val names = entities.map { it.name }
        val resolved = entities.map { entity ->
            entity.copy(fields = entity.fields.map { field -> field.copy(foreignKey = field.foreignKey?.let { target(it, names) } ?: implicitTarget(field, entity.name, names)) })
        }
        val relations = mutableListOf<Relation>()
        for ((entity, block) in resolved.map { entity -> entity to lines.subList(entity.line + 1, lines.size) }) {
            val section = block.takeWhile { !(HEADING.matchEntire(it)?.groupValues?.get(1)?.length?.let { l -> l <= 3 } ?: false) }
            labeled(section, listOf("relationship", "relacionamento", "relations", "relações", "associations"), keepInline = true)
                .forEach { relation(it, entity.name, names)?.let(relations::add) }
        }
        globalRelations.forEach { line -> relation(line, null, names)?.let(relations::add) }
        resolved.forEach { entity ->
            entity.fields.forEach { field ->
                val target = field.foreignKey ?: return@forEach
                if (target != entity.name && relations.none { setOf(it.from, it.to) == setOf(target, entity.name) }) {
                    relations += Relation(target, entity.name, Cardinality.ONE_TO_MANY, field.name)
                }
            }
        }
        return DataModelInfo(resolved, relations.distinctBy { listOf(it.from, it.to).sorted() + it.label })
    }

    /** As linhas com os comentários HTML e os blocos de código apagados, mantendo a numeração e as linhas em branco. */
    private fun masked(lines: List<String>): List<String> {
        val visible = com.mio.tomahawk.speckit.Markdown.visible(lines).toMap()
        return lines.indices.map { visible[it] ?: "" }
    }

    private fun entityName(title: String): String = title
        .replace(Regex("""^\d+[.)]\s*"""), "")
        .replace(Regex("""^(entity|entidade|table|tabela|model|modelo)\s*[:\-—]\s*""", RegexOption.IGNORE_CASE), "")
        .replace("`", "").replace("*", "")
        .split(Regex("""\s+[(—–-]\s*|\s*\(""")).first().trim()

    /** Os campos de uma tabela Markdown com colunas de nome e tipo, ou dos itens de lista no formato de campo. */
    private fun fields(lines: List<String>): List<Field> {
        val table = lines.filter { it.trimStart().startsWith("|") }
        if (table.size >= 3) {
            val header = cells(table[0]).map { it.lowercase() }
            val nameCol = header.indexOfFirst { Regex("""field|name|attribute|column|campo|atributo|coluna|propert|nome""").containsMatchIn(it) }
            val typeCol = header.indexOfFirst { Regex("""type|tipo""").containsMatchIn(it) }
            if (nameCol >= 0 && typeCol >= 0) {
                val descCol = header.indexOfFirst { Regex("""desc|notes?|notas|purpose|propósito""").containsMatchIn(it) }
                return table.drop(2).map(::cells).filter { it.size > maxOf(nameCol, typeCol) && it[nameCol].isNotBlank() }.map { row ->
                    val others = row.filterIndexed { i, _ -> i != nameCol && i != typeCol }.joinToString(", ")
                    field(clean(row[nameCol]), clean(row[typeCol]) + ", " + others, note = row.getOrNull(descCol)?.let(::clean).orEmpty())
                }
            }
        }
        return fieldLines(lines).mapNotNull { LIST_ITEM.matchEntire(it)?.groupValues?.get(1)?.trim() }.mapNotNull { item ->
            val match = FIELD_BOLD.matchEntire(item) ?: FIELD_CODE.matchEntire(item) ?: FIELD_PLAIN.matchEntire(item) ?: return@mapNotNull null
            val name = match.groupValues[1]
            if (name.lowercase() in LABEL_WORDS) return@mapNotNull null
            val inParens = match.groupValues[2]
            val rest = match.groupValues[3]
            field(name, listOf(inParens, rest).filter { it.isNotBlank() }.joinToString(", "), note = rest)
        }
    }

    /**
     * As linhas que podem ser campos: fora dos trechos de rótulo em negrito que não são de campos, como
     * `**Validation Rules**:` ou `**Relationships**:`, cujos itens não podem virar colunas da tabela.
     */
    private fun fieldLines(lines: List<String>): List<String> {
        val kept = mutableListOf<String>()
        var skipping = false
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("**")) {
                val key = LABEL.matchEntire(line)?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()
                skipping = !FIELD_SECTION.containsMatchIn(key)
                continue
            }
            if (!skipping) kept += line
        }
        return kept
    }

    private val LABEL_WORDS = setOf("relationships", "relationship", "validation", "validation rules", "rules", "state", "states", "state transitions",
        "notes", "relacionamentos", "regras", "estados", "description", "descrição", "purpose", "indexes", "constraints")

    private fun field(name: String, details: String, note: String): Field {
        val type = GENERIC_TYPE.find(details)?.value ?: TYPE.find(details)?.value
            ?: details.split(',', ' ', '—', '-').map { it.trim('`', '*', ' ') }.firstOrNull { it.matches(Regex("""[A-Z][A-Za-z0-9]+""")) }
            ?: "?"
        val fk = FK.find(details)?.groupValues?.get(1)?.takeIf { it.lowercase() !in setOf("key", "chave") }
        return Field(
            name = name,
            type = type,
            primaryKey = PK.containsMatchIn(details) || (name.equals("id", true) && fk == null),
            foreignKey = fk ?: if (FK_WORD.containsMatchIn(details)) "" else null,
            unique = UNIQUE.containsMatchIn(details),
            required = REQUIRED.containsMatchIn(details),
            optional = OPTIONAL.containsMatchIn(details) && !REQUIRED.containsMatchIn(details),
            indexed = INDEX.containsMatchIn(details),
            default = DEFAULT.find(details)?.groupValues?.get(1)?.trim(),
            enumValues = if (type.equals("enum", true)) ENUM.find(details)?.groupValues?.get(1)?.split('|', ',', '/')?.map { it.trim(' ', '`', '"', '\'') }
                ?.filter { it.isNotEmpty() && it.length < 30 }.orEmpty() else emptyList(),
            note = note.trim(),
        )
    }

    /** O destino de uma chave estrangeira entre as entidades conhecidas, aceitando plural e maiúsculas. */
    private fun target(name: String, names: List<String>): String? {
        if (name.isEmpty()) return null
        return names.firstOrNull { it.equals(name, true) || "${it}s".equals(name, true) || it.equals(name.removeSuffix("s"), true) }
    }

    /** `album_id` ou `albumId` numa entidade que não é Album aponta para Album, se ela existir. */
    private fun implicitTarget(field: Field, owner: String, names: List<String>): String? {
        val base = Regex("""^(.+?)(?:_id|Id|ID)$""").matchEntire(field.name)?.groupValues?.get(1) ?: return null
        val normalized = base.replace("_", "")
        return names.firstOrNull { it.replace(" ", "").equals(normalized, true) && !it.equals(owner, true) }
    }

    /** As linhas de um rótulo em negrito (`**Relationships**:`) ou de um subtítulo, com os itens de lista que o seguem. */
    private fun labeled(block: List<String>, keys: List<String>, keepInline: Boolean = false): List<String> {
        val result = mutableListOf<String>()
        var inside = false
        for (line in block) {
            val heading = HEADING.matchEntire(line)
            val label = if (heading == null) LABEL.matchEntire(line) else null
            val key = heading?.groupValues?.get(2) ?: label?.groupValues?.get(1)
            if (key != null && (heading != null || line.trimStart().startsWith("**"))) {
                inside = keys.any { key.contains(it, ignoreCase = true) }
                if (inside && keepInline) label?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.let(result::add)
                if (inside && !keepInline) label?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.let(result::add)
                continue
            }
            if (!inside) continue
            if (line.isBlank()) continue
            val item = LIST_ITEM.matchEntire(line)?.groupValues?.get(1)
            if (item != null) result += item.trim() else if (line.trimStart().startsWith("|") || keepInline) result += line.trim() else inside = false
        }
        return result.filterNot { it.matches(Regex("""^\|?\s*:?-{3,}.*""")) }
    }

    private fun transitions(lines: List<String>): List<Transition> = lines.flatMap { line ->
        val event = EVENT.find(line.substringAfterLast("→").substringAfterLast("->"))?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }?.trim()?.take(40)
        val chain = line.replace("**", "").replace("`", "")
        val states = Regex("""\s*(?:→|->|⟶|=>)\s*""").split(chain.substringBefore(" (").substringBefore(":").let { s ->
            if (Regex("""(→|->|⟶|=>)""").containsMatchIn(s)) s else chain
        }).map { it.trim().trim(',', '.', ';').split(Regex("""\s+""")).takeLast(2).joinToString(" ").trim() }.filter { it.isNotEmpty() && it.length <= 30 }
        if (states.size < 2) emptyList()
        else states.zipWithNext().mapIndexed { i, (a, b) -> Transition(a, b, if (i == states.size - 2) event?.takeIf { states.size == 2 } else null) }
    }.distinct()

    private fun relation(line: String, owner: String?, names: List<String>): Relation? {
        val text = line.replace("**", "").replace("`", "")
        if (text.trimStart().startsWith("|")) {
            val cells = cells(text)
            val found = cells.mapNotNull { cell -> names.firstOrNull { name -> Regex("""\b${Regex.escape(name)}s?\b""", RegexOption.IGNORE_CASE).containsMatchIn(cell) } }
            if (found.size < 2) return null
            return Relation(found[0], found[1], cardinality(text), label(text))
        }
        val mentioned = names.mapNotNull { name ->
            Regex("""\b${Regex.escape(name)}s?\b""", RegexOption.IGNORE_CASE).find(text)?.let { it.range.first to name }
        }.sortedBy { it.first }.map { it.second }.distinct()
        val (from, to) = when {
            mentioned.size >= 2 -> mentioned[0] to mentioned[1]
            mentioned.size == 1 && owner != null && mentioned[0] != owner -> owner to mentioned[0]
            else -> return null
        }
        return Relation(from, to, cardinality(text), label(text))
    }

    private fun cardinality(text: String): Cardinality {
        val t = text.lowercase()
        return when {
            Regex("""many[- ]to[- ]many|n:m|m:n|n-n|muitos para muitos""").containsMatchIn(t) -> Cardinality.MANY_TO_MANY
            Regex("""one[- ]to[- ]one|1:1|has one|has a single|um para um|tem um\b""").containsMatchIn(t) -> Cardinality.ONE_TO_ONE
            Regex("""many[- ]to[- ]one|n:1|belongs to|pertence a|references?|referencia|\bin (one|a)\b""").containsMatchIn(t) -> Cardinality.MANY_TO_ONE
            else -> Cardinality.ONE_TO_MANY
        }
    }

    private fun label(text: String): String {
        val t = text.lowercase()
        return when {
            "belongs to" in t || "pertence" in t -> "pertence a"
            "has many" in t || "contains" in t || "contém" in t || "tem muitos" in t -> "tem muitos"
            "has one" in t || "tem um" in t -> "tem um"
            "references" in t || "referencia" in t -> "referencia"
            else -> "relaciona"
        }
    }

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim() }

    private fun clean(text: String) = text.replace("`", "").replace("**", "").trim()

    // ---------------------------------------------------------------- desenho

    private val TONES = listOf("blue", "purple", "teal", "orange", "pink", "green")

    /** O diagrama ER do Mermaid: entidades com os campos (PK, FK, UK) e os relacionamentos com cardinalidade. */
    fun mermaidEr(model: DataModelInfo): String = buildString {
        appendLine("erDiagram")
        for (entity in model.entities) {
            appendLine("    ${id(entity.name)} {")
            entity.fields.take(MAX_ER_FIELDS).forEach { field ->
                val keys = listOfNotNull("PK".takeIf { field.primaryKey }, "FK".takeIf { field.foreignKey != null }, "UK".takeIf { field.unique && !field.primaryKey })
                append("        ").append(typeId(field.type)).append(' ').append(id(field.name))
                if (keys.isNotEmpty()) append(' ').append(keys.joinToString(","))
                appendLine()
            }
            if (entity.fields.size > MAX_ER_FIELDS) appendLine("        etc mais_${entity.fields.size - MAX_ER_FIELDS}_campos")
            appendLine("    }")
        }
        model.relations.forEach { relation ->
            appendLine("    ${id(relation.from)} ${relation.cardinality.mermaid} ${id(relation.to)} : \"${relation.label.replace("\"", "")}\"")
        }
    }

    /** As transições de estado de uma entidade como diagrama de estados do Mermaid. */
    fun mermaidStates(entity: Entity): String = buildString {
        appendLine("stateDiagram-v2")
        appendLine("    direction LR")
        entity.transitions.firstOrNull()?.let { appendLine("    [*] --> ${id(it.from)}") }
        entity.transitions.flatMap { listOf(it.from, it.to) }.distinct().forEach { appendLine("    state \"${it.replace("\"", "")}\" as ${id(it)}") }
        entity.transitions.forEach { t -> appendLine("    ${id(t.from)} --> ${id(t.to)}${t.event?.let { " : ${it.replace(":", " ")}" }.orEmpty()}") }
    }

    /** [keyLabel] é o nome da referência no campo: `FK` num banco, `ref` entre schemas de um contrato. */
    fun tableCards(
        model: DataModelInfo,
        esc: (String) -> String,
        inline: (String) -> String,
        diagram: (String, String) -> String,
        keyLabel: String = "FK",
    ): String = buildString {
        append("<div class=\"tables\">")
        model.entities.forEachIndexed { index, entity ->
            val tone = TONES[index % TONES.size]
            val outgoing = model.relations.filter { it.from == entity.name || it.to == entity.name }
            append("<div class=\"table-card\" id=\"entity-${ArtifactHtml.slug(entity.name)}\" style=\"--t:var(--$tone);--t-bg:var(--$tone-bg);--t-fg:var(--$tone-fg)\">")
            append("<div class=\"table-head\"><span class=\"ticon\">").append(TABLE_ICON).append("</span>")
            append("<span class=\"tname\">").append(esc(entity.name)).append("</span>")
            append("<span class=\"tcount\">${entity.fields.size} campo(s)</span></div>")
            if (entity.description.isNotBlank()) append("<div class=\"table-desc\">").append(inline(entity.description)).append("</div>")
            append("<div class=\"table-rows\">")
            entity.fields.forEach { field -> append(fieldRow(field, esc, inline, keyLabel)) }
            append("</div>")
            if (outgoing.isNotEmpty() || entity.rules.isNotEmpty() || entity.transitions.isNotEmpty()) {
                append("<div class=\"table-foot\">")
                if (outgoing.isNotEmpty()) {
                    append("<div class=\"foot-title\">Relacionamentos</div><div class=\"rels\">")
                    outgoing.forEach { r ->
                        val forward = r.from == entity.name
                        val other = if (forward) r.to else r.from
                        val card = if (forward) r.cardinality.label else r.cardinality.label.reversed()
                        append("<a class=\"rel\" href=\"#entity-${ArtifactHtml.slug(other)}\"><span class=\"card-badge\">$card</span>")
                        append(esc(if (forward) r.label else inverse(r.label))).append(" <b>").append(esc(other)).append("</b></a>")
                    }
                    append("</div>")
                }
                if (entity.rules.isNotEmpty()) {
                    append("<div class=\"foot-title\">Regras de validação</div><ul class=\"rules\">")
                    entity.rules.forEach { append("<li>").append(inline(it)).append("</li>") }
                    append("</ul>")
                }
                if (entity.transitions.isNotEmpty()) {
                    append("<div class=\"foot-title\">Ciclo de vida</div>")
                    append(diagram(mermaidStates(entity), "Estados de ${entity.name}"))
                }
                append("</div>")
            }
            append("</div>")
        }
        append("</div>")
    }

    /** O relacionamento visto do outro lado: quem "tem muitos" é dono de quem "pertence a". */
    private fun inverse(label: String) = when (label) {
        "tem muitos", "tem um" -> "pertence a"
        "pertence a" -> "tem"
        "referencia" -> "é referenciado por"
        else -> label
    }

    private fun fieldRow(field: Field, esc: (String) -> String, inline: (String) -> String, keyLabel: String): String = buildString {
        val kind = when {
            field.primaryKey -> "pk"
            field.foreignKey != null -> "fk"
            else -> ""
        }
        append("<div class=\"trow $kind\"><span class=\"key\" title=\"")
        append(
            when {
                field.primaryKey -> "Chave primária\">${KEY_ICON}"
                field.foreignKey != null -> "Chave estrangeira\">${LINK_ICON}"
                field.unique -> "Único\">◆"
                else -> "\">"
            },
        )
        append("</span><span class=\"fname\">").append(esc(field.name)).append("</span>")
        append("<span class=\"ftype ty-${typeFamily(field.type)}\">").append(esc(field.type)).append("</span>")
        append("<span class=\"flags\">")
        if (field.primaryKey) append("<span class=\"flag pkf\">PK</span>")
        field.foreignKey?.let { target ->
            if (target.isNotEmpty()) append("<a class=\"flag fkf\" href=\"#entity-${ArtifactHtml.slug(target)}\">$keyLabel → ${esc(target)}</a>")
            else append("<span class=\"flag fkf\">$keyLabel</span>")
        }
        if (field.unique && !field.primaryKey) append("<span class=\"flag\">UNIQUE</span>")
        if (field.required && !field.primaryKey) append("<span class=\"flag req\">NOT NULL</span>")
        if (field.optional) append("<span class=\"flag opt\">NULL</span>")
        if (field.indexed) append("<span class=\"flag\">INDEX</span>")
        field.default?.let { append("<span class=\"flag def\">= ${esc(it.take(24))}</span>") }
        append("</span></div>")
        if (field.enumValues.isNotEmpty()) {
            append("<div class=\"tnote enum\">").append(field.enumValues.joinToString(" ") { "<span class=\"ev\">${esc(it)}</span>" }).append("</div>")
        }
        val note = field.note.takeIf { it.isNotBlank() && it.length > field.type.length + 2 }
        note?.let { append("<div class=\"tnote\">").append(inline(it)).append("</div>") }
    }

    fun typeFamily(type: String): String {
        val t = type.lowercase()
        return when {
            Regex("""uuid|guid|ulid|hash""").containsMatchIn(t) -> "id"
            Regex("""string|text|char|email|url|uri|path|file""").containsMatchIn(t) -> "text"
            Regex("""int|long|short|number|numeric|decimal|float|double|real|money|bigint""").containsMatchIn(t) -> "num"
            Regex("""bool""").containsMatchIn(t) -> "bool"
            Regex("""date|time|instant|duration""").containsMatchIn(t) -> "date"
            Regex("""enum""").containsMatchIn(t) -> "enum"
            Regex("""json|object|map|dict|blob|binary|bytes""").containsMatchIn(t) -> "json"
            Regex("""list|array|set|\[]""").containsMatchIn(t) -> "list"
            else -> "ref"
        }
    }

    /** Identificador aceito pelo Mermaid: letras, números e `_`. */
    fun id(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
        .replace(Regex("""[^A-Za-z0-9_]+"""), "_").trim('_').ifEmpty { "x" }.let { if (it.first().isDigit()) "_$it" else it }

    private fun typeId(type: String) = id(type.replace("[]", "_list").replace("<", "_").replace(">", "")).take(24)

    private const val MAX_ER_FIELDS = 14
    private const val TABLE_ICON = "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\"><rect x=\"1.5\" y=\"2.5\" width=\"13\" height=\"11\" rx=\"2\" fill=\"none\" stroke=\"currentColor\"/><path d=\"M1.5 6h13M1.5 9.5h13M6 6v7.5\" stroke=\"currentColor\"/></svg>"
    private const val KEY_ICON = "<svg viewBox=\"0 0 16 16\" width=\"13\" height=\"13\"><circle cx=\"5\" cy=\"8\" r=\"3\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\"/><path d=\"M8 8h6.5M12 8v2.5M14 8v2\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\"/></svg>"
    private const val LINK_ICON = "<svg viewBox=\"0 0 16 16\" width=\"13\" height=\"13\"><path d=\"M6.5 9.5l3-3M7 4.5l1-1a2.8 2.8 0 014 4l-1 1M9 11.5l-1 1a2.8 2.8 0 01-4-4l1-1\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\"/></svg>"
}
