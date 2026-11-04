package com.mio.tomahawk.speckit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A integração padrão do Spec Kit no projeto, de `.specify/integration.json`. */
data class IntegrationInfo(val key: String?, val separator: String?) {
    companion object {
        val NONE = IntegrationInfo(null, null)
    }
}

/**
 * Onde está o comando de uma etapa: uma skill (`speckit-<etapa>/SKILL.md`, chamada com `-`) ou um arquivo de
 * comando (`speckit.<etapa>.md`, chamado com `.`).
 */
data class CommandRef(val step: SddStep, val file: Path, val skill: Boolean) {
    val separator: String get() = if (skill) "-" else "."
}

/**
 * A estrutura do Spec Kit num projeto: `.specify/` (constituição, integração e feature ativa) e `specs/NNN-nome/`
 * com os artefatos de cada feature. Só lê e grava arquivos, sem depender da IDE.
 */
class SpecKitLayout(val root: Path) {

    val specifyDir: Path = root.resolve(".specify")
    val specsDir: Path = root.resolve("specs")
    val constitution: Path = specifyDir.resolve("memory").resolve("constitution.md")
    private val featureJson: Path = specifyDir.resolve("feature.json")
    private val integrationJson: Path = specifyDir.resolve("integration.json")

    val initialized: Boolean get() = specifyDir.isDirectory()

    /** A constituição existe e já não é o modelo com os marcadores `[PROJECT_NAME]`, `[PRINCIPLE_1_NAME]`… */
    fun constitutionReady(): Boolean {
        if (!constitution.isRegularFile()) return false
        val text = read(constitution) ?: return false
        return !TEMPLATE_PLACEHOLDER.containsMatchIn(text)
    }

    fun integration(): IntegrationInfo {
        val json = readJson(integrationJson) ?: return IntegrationInfo.NONE
        val key = json.str("default_integration") ?: json.str("integration")
        val separator = key?.let { json.getAsJsonObject("integration_settings")?.getAsJsonObject(it)?.str("invoke_separator") }
            ?.takeIf { it == "." || it == "-" }
        return IntegrationInfo(key, separator)
    }

    /** As pastas de feature em `specs/`, em ordem de número. */
    fun features(): List<Path> {
        if (!specsDir.isDirectory()) return emptyList()
        return Files.list(specsDir).use { dirs -> dirs.filter { it.isDirectory() && !it.name.startsWith(".") }.toList() }
            .sortedBy { it.name }
    }

    /**
     * A feature ativa: a de `.specify/feature.json`; sem ela, a da branch do git com o mesmo nome de uma pasta
     * de `specs/` (versões antigas do Spec Kit); sem isso, a de número mais alto.
     */
    fun activeFeature(gitBranch: String? = null): Path? {
        readJson(featureJson)?.str("feature_directory")?.takeIf { it.isNotBlank() }?.let { stored ->
            val dir = root.resolve(stored.replace('\\', '/')).normalize()
            if (dir.isDirectory()) return dir
        }
        val features = features()
        gitBranch?.let { branch -> features.firstOrNull { it.name == branch }?.let { return it } }
        return features.lastOrNull()
    }

    /** Torna [feature] a ativa para os comandos do Spec Kit, como o `/speckit-specify` faz ao criar uma. */
    fun activate(feature: Path) {
        Files.createDirectories(specifyDir)
        val relative = relative(feature)
        Files.writeString(featureJson, "{\n  \"feature_directory\": \"$relative\"\n}\n", StandardCharsets.UTF_8)
    }

    fun relative(path: Path): String = runCatching { root.relativize(path).toString().replace('\\', '/') }.getOrDefault(path.toString())

    /**
     * Onde estão os comandos de cada etapa no projeto. Procura primeiro nas pastas de [preferred] (como `.cursor` ou
     * `.claude`) e depois nas outras pastas ocultas da raiz, pois cada integração instala num lugar.
     */
    fun commands(preferred: List<String> = emptyList()): Map<SddStep, CommandRef> {
        val roots = buildList {
            preferred.map(root::resolve).filter { it.isDirectory() }.forEach(::add)
            if (root.isDirectory()) {
                Files.list(root).use { entries ->
                    entries.filter { it.isDirectory() && it.name.startsWith(".") && it.name !in IGNORED_DIRS }.toList()
                }.sortedBy { it.name }.filterNot { it in this }.forEach(::add)
            }
        }
        val found = linkedMapOf<SddStep, CommandRef>()
        for (dir in roots) {
            val files = runCatching {
                Files.walk(dir, SEARCH_DEPTH).use { walk -> walk.filter { it.isRegularFile() }.toList() }
            }.getOrDefault(emptyList())
            for (file in files) {
                val ref = commandRef(file) ?: continue
                found.putIfAbsent(ref.step, ref)
            }
        }
        return found
    }

    companion object {
        private val TEMPLATE_PLACEHOLDER = Regex("""\[(PROJECT_NAME|PRINCIPLE_\d+_NAME|CONSTITUTION_VERSION)]""")
        private val IGNORED_DIRS = setOf(".git", ".idea", ".gradle", ".kotlin", ".intellijPlatform", ".venv", ".specify", ".vscode")
        private const val SEARCH_DEPTH = 4
        private val SKILL_DIR = Regex("""speckit[-.]([a-z]+)""")
        private val COMMAND_FILE = Regex("""speckit\.([a-z]+)(\.prompt|\.agent)?\.(md|toml|mdc)""")

        /** A etapa de um arquivo de comando ou skill do Spec Kit, ou nulo se não for um. */
        fun commandRef(file: Path): CommandRef? {
            val name = file.name.lowercase()
            if (name == "skill.md") {
                val dir = file.parent?.name?.lowercase() ?: return null
                val step = SKILL_DIR.matchEntire(dir)?.groupValues?.get(1)?.let(SddStep::byCommand) ?: return null
                return CommandRef(step, file, skill = true)
            }
            val step = COMMAND_FILE.matchEntire(name)?.groupValues?.get(1)?.let(SddStep::byCommand) ?: return null
            return CommandRef(step, file, skill = false)
        }

        internal fun read(path: Path): String? = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull()

        private fun readJson(path: Path): JsonObject? {
            if (!path.isRegularFile()) return null
            val text = read(path) ?: return null
            return runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        }

        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
