package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.mio.tomahawk.base.FlowRow
import com.mio.tomahawk.base.MechanismList
import com.mio.tomahawk.base.WidthPanel
import com.mio.tomahawk.base.WrapText
import java.awt.Dimension
import com.mio.tomahawk.base.NoticeBox
import com.mio.tomahawk.base.Pill
import com.mio.tomahawk.base.Segment
import com.mio.tomahawk.base.Stat
import com.mio.tomahawk.base.SummaryHeader
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.refreshAction
import com.mio.tomahawk.base.settingsAction
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.Mission
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.speckit.StepState
import com.mio.tomahawk.speckit.StepStatus
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/** Uma opção do seletor de feature: uma pasta de `specs/` ou uma feature nova, a designar. */
private sealed interface FeatureChoice {
    data class Existing(val report: FeatureReport) : FeatureChoice
    data object New : FeatureChoice
}

/**
 * A aba Lançador: a missão SDD da feature escolhida, etapa por etapa, com o estado de cada uma e a próxima em
 * destaque. Escolhida a etapa, escreve-se a entrada e lança-se pelo CLI de IA, na aba ou no terminal.
 */
class LauncherPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private val header = SummaryHeader(TomahawkIcons.lancador, Tone.ORANGE)
    private val initNotice = NoticeBox(AllIcons.General.Information, Tone.BLUE).apply {
        text = "Este projeto ainda não tem o Spec Kit (.specify/). Inicialize para instalar os comandos /speckit no agente escolhido."
    }
    private val initButton = JButton("Inicializar Spec Kit", AllIcons.Actions.Execute).apply { addActionListener { runner.initSpecKit() } }
    private val initPanel = JPanel(VerticalLayout(JBUI.scale(6))).apply {
        border = JBUI.Borders.empty(8, 12)
        add(initNotice)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(initButton) })
    }

    private val featureModel = DefaultComboBoxModel<FeatureChoice>()
    private val feature = ComboBox(featureModel).apply {
        renderer = SimpleListCellRenderer.create("") { choice ->
            when (choice) {
                is FeatureChoice.Existing -> choice.report.name + if (choice.report.dir == service.snapshot.active?.dir) "  (ativa)" else ""
                FeatureChoice.New -> "+ Nova feature (Designar Alvo)"
                null -> ""
            }
        }
        addActionListener { if (!loading) featureChanged() }
    }
    private val openSpec = JButton(AllIcons.Actions.MenuOpen).apply {
        toolTipText = "Abrir a spec.md da feature"
        addActionListener { selectedFeature()?.spec?.file?.let { openFile(project, it) } }
    }

    private val stepsModel = DefaultListModel<StepStatus>()
    private val steps = MechanismList(stepsModel, TomahawkIcons.lancador, Tone.ORANGE).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = StepRenderer()
        emptyText.text = "Inicialize o Spec Kit para ver as etapas"
        addListSelectionListener { if (!it.valueIsAdjusting && !loading) stepChanged() }
    }

    private val stepTitle = WrapText(font = JBFont.h4())
    private val stepPills = wrapRow()
    private val purpose = WrapText()
    private val reasonIcon = JBLabel()
    private val reasonText = WrapText(color = UIUtil.getContextHelpForeground())
    private val reason = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        add(JPanel(BorderLayout()).apply { add(reasonIcon, BorderLayout.NORTH) }, BorderLayout.WEST)
        add(reasonText, BorderLayout.CENTER)
    }
    private val commandLink = ActionLink("") { selectedStatus()?.let { s -> service.snapshot.commands[s.step]?.let { openFile(project, it.file) } } }
    private val input = JBTextArea(6, 30).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.label()
    }
    private val mode = ComboBox(RunMode.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val launch = JButton("Lançar", TomahawkIcons.lancador).apply { addActionListener { launch() } }
    private val copy = JButton("Copiar Comando", AllIcons.Actions.Copy).apply {
        toolTipText = "Copia o comando com a entrada, para colar no chat do agente"
        addActionListener { mission()?.let(runner::copyCommand) }
    }
    private val openArtifact = JButton("Abrir Artefato", AllIcons.Actions.MenuOpen).apply { addActionListener { artifactOf()?.let { openFile(project, it) } } }

    /** O texto escrito em cada etapa, para trocar de etapa sem perder o rascunho. */
    private val drafts = mutableMapOf<SddStep, String>()
    private var currentStep: SddStep? = null
    private var lastActive: Path? = null
    private var loading = false

    init {
        val featureBar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(8, 12, 6, 12)
            add(JBLabel("Feature:"), BorderLayout.WEST)
            add(feature, BorderLayout.CENTER)
            add(openSpec, BorderLayout.EAST)
        }
        val detail = WidthPanel(VerticalLayout(JBUI.scale(6), VerticalLayout.FILL)).apply {
            border = JBUI.Borders.empty(10, 12)
            add(stepTitle)
            add(stepPills)
            add(purpose)
            add(reason)
            add(commandLink)
            add(JBLabel("Entrada (\$ARGUMENTS):").apply { border = JBUI.Borders.emptyTop(4) })
            add(ScrollPaneFactory.createScrollPane(input).apply { preferredSize = Dimension(JBUI.scale(120), JBUI.scale(110)) })
            add(wrapRow().apply {
                add(JBLabel("Onde lançar:"))
                add(mode)
            })
            add(wrapRow().apply {
                add(launch)
                add(copy)
                add(openArtifact)
            })
            add(WrapText(
                "Na aba, a Telemetria acompanha cada passo e grava o registro em tomahawk/execucoes/. No terminal, você conversa com a IA (bom para o Reconhecimento, que faz perguntas).",
                JBFont.small(),
                UIUtil.getContextHelpForeground(),
            ))
        }
        val splitter = OnePixelSplitter(true, 0.42f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(steps, true)
            secondComponent = ScrollPaneFactory.createScrollPane(detail, true)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(MissionProgressPanel(project, this@LauncherPanel))
                add(initPanel)
                add(featureBar)
            }, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "Tomahawk.LauncherToolbar",
            DefaultActionGroup(refreshAction { service.refresh(0) }, settingsAction(project)),
            true,
        ).apply { targetComponent = this@LauncherPanel }.component
        steps.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) input.requestFocusInWindow()
            }
        })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { reload(it) })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener { ApplicationManager.getApplication().invokeLater({ updateButtons() }, project.disposed) },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener { ApplicationManager.getApplication().invokeLater({ reload(service.snapshot) }, project.disposed) },
        )
        reload(service.snapshot)
    }

    override fun dispose() = Unit

    private fun reload(snapshot: Snapshot) {
        loading = true
        try {
            initPanel.isVisible = !snapshot.initialized && snapshot.layout != null
            val previous = feature.selectedItem as? FeatureChoice
            featureModel.removeAllElements()
            snapshot.features.forEach { featureModel.addElement(FeatureChoice.Existing(it)) }
            featureModel.addElement(FeatureChoice.New)
            // Com "Nova feature" escolhida, a seleção só segue a ativa quando ela muda: o specify acabou de criar uma.
            val activeChanged = snapshot.active?.dir != lastActive
            lastActive = snapshot.active?.dir
            featureModel.selectedItem = if (previous == FeatureChoice.New && !activeChanged) FeatureChoice.New
            else snapshot.active?.let { FeatureChoice.Existing(it) } ?: FeatureChoice.New
            feature.isEnabled = snapshot.initialized
            fillSteps(snapshot)
        } finally {
            loading = false
        }
        stepChanged()
        updateHeader(snapshot)
    }

    private fun fillSteps(snapshot: Snapshot) {
        val chosen = currentStep
        stepsModel.clear()
        if (!snapshot.initialized) return
        val statuses = snapshot.statuses(selectedFeature())
        statuses.forEach(stepsModel::addElement)
        val select = statuses.indexOfFirst { it.step == chosen }.takeIf { it >= 0 } ?: statuses.indexOfFirst { it.next }.coerceAtLeast(0)
        if (statuses.isNotEmpty()) steps.selectedIndex = select
    }

    private fun featureChanged() {
        when (val choice = feature.selectedItem as? FeatureChoice) {
            is FeatureChoice.Existing -> if (choice.report.dir != service.snapshot.active?.dir) service.activate(choice.report.dir)
            FeatureChoice.New -> currentStep = SddStep.SPECIFY
            null -> Unit
        }
        loading = true
        try {
            fillSteps(service.snapshot)
        } finally {
            loading = false
        }
        stepChanged()
        updateHeader(service.snapshot)
    }

    private fun stepChanged() {
        currentStep?.let { drafts[it] = input.text }
        val status = selectedStatus()
        val switched = status?.step != currentStep
        currentStep = status?.step
        val snapshot = service.snapshot
        if (status == null) {
            stepTitle.text = if (snapshot.initialized) "Escolha uma etapa" else "Spec Kit não inicializado"
            stepPills.removeAll()
            purpose.text = ""
            reason.isVisible = false
            commandLink.isVisible = false
            input.text = ""
            updateButtons()
            return
        }
        val step = status.step
        stepTitle.text = "${step.number}. ${step.codename}"
        stepPills.removeAll()
        stepPills.add(Pill(snapshot.invocation(step), Tone.ORANGE, bold = true))
        stepPills.add(Pill(status.state.label, status.state.tone))
        if (step.optional) stepPills.add(Pill("Opcional", Tone.GRAY))
        if (step.readOnly) stepPills.add(Pill("Só leitura", Tone.GRAY))
        if (step.interactive) stepPills.add(Pill("Faz perguntas", Tone.PURPLE))
        purpose.text = step.purpose
        reason.isVisible = true
        reasonIcon.icon = if (status.state == StepState.DONE) AllIcons.General.InspectionsOK else AllIcons.General.Information
        reasonText.text = status.reason
        val ref = snapshot.commands[step]
        commandLink.isVisible = ref != null
        commandLink.text = ref?.let { "Abrir o comando (${it.file.parent?.fileName?.takeIf { _ -> it.skill } ?: it.file.fileName})" }.orEmpty()
        commandLink.toolTipText = ref?.let { snapshot.relative(it.file) }
        if (switched) {
            input.text = drafts[step].orEmpty()
            input.emptyText.text = step.argsHint
            mode.selectedItem = if (step.interactive && LaunchTerminal.available() != null) RunMode.TERMINAL else TomahawkSettings.runMode
        }
        updateButtons()
        stepPills.revalidate()
        stepPills.repaint()
    }

    private fun updateButtons() {
        val status = selectedStatus()
        val flying = runner.progress != null
        launch.isEnabled = status != null && !flying && service.snapshot.initialized &&
            status.state != StepState.BLOCKED && status.state != StepState.UNAVAILABLE
        launch.toolTipText = when {
            flying -> "Há uma missão em voo: acompanhe na Telemetria"
            status?.state == StepState.BLOCKED -> status.reason
            status?.state == StepState.UNAVAILABLE -> status.reason
            else -> null
        }
        copy.isEnabled = status != null
        openArtifact.isEnabled = artifactOf() != null
    }

    private fun updateHeader(snapshot: Snapshot) {
        val cliMissing = TomahawkSettings.resolveExecutable() == null
        val cliWarning = if (cliMissing) "CLI do ${TomahawkSettings.provider.title} não encontrado: ${TomahawkSettings.provider.installHint}" else null
        if (snapshot.layout == null) {
            if (project.basePath == null) header.update("Projeto sem pasta", Tone.GRAY.accent, "Abra um projeto com pasta no disco.")
            else header.update("Lendo o Spec Kit…", Tone.GRAY.accent, "Procurando .specify/ e specs/ no projeto.")
            return
        }
        if (!snapshot.initialized) {
            header.update(
                "Spec Kit não inicializado",
                Tone.GRAY.accent,
                "O Tomahawk dispara os comandos /speckit pelo ${TomahawkSettings.provider.title} CLI.",
                warning = cliWarning,
            )
            return
        }
        val chosen = selectedFeature()
        val statuses = snapshot.statuses(chosen)
        val next = statuses.firstOrNull { it.next }
        val tasks = chosen?.tasks
        val title = chosen?.name ?: "Nova feature"
        val subtitle = when {
            next != null -> "Próxima etapa: ${next.step.codename} (${snapshot.invocation(next.step)})"
            statuses.any { it.step == SddStep.CONVERGE && it.state == StepState.DONE } -> "Missão cumprida: a implementação convergiu."
            else -> "Nenhuma etapa pendente."
        }
        val counts = statuses.groupingBy { it.state }.eachCount()
        header.update(
            title,
            (next?.state?.tone ?: Tone.GREEN).accent,
            subtitle + "  ·  ${TomahawkSettings.provider.title}",
            stats = listOf(
                Stat(counts[StepState.DONE] ?: 0, "etapas concluídas", Tone.GREEN),
                Stat(tasks?.done ?: 0, "tarefas feitas", Tone.BLUE),
                Stat((tasks?.total ?: 0) - (tasks?.done ?: 0), "tarefas abertas", Tone.ORANGE),
                Stat(chosen?.pending?.size ?: 0, "esclarecimentos pendentes", Tone.YELLOW),
            ),
            bar = StepState.entries.map { Segment(counts[it] ?: 0, it.tone.accent) },
            warning = cliWarning ?: if (!snapshot.constitutionReady) "Defina as Regras de Engajamento (/speckit constitution) antes das outras etapas." else null,
        )
    }

    private fun launch() {
        val mission = mission() ?: return
        if (runner.launch(mission)) {
            drafts.remove(mission.step)
            input.text = ""
        }
    }

    private fun mission(): Mission? {
        val status = selectedStatus() ?: return null
        val chosen = selectedFeature()
        return Mission(
            step = status.step,
            arguments = input.text,
            feature = chosen?.takeIf { status.step != SddStep.SPECIFY }?.let { service.snapshot.relative(it.dir) },
            mode = mode.selectedItem as? RunMode,
        )
    }

    private fun artifactOf(): Path? {
        val step = selectedStatus()?.step ?: return null
        val chosen = selectedFeature()
        return when (step) {
            SddStep.CONSTITUTION -> service.snapshot.layout?.constitution
            SddStep.SPECIFY, SddStep.CLARIFY -> chosen?.spec?.file
            SddStep.PLAN -> chosen?.plan?.file
            SddStep.CHECKLIST -> chosen?.customChecklists?.firstOrNull()?.file ?: chosen?.checklists?.firstOrNull()?.file
            SddStep.TASKS, SddStep.IMPLEMENT, SddStep.CONVERGE, SddStep.ANALYZE -> chosen?.tasks?.file
        }?.takeIf { java.nio.file.Files.exists(it) }
    }

    private fun selectedFeature(): FeatureReport? = (feature.selectedItem as? FeatureChoice.Existing)?.report

    private fun selectedStatus(): StepStatus? = steps.selectedValue

    private fun wrapRow() = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
        isOpaque = false
        border = JBUI.Borders.empty()
    }

    /** Linha da etapa: número, nome, estado e comando, que passam para baixo quando a aba é estreita; o motivo fica na dica. */
    private inner class StepRenderer : ListCellRenderer<StepStatus> {
        override fun getListCellRendererComponent(list: JList<out StepStatus>, value: StepStatus?, index: Int, selected: Boolean, focused: Boolean): Component {
            val padding = JBUI.scale(10)
            val panel = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getListBackground(selected, focused)
                border = JBUI.Borders.empty(5, 10)
            }
            value ?: return panel
            val foreground = UIUtil.getListForeground(selected, focused)
            val muted = value.state == StepState.BLOCKED || value.state == StepState.UNAVAILABLE
            val line = FlowRow((list.width - 2 * padding).coerceAtLeast(JBUI.scale(120)))
            line.add(Pill(value.step.number.toString(), value.state.tone, bold = true))
            line.add(WrapText(value.step.codename, JBFont.label().asBold(), if (muted && !selected) UIUtil.getContextHelpForeground() else foreground))
            line.add(Pill(value.state.label, value.state.tone))
            if (value.next) line.add(Pill("Próxima", Tone.ORANGE, bold = true))
            line.add(WrapText(service.snapshot.invocation(value.step), JBFont.small(), if (selected) foreground else UIUtil.getContextHelpForeground()))
            panel.add(line, BorderLayout.CENTER)
            panel.toolTipText = value.reason
            return panel
        }
    }
}