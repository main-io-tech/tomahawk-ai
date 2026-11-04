package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import javax.swing.JPanel
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import com.mio.tomahawk.view.ArtifactHtml
import com.mio.tomahawk.view.Diagrams
import com.mio.tomahawk.view.HtmlTheme
import java.awt.Color
import javax.swing.JComponent

/**
 * Uma aba do Tomahawk desenhada no navegador embutido (JCEF), com o visual das páginas de artefato. A página manda
 * mensagens JSON (`{c: comando, ...}`) que chegam em [onMessage] na EDT. Recarregar a página preserva a rolagem e
 * espera o usuário sair do campo em que está digitando.
 */
abstract class WebTab(protected val project: Project) : Disposable {

    /** Nulo sem JCEF (como nos testes): a página ainda pode ser montada por [html]. */
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val query: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    val component: JComponent by lazy { browser?.component ?: JPanel() }

    /** O tom da página: `orange`, `purple`, `blue`… */
    protected abstract val accent: String

    /** O conteúdo da página, dentro do `<div class="wrap">`. */
    protected abstract fun body(): String

    protected abstract fun onMessage(command: String, data: JsonObject)

    @Volatile
    private var scrollY = 0
    private var typing = false
    private var pending = false
    private var lastHtml: String? = null

    init {
        browser?.let { Disposer.register(this, it) }
        query?.let { q ->
            Disposer.register(this, q)
            q.addHandler { raw ->
                ApplicationManager.getApplication().invokeLater({ dispatch(raw) }, project.disposed)
                null
            }
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener { refresh(force = true) })
    }

    private fun dispatch(raw: String) {
        val data = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        when (val command = data.str("c").orEmpty()) {
            "scroll" -> scrollY = data.get("y")?.asInt ?: scrollY
            "focus" -> typing = true
            "blur" -> {
                typing = false
                if (pending) refresh()
            }
            else -> runCatching { onMessage(command, data) }.onFailure { LOG.warn("A aba do Tomahawk não tratou \"$command\"", it) }
        }
    }

    /** Redesenha a página; enquanto o usuário digita num campo, espera ele sair dele, para não perder o foco. */
    fun refresh(force: Boolean = false) {
        val browser = browser ?: return
        if (browser.isDisposed) return
        if (typing && !force) {
            pending = true
            return
        }
        pending = false
        val html = html()
        if (html == lastHtml) return
        lastHtml = html
        browser.loadHTML(html)
    }

    /** A página inteira, como o navegador a recebe. */
    fun html(): String = page(body())

    protected fun js(code: String) {
        val browser = browser ?: return
        if (browser.isDisposed) return
        val cef = browser.cefBrowser
        cef.executeJavaScript(code, cef.url, 0)
    }

    private fun page(content: String): String {
        val theme = theme()
        val bridge = query?.let { "function(msg){ ${it.inject("msg")} }" } ?: "function(msg){}"
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><style>")
            append(ArtifactHtml.baseCss(theme, accent)).append(Diagrams.CSS).append(WebUi.CSS)
            append("</style></head><body class=\"app\"><div class=\"wrap\">").append(content).append("</div>")
            append("<script>").append(WebUi.script(bridge, scrollY)).append("</script></body></html>")
        }
    }

    protected fun theme(): HtmlTheme {
        val background = UIUtil.getPanelBackground()
        val foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        return HtmlTheme(!JBColor.isBright(), hex(background), hex(foreground))
    }

    private fun hex(color: Color) = "#%02X%02X%02X".format(color.red, color.green, color.blue)

    override fun dispose() = Unit

    protected companion object {
        private val LOG = logger<WebTab>()

        fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

        fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()

        fun esc(text: String) = ArtifactHtml.esc(text)

        /** Texto de uma linha para uma string JavaScript entre aspas simples. */
        fun jsString(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "").replace("</", "<\\/") + "'"
    }
}
