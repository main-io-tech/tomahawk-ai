package com.mio.tomahawk.ui.web

import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.view.ArtifactHtml
import java.nio.file.Path

/** O Spec Kit nesta máquina nas abas: instalando, faltando (com o botão de instalar) ou com versão nova. */
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
        if (info != null && info.outdated) {
            return notice(
                "info", "⬆️",
                "Há uma versão nova do Spec Kit: <b>v${esc(info.latest.orEmpty())}</b> (aqui está a v${esc(info.version ?: "?")}).",
                WebUi.button("⬆ Atualizar o Spec Kit", "specify-upgrade", title = "Roda specify self upgrade no terminal da IDE"),
            )
        }
        return ""
    }

    private fun notice(kind: String, icon: String, html: String, action: String): String =
        "<div class=\"notice $kind\" style=\"margin:10px 0 0\"><span class=\"ni\">$icon</span><div class=\"nb\">$html<div class=\"row-btns\">$action</div></div></div>"

    private fun esc(text: String) = ArtifactHtml.esc(text)
}
