package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.tomahawk.base.FlowRow
import com.mio.tomahawk.base.MechanismList
import com.mio.tomahawk.base.WrapText
import com.mio.tomahawk.base.Pill
import com.mio.tomahawk.base.Segment
import com.mio.tomahawk.base.Stat
import com.mio.tomahawk.base.SummaryHeader
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.refreshAction
import com.mio.tomahawk.run.LogKind
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunRecord
import com.mio.tomahawk.run.RunStatus
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.view.ArtifactView
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * A aba Telemetria: a missão em voo com o log ao vivo de cada passo da IA e o histórico das execuções, com a
 * situação, o veredito e o tempo de cada uma. Selecionar uma execução mostra o registro; o duplo clique o abre.
 */
class TelemetryPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private val header = SummaryHeader(TomahawkIcons.telemetria, Tone.BLUE)
    private val runsModel = DefaultListModel<RunRecord>()
    private val runs = MechanismList(runsModel, TomahawkIcons.telemetria, Tone.BLUE).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RunRenderer()
        emptyText.text = "Nenhuma missão lançada neste projeto ainda"
        addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
    }
    private val viewerTitle = WrapText(font = JBFont.small().asBold()).apply { border = JBUI.Borders.empty(6, 10, 4, 10) }
    private val viewer = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size)
        border = JBUI.Borders.empty(4, 10)
    }
    private val timer = Timer(LIVE_TICK_MS) { if (runs.selectedValue == null) showLive() }
    private var onlyActive = false
    private var lastLogSize = -1

    private val preview = ArtifactView(project, this)
    private val cards = CardLayout()
    private val bottomCards = JPanel(cards)

    init {
        bottomCards.add(ScrollPaneFactory.createScrollPane(viewer, true), LIVE_CARD)
        bottomCards.add(preview, RUN_CARD)
        val bottom = JPanel(BorderLayout()).apply {
            add(viewerTitle, BorderLayout.NORTH)
            add(bottomCards, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(runs, true)
            secondComponent = bottom
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(MissionProgressPanel(project, this@TelemetryPanel))
            }, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "Tomahawk.TelemetryToolbar",
            DefaultActionGroup(
                refreshAction { service.refresh(0) },
                object : DumbAwareAction("Log ao Vivo", "Mostra o log da missão em voo (ou da última)", AllIcons.Debugger.Console) {
                    override fun actionPerformed(e: AnActionEvent) {
                        runs.clearSelection()
                        lastLogSize = -1
                        showLive()
                    }
                },
                object : DumbAwareToggleAction("Só a Feature Ativa", "Mostra só as execuções da feature ativa", AllIcons.General.Filter) {
                    override fun isSelected(e: AnActionEvent) = onlyActive
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        onlyActive = state
                        reload(service.snapshot)
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                object : DumbAwareAction("Abrir Registro", "Abre o registro da execução no editor", AllIcons.Actions.MenuOpen) {
                    override fun actionPerformed(e: AnActionEvent) {
                        runs.selectedValue?.let { runner.open(it.file) }
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = runs.selectedValue != null
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
            ),
            true,
        ).apply { targetComponent = this@TelemetryPanel }.component
        runs.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) runs.selectedValue?.let { runner.open(it.file) }
            }
        })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { reload(it) })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener {
                ApplicationManager.getApplication().invokeLater({
                    if (runner.progress != null) runs.clearSelection()
                    lastLogSize = -1
                    updateHeader(service.snapshot)
                }, project.disposed)
            },
        )
        timer.start()
        Disposer.register(this) { timer.stop() }
        reload(service.snapshot)
        showLive()
    }

    override fun dispose() = Unit

    private fun reload(snapshot: Snapshot) {
        val selected = runs.selectedValue?.file
        val records = if (onlyActive) snapshot.runsOf(snapshot.active) else snapshot.runs
        runsModel.clear()
        records.forEach(runsModel::addElement)
        selected?.let { file -> records.indexOfFirst { it.file == file }.takeIf { it >= 0 }?.let { runs.selectedIndex = it } }
        updateHeader(snapshot)
    }

    private fun updateHeader(snapshot: Snapshot) {
        val records = if (onlyActive) snapshot.runsOf(snapshot.active) else snapshot.runs
        val counts = records.groupingBy { it.status }.eachCount()
        val flying = runner.progress
        val finished = records.filter { it.status == RunStatus.OK || it.status == RunStatus.FAILED }
        val average = finished.takeIf { it.isNotEmpty() }?.map { it.durationSeconds }?.average()?.toLong()
        val title = when {
            flying != null -> "Em voo: ${flying.mission.step.codename}"
            records.isEmpty() -> "Sem telemetria"
            else -> "Última: ${records.first().title.substringBefore(" (")} · ${records.first().status.label}"
        }
        val color = when {
            flying != null -> Tone.ORANGE
            records.firstOrNull()?.status == RunStatus.FAILED -> Tone.RED
            records.isEmpty() -> Tone.GRAY
            else -> Tone.GREEN
        }
        header.update(
            title,
            color.accent,
            listOfNotNull(
                "${records.size} execução(ões)" + if (onlyActive) " da feature ativa" else "",
                average?.let { "duração média ${elapsed(it)}" },
            ).joinToString("  ·  "),
            stats = listOf(
                Stat(counts[RunStatus.OK] ?: 0, "concluídas", Tone.GREEN),
                Stat(counts[RunStatus.FAILED] ?: 0, "falharam", Tone.RED),
                Stat(counts[RunStatus.CANCELLED] ?: 0, "abortadas", Tone.GRAY),
                Stat(counts[RunStatus.TERMINAL] ?: 0, "no terminal", Tone.PURPLE),
            ),
            bar = RunStatus.entries.map { Segment(counts[it] ?: 0, it.tone.accent) },
        )
    }

    /** O log da missão em voo, atualizado pelo timer enquanto nenhuma execução do histórico está selecionada. */
    private fun showLive() {
        cards.show(bottomCards, LIVE_CARD)
        val flying = runner.progress
        val progress = flying ?: runner.latest
        if (progress == null) {
            if (lastLogSize == 0) return
            lastLogSize = 0
            viewerTitle.text = "Log ao vivo"
            viewer.text = "Nenhuma missão em voo. Lance uma etapa no Lançador; aqui aparece cada passo da IA: leituras, edições, comandos e a resposta.\n\n" +
                "Selecione uma execução acima para ver o registro dela."
            return
        }
        val lines = progress.log
        if (lines.size == lastLogSize) return
        lastLogSize = lines.size
        val prefix = if (flying != null) "Log ao vivo" else "Log da última missão"
        viewerTitle.text = "$prefix: ${progress.mission.step.codename} (${progress.invocation})"
        viewer.text = lines.joinToString("\n") { line ->
            val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(line.at), ZoneId.systemDefault()).format(CLOCK)
            val tag = when (line.kind) {
                LogKind.INFO -> "·"
                LogKind.TOOL -> "→"
                LogKind.TEXT -> "IA"
                LogKind.ERROR -> "!!"
                LogKind.RESULT -> "✓"
            }
            "$time  $tag  ${line.text}"
        }
        viewer.caretPosition = viewer.document.length
    }

    private fun showSelected() {
        val record = runs.selectedValue ?: return
        viewerTitle.text = "Registro: ${record.file.fileName}"
        cards.show(bottomCards, RUN_CARD)
        preview.show(record.file)
    }

    private inner class RunRenderer : ListCellRenderer<RunRecord> {
        override fun getListCellRendererComponent(list: JList<out RunRecord>, value: RunRecord?, index: Int, selected: Boolean, focused: Boolean): Component {
            val panel = JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = true
                background = UIUtil.getListBackground(selected, focused)
                border = JBUI.Borders.empty(5, 10)
            }
            value ?: return panel
            val width = (list.width - JBUI.scale(20)).coerceAtLeast(JBUI.scale(120))
            val foreground = UIUtil.getListForeground(selected, focused)
            val muted = if (selected) foreground else UIUtil.getContextHelpForeground()
            val line = FlowRow(width)
            line.add(Pill(value.status.label, value.status.tone, bold = true))
            value.verdict?.let { line.add(Pill(it.label, it.tone)) }
            line.add(WrapText(value.title, JBFont.label().asBold(), foreground))
            panel.add(line)
            val started = if (value.startedAt > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(value.startedAt), ZoneId.systemDefault()).format(WHEN) else ""
            val meta = listOfNotNull(
                started.ifEmpty { null },
                value.feature?.substringAfterLast('/'),
                elapsed(value.durationSeconds).takeIf { value.status != RunStatus.TERMINAL },
                value.provider,
                "${value.filesChanged} arquivo(s)".takeIf { value.filesChanged > 0 },
            ).joinToString("  ·  ")
            panel.add(WrapText(meta, JBFont.small(), muted).fit(width))
            value.error?.let { panel.add(WrapText(it.take(300), JBFont.small(), if (selected) foreground else Tone.RED.foreground).fit(width)) }
            return panel
        }
    }

    private companion object {
        const val LIVE_TICK_MS = 700
        const val LIVE_CARD = "live"
        const val RUN_CARD = "run"
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    }
}
