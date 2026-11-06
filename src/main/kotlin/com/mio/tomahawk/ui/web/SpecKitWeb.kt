package com.mio.tomahawk.ui.web

import com.intellij.openapi.util.SystemInfo
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.run.WindowsShell
import com.mio.tomahawk.view.ArtifactHtml
import java.nio.file.Path

/**
 * O Spec Kit nas abas: o `specify` nesta máquina (instalando, faltando, com defeito ou com versão nova), o shell dos
 * comandos dele e o bloco de inicializar o projeto.
 */
object SpecKitWeb {

    /** O aviso sobre o `specify` em [specify]; vazio quando ele está instalado e em dia. */
    fun machine(specify: Path?): String {
        SpecKitSetup.job?.let { job ->
            return notice(
                "info", "⏳",
                "<b>${job.label}…</b> Acompanhe na aba <b>${esc(job.title)}</b> do terminal da IDE; esta tela atualiza quando terminar.",
                WebUi.button("Parar de esperar", "specify-recheck", "ghost sm"),
            )
        }
        if (specify == null) {
            val uv = TomahawkSettings.resolveUv()
            val how = if (uv != null) "O <b>uv</b> já está aqui e instala o <code>${SpecKitSetup.PACKAGE}</code>."
            else "Falta também o <b>uv</b>: o instalador oficial da Astral roda antes, e depois o <code>${SpecKitSetup.PACKAGE}</code>."
            return notice(
                "warn", "📦",
                "O <b>specify</b>, o CLI do Spec Kit, não está instalado nesta máquina. $how Tudo roda no terminal da IDE, onde você acompanha." +
                    "<div class=\"cmdline\" style=\"margin:8px 0 0\">uv tool install ${SpecKitSetup.PACKAGE}</div>",
                WebUi.button("📦 Instalar o Spec Kit", "specify-install", "primary") + " " + WebUi.button("↻ Verificar de novo", "specify-recheck", "ghost"),
            )
        }
        val info = SpecKitSetup.infoFor(specify)
        if (info?.broken == true) {
            return notice(
                "err", "🔧",
                "O <b>specify</b> desta máquina está com defeito: o executável existe, mas a instalação dele no uv perdeu o pacote " +
                    "<code>${SpecKitSetup.PACKAGE}</code>, e nenhum comando do Spec Kit roda. Reinstale por cima:" +
                    "<div class=\"cmdline\" style=\"margin:8px 0 0\">uv tool install --force ${SpecKitSetup.PACKAGE}</div>",
                WebUi.button("🔧 Reinstalar o Spec Kit", "specify-repair", "primary") + " " + WebUi.button("↻ Verificar de novo", "specify-recheck", "ghost"),
            )
        }
        if (info != null && info.outdated) {
            return notice(
                "info", "⬆️",
                "Há uma versão nova do Spec Kit: <b>v${esc(info.latest.orEmpty())}</b> (aqui está a v${esc(info.version ?: "?")}).",
                WebUi.button("⬆ Atualizar o Spec Kit", "specify-upgrade", title = "Roda specify self upgrade no terminal da IDE"),
            )
        }
        return ""
    }

    /** No Windows, onde os comandos do Spec Kit rodam: no shell padrão do terminal da IDE, no PowerShell ou no CMD. */
    fun shellPicker(): String {
        if (!SystemInfo.isWindows) return ""
        val options = listOf(IDE to "💻 Terminal da IDE") + WindowsShell.entries.map { it.name to it.label }
        return "<div class=\"setup-row\"><span class=\"setup-label\">Rodar em</span>" +
            WebUi.seg("shell", options, TomahawkSettings.specKitShell?.name ?: IDE, send = "specify-shell") + "</div>"
    }

    /** O que [value], a escolha do [shellPicker], guarda nas configurações. */
    fun choose(value: String?) {
        TomahawkSettings.specKitShell = WindowsShell.entries.firstOrNull { it.name == value }
    }

    /** O bloco de inicializar o Spec Kit no projeto: o shell, o comando e os botões de rodar e de copiar. */
    fun initBox(specify: Path?): String = buildString {
        val broken = SpecKitSetup.infoFor(specify)?.broken == true
        val busy = SpecKitSetup.job != null
        val integration = TomahawkSettings.provider.integration
        append("<div class=\"setup-box\">")
        append(shellPicker())
        append("<div class=\"cmdline\">specify init --here --integration ").append(esc(integration)).append("</div>")
        append("<div class=\"row-btns center\">")
        append(
            WebUi.button(
                "⚡ Inicializar o Spec Kit", "init", "primary", enabled = specify != null && !broken && !busy,
                title = when {
                    specify == null -> "Instale o Spec Kit nesta máquina primeiro"
                    broken -> "Reinstale o Spec Kit primeiro: o specify desta máquina está com defeito"
                    else -> "Roda specify init --here no terminal escolhido"
                },
            ),
        )
        append(WebUi.button("⧉ Copiar comando", "init-copy", "ghost", enabled = specify != null, title = "Copia o comando completo, para rodar fora da IDE"))
        append("</div></div>")
    }

    private const val IDE = "IDE"

    private fun notice(kind: String, icon: String, html: String, action: String): String =
        "<div class=\"notice $kind\" style=\"margin:10px 0 0\"><span class=\"ni\">$icon</span><div class=\"nb\">$html<div class=\"row-btns\">$action</div></div></div>"

    private fun esc(text: String) = ArtifactHtml.esc(text)
}
