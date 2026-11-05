package com.mio.tomahawk.ui.web

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IElementType
import com.mio.tomahawk.view.ArtifactHtml
import java.awt.Color
import java.awt.Font

/** Uma linha do trecho do diff, com o número dela antes ([old]) e depois ([new]) da mudança. */
data class HunkLine(val kind: Kind, val old: Int?, val new: Int?, val text: String) {
    enum class Kind { CONTEXT, ADD, DEL, HEADER, NOTE }
}

/**
 * O trecho do diff que o GitHub guarda na conversa de código, desenhado como no editor: os números das linhas,
 * o realce de sintaxe e as cores de diff do esquema de cores da IDE. O trecho termina na linha comentada.
 */
object DiffHunk {

    private val HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@ ?(.*)$""")

    fun parse(text: String): List<HunkLine> {
        var old = 0
        var new = 0
        return text.trimEnd('\n', '\r').lines().map { raw ->
            val line = raw.trimEnd('\r')
            val header = HEADER.find(line)
            when {
                header != null -> {
                    old = header.groupValues[1].toInt()
                    new = header.groupValues[2].toInt()
                    HunkLine(HunkLine.Kind.HEADER, null, null, header.groupValues[3])
                }
                line.startsWith("+") -> HunkLine(HunkLine.Kind.ADD, null, new++, line.substring(1))
                line.startsWith("-") -> HunkLine(HunkLine.Kind.DEL, old++, null, line.substring(1))
                line.startsWith("\\") -> HunkLine(HunkLine.Kind.NOTE, null, null, line.removePrefix("\\").trim())
                else -> HunkLine(HunkLine.Kind.CONTEXT, old++, new++, line.removePrefix(" "))
            }
        }
    }

    /**
     * O trecho em HTML. Com [compact], só as últimas [visible] linhas aparecem e as de cima ficam dobradas, como uma
     * região recolhida do editor; [foldKey] é a chave da dobra, que a página lembra.
     */
    fun html(project: Project, text: String, path: String?, line: Int?, foldKey: String, compact: Boolean, visible: Int): String {
        val lines = parse(text).let { all -> if (all.firstOrNull()?.kind == HunkLine.Kind.HEADER) all.drop(1) else all }
        if (lines.isEmpty()) return ""
        val code = highlight(project, path, lines)
        val hidden = (lines.size - visible).coerceAtLeast(0)
        val focus = lines.indexOfLast { it.kind != HunkLine.Kind.NOTE && it.kind != HunkLine.Kind.HEADER }
        val added = lines.count { it.kind == HunkLine.Kind.ADD }
        val removed = lines.count { it.kind == HunkLine.Kind.DEL }
        return buildString {
            append("<div class=\"code").append(if (compact && hidden > 0) " folded" else "").append("\" data-fold=\"").append(WebUi.attr(foldKey)).append("\">")
            append("<div class=\"code-head\">")
            path?.let { p ->
                val dir = p.substringBeforeLast('/', "")
                append("<span class=\"code-file clickable\" data-cmd=\"file\" data-arg=\"").append(WebUi.attr(p)).append("\"")
                line?.let { append(" data-line=\"$it\"") }
                append(" title=\"Abrir no editor\">")
                if (dir.isNotEmpty()) append("<span class=\"dir\">").append(esc(dir)).append("/</span>")
                append("<b>").append(esc(p.substringAfterLast('/'))).append("</b></span>")
            }
            line?.let { append("<span class=\"code-meta\">linha $it</span>") }
            append("<span class=\"grow\"></span><span class=\"code-stat\">")
            if (added > 0) append("<span class=\"a\">+$added</span>")
            if (removed > 0) append("<span class=\"d\">−$removed</span>")
            append("</span>")
            if (hidden > 0) {
                append("<button type=\"button\" class=\"btn sm ghost\" data-fold-toggle>")
                append("<span class=\"if-folded\">↕ Trecho inteiro</span><span class=\"if-open\">↥ Recolher</span></button>")
            }
            append("</div><div class=\"code-body\"><table>")
            if (hidden > 0) {
                append("<tr class=\"fold-row\" data-fold-toggle title=\"Mostrar as linhas de cima\"><td class=\"gt\"><span>⋯</span><span></span></td>")
                append("<td class=\"sg\"></td><td class=\"cd\">↕ ").append(hidden).append(if (hidden == 1) " linha acima" else " linhas acima").append("</td></tr>")
            }
            lines.forEachIndexed { i, l ->
                val cls = listOfNotNull(
                    when (l.kind) {
                        HunkLine.Kind.ADD -> "add"
                        HunkLine.Kind.DEL -> "del"
                        HunkLine.Kind.NOTE -> "note"
                        HunkLine.Kind.HEADER -> "sep"
                        HunkLine.Kind.CONTEXT -> null
                    },
                    "far".takeIf { i < hidden },
                    "focus".takeIf { i == focus },
                ).joinToString(" ")
                append("<tr").append(if (cls.isNotEmpty()) " class=\"$cls\"" else "").append(">")
                append("<td class=\"gt\"><span>").append(l.old?.toString().orEmpty()).append("</span><span>").append(l.new?.toString().orEmpty()).append("</span></td>")
                append("<td class=\"sg\">").append(
                    when (l.kind) {
                        HunkLine.Kind.ADD -> "+"
                        HunkLine.Kind.DEL -> "−"
                        else -> ""
                    },
                ).append("</td><td class=\"cd\">")
                append(
                    when (l.kind) {
                        HunkLine.Kind.HEADER -> "⋯ " + esc(l.text)
                        HunkLine.Kind.NOTE -> esc(l.text)
                        else -> code[i]
                    },
                )
                append("</td></tr>")
            }
            append("</table></div></div>")
        }
    }

    /** As cores e a fonte do editor, como variáveis CSS que o bloco de código usa. */
    fun css(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val vars = linkedMapOf(
            "--ed-bg" to scheme.defaultBackground,
            "--ed-fg" to scheme.defaultForeground,
            "--ed-gutter" to scheme.getColor(EditorColors.GUTTER_BACKGROUND),
            "--ed-ln" to scheme.getColor(EditorColors.LINE_NUMBERS_COLOR),
            "--ed-ln-on" to scheme.getColor(EditorColors.LINE_NUMBER_ON_CARET_ROW_COLOR),
            "--ed-caret" to scheme.getColor(EditorColors.CARET_ROW_COLOR),
            "--ed-add" to scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor,
            "--ed-del" to scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor,
            "--ed-mod" to scheme.getAttributes(DiffColors.DIFF_MODIFIED)?.backgroundColor,
        )
        return buildString {
            append("<style>:root{")
            vars.forEach { (name, color) -> color?.let { append(name).append(':').append(hex(it)).append(';') } }
            append("--ed-font:\"").append(scheme.editorFontName.replace("\"", "")).append("\",\"JetBrains Mono\",Consolas,monospace;")
            append("--ed-size:").append(scheme.editorFontSize.coerceIn(10, 16)).append("px;")
            append("}</style>")
        }
    }

    /**
     * O HTML do código de cada linha, com o realce do editor. As removidas são lidas na versão de antes e as outras
     * na de depois, para o lexer ver código que existiu de fato.
     */
    private fun highlight(project: Project, path: String?, lines: List<HunkLine>): List<String> {
        val plain = lines.map { esc(it.text) }
        val name = path?.substringAfterLast('/') ?: return plain
        return runCatching {
            runReadAction {
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
                val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null) ?: return@runReadAction plain
                val scheme = EditorColorsManager.getInstance().globalScheme
                val styles = mutableMapOf<IElementType, String>()
                fun style(type: IElementType) = styles.getOrPut(type) {
                    val attrs = highlighter.getTokenHighlights(type).fold(null as TextAttributes?) { acc, key -> TextAttributes.merge(acc, scheme.getAttributes(key)) }
                    style(attrs, scheme)
                }
                fun lex(indexes: List<Int>): Map<Int, String> {
                    if (indexes.isEmpty()) return emptyMap()
                    val out = Array(indexes.size) { StringBuilder() }
                    val lexer = highlighter.highlightingLexer
                    lexer.start(indexes.joinToString("\n") { lines[it].text })
                    var row = 0
                    while (lexer.tokenType != null) {
                        val css = style(lexer.tokenType!!)
                        lexer.bufferSequence.subSequence(lexer.tokenStart, lexer.tokenEnd).toString().split('\n').forEachIndexed { part, piece ->
                            if (part > 0) row++
                            if (piece.isNotEmpty() && row < out.size) {
                                if (css.isEmpty()) out[row].append(esc(piece))
                                else out[row].append("<span style=\"").append(css).append("\">").append(esc(piece)).append("</span>")
                            }
                        }
                        lexer.advance()
                    }
                    return indexes.withIndex().associate { (i, line) -> line to out[i].toString() }
                }
                val before = lex(lines.indices.filter { lines[it].kind == HunkLine.Kind.CONTEXT || lines[it].kind == HunkLine.Kind.DEL })
                val after = lex(lines.indices.filter { lines[it].kind == HunkLine.Kind.CONTEXT || lines[it].kind == HunkLine.Kind.ADD })
                lines.indices.map { i -> (if (lines[i].kind == HunkLine.Kind.DEL) before[i] else after[i]) ?: plain[i] }
            }
        }.getOrDefault(plain)
    }

    private fun style(attrs: TextAttributes?, scheme: EditorColorsScheme): String {
        attrs ?: return ""
        return buildString {
            attrs.foregroundColor?.takeIf { it != scheme.defaultForeground }?.let { append("color:").append(hex(it)).append(';') }
            if (attrs.fontType and Font.BOLD != 0) append("font-weight:700;")
            if (attrs.fontType and Font.ITALIC != 0) append("font-style:italic;")
        }
    }

    private fun hex(color: Color) = "#%02X%02X%02X".format(color.red, color.green, color.blue)

    private fun esc(text: String) = ArtifactHtml.esc(text)
}
