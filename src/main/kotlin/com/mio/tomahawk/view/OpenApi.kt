package com.mio.tomahawk.view

import com.google.gson.JsonParser
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Os contratos OpenAPI que o `/speckit-plan` gera em `contracts/`: os endpoints em cartões por tag (método,
 * caminho, parâmetros, corpo e respostas) e os schemas como tabelas, com o diagrama das referências entre eles.
 */
object OpenApi {

    class Endpoint(
        val method: String,
        val path: String,
        val summary: String?,
        val tags: List<String>,
        val parameters: List<Triple<String, String, Boolean>>,
        val body: String?,
        val responses: List<Pair<String, String>>,
    )

    class Spec(val title: String?, val version: String?, val description: String?, val endpoints: List<Endpoint>, val schemas: DataModelInfo)

    private val METHODS = listOf("get", "post", "put", "patch", "delete", "head", "options")

    fun parse(text: String, json: Boolean): Spec? {
        val root = runCatching {
            if (json) gsonToMap(JsonParser.parseString(text)) else Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
        }.getOrNull() as? Map<*, *> ?: return null
        if (root["openapi"] == null && root["swagger"] == null && root["paths"] == null) return null
        val info = root["info"] as? Map<*, *>
        val endpoints = (root["paths"] as? Map<*, *>).orEmpty().flatMap { (path, item) ->
            val operations = item as? Map<*, *> ?: return@flatMap emptyList()
            val shared = operations["parameters"] as? List<*>
            METHODS.mapNotNull { method ->
                val op = operations[method] as? Map<*, *> ?: return@mapNotNull null
                Endpoint(
                    method = method.uppercase(),
                    path = path.toString(),
                    summary = (op["summary"] ?: op["description"] ?: op["operationId"])?.toString()?.lines()?.firstOrNull(),
                    tags = (op["tags"] as? List<*>)?.map { it.toString() }.orEmpty(),
                    parameters = ((shared.orEmpty()) + (op["parameters"] as? List<*>).orEmpty()).mapNotNull { p ->
                        val param = p as? Map<*, *> ?: return@mapNotNull null
                        val name = param["name"]?.toString() ?: ref(param["\$ref"])?.let { return@mapNotNull Triple(it, "ref", false) } ?: return@mapNotNull null
                        Triple(name, "${param["in"] ?: "?"} · ${typeOf(param["schema"] as? Map<*, *>)}", param["required"] == true)
                    },
                    body = ((op["requestBody"] as? Map<*, *>)?.get("content") as? Map<*, *>)?.values?.firstNotNullOfOrNull { media ->
                        typeOf((media as? Map<*, *>)?.get("schema") as? Map<*, *>)
                    },
                    responses = (op["responses"] as? Map<*, *>).orEmpty().map { (code, response) ->
                        val r = response as? Map<*, *>
                        val schema = (r?.get("content") as? Map<*, *>)?.values?.firstNotNullOfOrNull { typeOf((it as? Map<*, *>)?.get("schema") as? Map<*, *>) }
                        code.toString() to listOfNotNull(r?.get("description")?.toString()?.lines()?.firstOrNull(), schema?.let { "→ $it" }).joinToString(" ")
                    },
                )
            }
        }
        val schemas = ((root["components"] as? Map<*, *>)?.get("schemas") as? Map<*, *> ?: root["definitions"] as? Map<*, *>).orEmpty()
        val entities = schemas.mapNotNull { (name, value) ->
            val schema = value as? Map<*, *> ?: return@mapNotNull null
            val required = (schema["required"] as? List<*>)?.map { it.toString() }.orEmpty().toSet()
            val properties = (schema["properties"] as? Map<*, *>).orEmpty()
            val fields = properties.map { (prop, raw) ->
                val p = raw as? Map<*, *> ?: emptyMap<Any, Any>()
                val target = ref(p["\$ref"]) ?: ref((p["items"] as? Map<*, *>)?.get("\$ref"))
                Field(
                    name = prop.toString(),
                    type = typeOf(p),
                    primaryKey = prop.toString() == "id",
                    foreignKey = target,
                    required = prop.toString() in required,
                    optional = p["nullable"] == true,
                    default = p["default"]?.toString(),
                    enumValues = (p["enum"] as? List<*>)?.map { it.toString() }.orEmpty(),
                    note = p["description"]?.toString()?.lines()?.firstOrNull().orEmpty(),
                )
            }
            val enum = (schema["enum"] as? List<*>)?.map { it.toString() }
            Entity(
                name = name.toString(),
                description = schema["description"]?.toString()?.lines()?.firstOrNull().orEmpty(),
                fields = fields.ifEmpty { if (enum != null) listOf(Field("valor", "enum", enumValues = enum)) else listOf(Field("(tipo)", typeOf(schema))) },
                rules = emptyList(),
                transitions = emptyList(),
                line = 0,
            )
        }
        val relations = entities.flatMap { entity ->
            entity.fields.mapNotNull { field ->
                val target = field.foreignKey ?: return@mapNotNull null
                if (entities.none { it.name == target }) return@mapNotNull null
                Relation(entity.name, target, if (field.type.endsWith("[]")) Cardinality.ONE_TO_MANY else Cardinality.ONE_TO_ONE, field.name)
            }
        }
        return Spec(info?.get("title")?.toString(), info?.get("version")?.toString(), info?.get("description")?.toString(), endpoints, DataModelInfo(entities, relations))
    }

