package com.mio.tomahawk.delivery

import com.mio.tomahawk.speckit.FeatureReport
import java.nio.file.Path

/** Os valores iniciais do PR a partir da feature do Spec Kit: branch, base, título, descrição e mensagem do commit. */
object PullRequestDraft {

    private val SPEC_TITLE = Regex("""^#\s+(?:Feature Specification\s*:\s*)?(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val MAINLINES = setOf("main", "master", "develop", "development", "trunk")

    /**
     * A branch atual, se já é de trabalho; na branch principal, a da feature, que é o nome que o Spec Kit dá às
     * branches (`001-nome`).
     */
    fun branch(feature: FeatureReport?, current: String?, defaultBranch: String?): String {
        val mainline = current == null || current == defaultBranch || current.lowercase() in MAINLINES
        return if (!mainline) current.orEmpty() else feature?.name ?: current.orEmpty()
    }

    /** A `develop`, quando o repositório tem, como no fluxo das features; senão, a branch padrão do GitHub. */
    fun base(remote: RemoteInfo, branch: String): String =
        remote.openPr?.base
            ?: "develop".takeIf { it in remote.branches && it != branch }
            ?: remote.defaultBranch

    fun specTitle(lines: List<String>): String? = lines.firstNotNullOfOrNull { line ->
        SPEC_TITLE.matchEntire(line.trim())?.groupValues?.get(1)?.takeUnless { it.startsWith("[") }
    }

    fun title(feature: FeatureReport?, specTitle: String?): String {
        val subject = specTitle ?: feature?.name?.let(::humanize) ?: return ""
        return "feat: $subject"
    }

    fun body(feature: FeatureReport?, specTitle: String?, relative: (Path) -> String): String = buildString {
        if (feature == null) {
            append("_Aberto pelo Tomahawk IA._\n")
            return@buildString
        }
        append("## ").append(specTitle ?: humanize(feature.name)).append("\n\n")
        append("Feature do Spec Kit em `").append(relative(feature.dir)).append("`.\n")
        val stories = feature.spec?.stories.orEmpty()
        if (stories.isNotEmpty()) {
            append("\n### Histórias de usuário\n\n")
            stories.forEach { story ->
                append("- **US").append(story.number).append("** ").append(story.title)
                story.priority?.let { append(" _(").append(it).append(")_") }
                append('\n')
            }
        }
        feature.tasks?.let { tasks ->
            append("\n### Tarefas\n\n")
            append("- ").append(tasks.done).append(" de ").append(tasks.total).append(" concluída(s)\n")
            tasks.phases.forEach { phase -> append("- ").append(phase.title).append(": ").append(phase.done).append('/').append(phase.tasks.size).append('\n') }
        }
        val artifacts = listOfNotNull(feature.spec?.file, feature.plan?.file, feature.tasks?.file) + feature.designDocs
        if (artifacts.isNotEmpty()) {
            append("\n### Artefatos\n\n")
            artifacts.forEach { append("- `").append(relative(it)).append("`\n") }
        }
        append("\n---\n_Aberto pelo Tomahawk IA._\n")
    }

    /** `001-photo-albums` vira `Photo albums`. */
    internal fun humanize(name: String): String {
        val words = name.replace(Regex("""^\d+-"""), "").replace('-', ' ').replace('_', ' ').trim()
        return words.replaceFirstChar { it.uppercase() }.ifEmpty { name }
    }
}
