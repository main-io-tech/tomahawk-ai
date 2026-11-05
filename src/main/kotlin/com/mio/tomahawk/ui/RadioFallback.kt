package com.mio.tomahawk.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.mio.tomahawk.base.SummaryHeader
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.WrapText
import com.mio.tomahawk.delivery.PrRadarService
import java.awt.BorderLayout
import javax.swing.JPanel

/** O Rádio sem o navegador embutido: quantas conversas esperam resposta; os avisos chegam pelas notificações. */
class RadioFallback(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val header = SummaryHeader(TomahawkIcons.radio, Tone.PURPLE)
    private val radar = PrRadarService.getInstance(project)

    init {
        add(header, BorderLayout.NORTH)
        add(JPanel(VerticalLayout(JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            add(WrapText("As conversas e a resposta com a IA precisam do navegador embutido (JCEF). Os comentários novos dos seus PRs continuam chegando pelas notificações."))
        }, BorderLayout.CENTER)
        project.messageBus.connect(this).subscribe(
            PrRadarService.CHANGED,
            PrRadarService.Listener { ApplicationManager.getApplication().invokeLater({ update() }, project.disposed) },
        )
        update()
    }

    private fun update() {
        val pending = radar.pendingCount()
        val prs = radar.snapshot?.prs?.size ?: 0
        header.update(
            if (pending > 0) "$pending conversa(s) aguardando resposta" else "Nenhuma conversa aguardando resposta",
            if (pending > 0) Tone.YELLOW.accent else Tone.GREEN.accent,
            "Vigiando $prs PR(s) abertos por você.",
        )
    }

    override fun dispose() = Unit
}