    private fun ref(value: Any?): String? = value?.toString()?.substringAfterLast('/')

    private fun typeOf(schema: Map<*, *>?): String {
        schema ?: return "?"
        ref(schema["\$ref"])?.let { return it }
        val type = schema["type"]?.toString()
        if (type == "array") return typeOf(schema["items"] as? Map<*, *>) + "[]"
        val format = schema["format"]?.toString()
        if (schema["enum"] != null) return "enum"
        listOf("oneOf", "anyOf", "allOf").forEach { key ->
            (schema[key] as? List<*>)?.let { options -> return options.joinToString(if (key == "allOf") " & " else " | ") { typeOf(it as? Map<*, *>) } }
        }
        return format ?: type ?: "object"
    }

    private fun gsonToMap(element: com.google.gson.JsonElement): Any? = when {
        element.isJsonObject -> element.asJsonObject.entrySet().associate { it.key to gsonToMap(it.value) }
        element.isJsonArray -> element.asJsonArray.map { gsonToMap(it) }
        element.isJsonNull -> null
        else -> element.asJsonPrimitive.let { if (it.isBoolean) it.asBoolean else if (it.isNumber) it.asNumber else it.asString }
    }

    fun endpointsHtml(spec: Spec, esc: (String) -> String): String = buildString {
        val groups = spec.endpoints.groupBy { it.tags.firstOrNull() ?: "Endpoints" }
        for ((tag, endpoints) in groups) {
            append("<div class=\"section-title\">").append(esc(tag)).append(" · ${endpoints.size}</div><div class=\"eps\">")
            endpoints.forEach { ep ->
                val m = ep.method.lowercase()
                append("<details class=\"ep m-$m\"><summary><span class=\"method\">${ep.method}</span>")
                append("<span class=\"ep-path\">").append(esc(ep.path).replace(Regex("""\{([^}]+)}"""), "<span class=\"param\">{$1}</span>")).append("</span>")
                ep.summary?.let { append("<span class=\"ep-sum\">").append(esc(it)).append("</span>") }
                append("<span class=\"codes\">")
                ep.responses.forEach { (code, _) -> append("<span class=\"code c${code.firstOrNull() ?: 'x'}\">").append(esc(code)).append("</span>") }
                append("</span></summary><div class=\"ep-body\">")
                if (ep.parameters.isNotEmpty()) {
                    append("<div class=\"foot-title\">Parâmetros</div><div class=\"params\">")
                    ep.parameters.forEach { (name, where, required) ->
                        append("<span class=\"pchip\"><b>").append(esc(name)).append("</b>").append(esc(where))
                        if (required) append(" <span class=\"flag req\">obrigatório</span>")
                        append("</span>")
                    }
                    append("</div>")
                }
                ep.body?.let { append("<div class=\"foot-title\">Corpo</div><a class=\"pchip\" href=\"#entity-${ArtifactHtml.slug(it.removeSuffix("[]"))}\">").append(esc(it)).append("</a>") }
                if (ep.responses.isNotEmpty()) {
                    append("<div class=\"foot-title\">Respostas</div><div class=\"resps\">")
                    ep.responses.forEach { (code, text) ->
                        append("<div class=\"resp\"><span class=\"code c${code.firstOrNull() ?: 'x'}\">").append(esc(code)).append("</span>").append(esc(text)).append("</div>")
                    }
                    append("</div>")
                }
                append("</div></details>")
            }
            append("</div>")
        }
    }
}
