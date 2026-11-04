package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.mio.tomahawk.base.SummaryHeader
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.WrapText
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.settings.TomahawkConfigurable
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** O Arsenal sem o navegador embutido: o estado da IA e o atalho para a configuração em Settings. */
class ArsenalFallback(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val header = SummaryHeader(TomahawkIcons.arsenal, Tone.TEAL)

    init {
        add(header, BorderLayout.NORTH)
        add(JPanel(VerticalLayout(JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            add(WrapText("Configure o CLI de IA, faça a configuração automática e o teste da integração em Settings > Tools > Tomahawk IA."))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JButton("Abrir Configurações", AllIcons.General.Settings).apply {
                    addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, TomahawkConfigurable::class.java) }
                })
            })
        }, BorderLayout.CENTER)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener { ApplicationManager.getApplication().invokeLater({ update() }, project.disposed) },
        )
        update()
    }

    private fun update() {
        val launch = TomahawkSettings.launch()
        when {
            launch == null -> header.update("IA não configurada", Tone.GRAY.accent, TomahawkSettings.provider.installHint)
            !TomahawkSettings.ready -> header.update("Teste a integração", Tone.YELLOW.accent, "${TomahawkSettings.provider.title} CLI${launch.where} encontrado; falta testar.")
            else -> header.update("IA pronta", Tone.GREEN.accent, "${TomahawkSettings.provider.title} CLI${launch.where} · testada em ${TomahawkSettings.verifiedAt.orEmpty()}")
        }
    }

    override fun dispose() = Unit
}
