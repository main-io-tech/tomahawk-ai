package com.mio.tomahawk.view

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.mio.tomahawk.speckit.CheckItem
import com.mio.tomahawk.speckit.Markdown
import com.mio.tomahawk.ui.openArtifact
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import java.awt.BorderLayout
import java.awt.Color
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * Mostra um Markdown do Spec Kit formatado pelo [ArtifactHtml], no navegador embutido (JCEF). Na página, marcar
 * uma caixa grava `[x]` ou `[ ]` no arquivo (com desfazer), os links abrem o artefato na IDE ou no navegador e a
 * rolagem se mantém quando o arquivo muda. Sem JCEF, mostra o documento num painel HTML simples.
 */
class ArtifactView(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val query: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val fallback: JEditorPane? = if (browser == null) JEditorPane("text/html", "").apply { isEditable = false } else null

    private var file: Path? = null
    private var displayPath: String = ""
    private var lastHtml: String? = null
    @Volatile
    private var scrollY = 0

    init {
        Disposer.register(parent, this)
        if (browser != null) {
            Disposer.register(this, browser)
            query?.let { Disposer.register(this, it) }
            query?.addHandler { message ->
                ApplicationManager.getApplication().invokeLater({ handle(message) }, project.disposed)
                null
            }
            add(browser.component, BorderLayout.CENTER)
        } else {
            add(ScrollPaneFactory.createScrollPane(fallback, true), BorderLayout.CENTER)
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener { reload() })
    }

    /** Mostra [path]; [text] nulo lê o documento aberto na IDE ou o arquivo. Outro arquivo começa do topo. */
    fun show(path: Path, text: String? = null, display: String = relative(path)) {
        if (path != file) scrollY = 0
        file = path
        displayPath = display
        render(text ?: read(path))
    }

    fun clear() {
        file = null
        lastHtml = null
        browser?.loadHTML("<html><body style=\"background:${hex(background())}\"></body></html>")
        fallback?.text = ""
    }

    /** Relê o arquivo atual: depois de mudanças no disco ou no tema. */
    fun reload() {
        val current = file ?: return
        lastHtml = null
        render(read(current))
    }

    private fun render(text: String) {
        val current = file ?: return
        if (browser == null) {
            fallback?.text = ArtifactHtml.simple(text)
            fallback?.caretPosition = 0
            return
        }
        val bridge = query?.let { "function(msg){ ${it.inject("msg")} }" }
        val html = ArtifactHtml.render(current, text, theme(), displayPath, bridge, scrollY)
        if (html == lastHtml) return
        lastHtml = html
        browser.loadHTML(html)
    }

    private fun handle(message: String) {
        when {
            message.startsWith("scroll:") -> scrollY = message.removePrefix("scroll:").toIntOrNull() ?: scrollY
            message.startsWith("toggle:") -> {
                val rest = message.removePrefix("toggle:")
                toggle(rest.substringBefore(':').toIntOrNull() ?: return, rest.substringAfter(':', ""))
            }
            message.startsWith("open:") -> open(message.removePrefix("open:"))
            message.startsWith("copy:") -> CopyPasteManager.getInstance().setContents(StringSelection(message.removePrefix("copy:")))
        }
    }

    /** Inverte a caixa do item de número [index] na página, conferido pelo começo do texto para não marcar o errado. */
    private fun toggle(index: Int, hint: String) {
        val path = file ?: return
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtual) ?: return
        val lines = document.text.lines()
        val items = Markdown.checkItems(lines)
        val item = items.getOrNull(index)?.takeIf { sameItem(it, hint) } ?: items.firstOrNull { sameItem(it, hint) } ?: run {
            LOG.info("Item $index não encontrado em $path")
            return
        }
        val bracket = BRACKET.find(lines[item.line]) ?: return
        val offset = document.getLineStartOffset(item.line) + bracket.range.first + 1
        WriteCommandAction.runWriteCommandAction(project, if (item.done) "Desmarcar Item" else "Marcar Item", null, {
            document.replaceString(offset, offset + 1, if (item.done) " " else "x")
        })
        FileDocumentManager.getInstance().saveDocument(document)
        render(document.text)
    }

    private fun sameItem(item: CheckItem, hint: String): Boolean {
        val expected = letters(item.text)
        val shown = letters(hint)
        if (shown.isEmpty()) return false
        val size = minOf(MATCH_CHARS, expected.length, shown.length)
        return size > 0 && expected.take(size) == shown.take(size)
    }

    private fun letters(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

    private fun open(href: String) {
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
            BrowserUtil.browse(href)
            return
        }
        val base = file?.parent ?: return
        val target = runCatching { base.resolve(href.substringBefore('#').replace('/', java.io.File.separatorChar)).normalize() }.getOrNull() ?: return
        if (Files.exists(target)) openArtifact(project, target)
    }

    private fun read(path: Path): String {
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(path)
        virtual?.let { FileDocumentManager.getInstance().getCachedDocument(it) }?.let { return it.text }
        return runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrDefault("")
    }

    private fun relative(path: Path): String {
        val base = project.basePath?.let(Path::of) ?: return path.fileName.toString()
        return runCatching { base.relativize(path).toString().replace('\\', '/') }.getOrDefault(path.toString())
    }

    private fun theme(): HtmlTheme {
        val dark = !JBColor.isBright()
        val scheme = EditorColorsManager.getInstance().globalScheme
        return HtmlTheme(dark, hex(scheme.defaultBackground), hex(scheme.defaultForeground))
    }

    private fun background(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground

    private fun hex(color: Color) = "#%02X%02X%02X".format(color.red, color.green, color.blue)

    override fun dispose() = Unit

    private companion object {
        val LOG = logger<ArtifactView>()
        val BRACKET = Regex("""\[([ xX])]""")
        const val MATCH_CHARS = 24
    }
}
