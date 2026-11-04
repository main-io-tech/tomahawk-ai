package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.tomahawk.base.IconTile
import com.mio.tomahawk.base.Pill
import com.mio.tomahawk.base.StatChip
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.WrapText
import com.intellij.util.ui.WrapLayout
import com.mio.tomahawk.run.MissionRunner
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * A missão em voo: a etapa, o passo de agora com a trilha dos anteriores, os contadores de ações, arquivos e
 * tempo, uma barra pulsante e o botão de abortar. Some quando não há missão em voo.
 */
class MissionProgressPanel(project: Project, parent: Disposable) : JPanel(BorderLayout(JBUI.scale(12), 0)) {

    private val runner = MissionRunner.getInstance(project)
    private val tone = Tone.ORANGE
    private val spinner = AsyncProcessIcon("Tomahawk.Mission").apply { isOpaque = false }
    private val heading = JBLabel("Missão em voo").apply {
        font = JBFont.small().asBold()
        foreground = tone.foreground
    }
    private val title = WrapText(font = JBFont.label().asBold())
    private val pills = wrapRow()
    private val step = WrapText()
    private val trail = WrapText(font = JBFont.small(), color = UIUtil.getContextHelpForeground())
    private val detail = WrapText(font = JBFont.small().asItalic(), color = UIUtil.getContextHelpForeground())
    private val chips = wrapRow()
    private val bar = PulseBar(tone)
    private val abort = JButton("Abortar").apply {
        icon = AllIcons.Actions.Suspend
        addActionListener { runner.progress?.cancel(); update() }
    }
    private val timer = Timer(TICK_MS) { update() }

    init {
        isOpaque = true
        background = tone.background
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 14, 10, 14))
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(IconTile(TomahawkIcons.lancador, tone, scale = 1.35f, size = 34), BorderLayout.NORTH)
        }, BorderLayout.WEST)
        val headingLine = JPanel(HorizontalLayout(JBUI.scale(8), SwingConstants.CENTER)).apply {
            isOpaque = false
            add(spinner)
            add(heading)
        }
        val stepLine = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel(AllIcons.Actions.Forward), BorderLayout.NORTH)
            }, BorderLayout.WEST)
            add(step, BorderLayout.CENTER)
        }
        add(JPanel(VerticalLayout(JBUI.scale(3), VerticalLayout.FILL)).apply {
            isOpaque = false
            add(headingLine)
            add(title)
            add(pills)
            add(stepLine)
            add(trail)
            add(detail)
            add(chips)
            add(bar)
            add(wrapRow().apply { add(abort) })
        }, BorderLayout.CENTER)
        isVisible = false
        project.messageBus.connect(parent).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener { ApplicationManager.getApplication().invokeLater({ update() }, project.disposed) },
        )
        Disposer.register(parent) { timer.stop() }
        Disposer.register(parent, spinner)
        update()
    }

    private fun update() {
        val progress = runner.progress
        val visible = progress != null
        if (visible != isVisible) {
            isVisible = visible
            revalidate()
            repaint()
        }
        if (progress == null) {
            timer.stop()
            spinner.suspend()
            bar.running = false
            return
        }
        if (!timer.isRunning) timer.start()
        spinner.resume()
        bar.running = !progress.cancelled
        heading.text = if (progress.cancelled) "Abortando…" else "Missão em voo"
        title.text = "${progress.mission.step.codename}  ·  ${progress.invocation}"
        pills.removeAll()
        pills.add(Pill(progress.provider.title, tone, bold = true))
        progress.model?.let { pills.add(Pill(it, Tone.GRAY)) }
        progress.mission.feature?.let { pills.add(Pill(it.substringAfterLast('/'), Tone.forKey(it))) }
        if (progress.mission.step.readOnly) pills.add(Pill("Só leitura", Tone.GRAY))
        step.text = progress.step
        val previous = progress.trail
        trail.text = if (previous.isEmpty()) "" else "Antes: " + previous.joinToString("  ‹  ")
        trail.isVisible = previous.isNotEmpty()
        detail.text = progress.detail
        detail.isVisible = progress.detail.isNotBlank() && !progress.cancelled
        val seconds = (System.currentTimeMillis() - progress.startedAt) / 1000
        chips.removeAll()
        chips.add(StatChip(elapsed(seconds), "de voo", tone))
        if (progress.actions > 0) chips.add(StatChip(progress.actions.toString(), if (progress.actions == 1) "ação" else "ações", Tone.BLUE))
        if (progress.filesTouched > 0) {
            chips.add(StatChip(progress.filesTouched.toString(), if (progress.filesTouched == 1) "arquivo alterado" else "arquivos alterados", Tone.PURPLE))
        }
        abort.isEnabled = !progress.cancelled
        bar.tick()
        revalidate()
        repaint()
    }

    private fun wrapRow() = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply { isOpaque = false }

    private companion object {
        const val TICK_MS = 500
    }
}

/** Barra fina com um brilho que percorre o trilho enquanto [running], para dizer "em andamento" sem porcentagem. */
class PulseBar(private val tone: Tone) : JComponent() {

    var running = true
    private var phase = 0f
    private val animation = Timer(40) {
        phase = (phase + 0.012f) % 1f
        repaint()
    }

    init {
        isOpaque = false
    }

    fun tick() {
        if (running && !animation.isRunning) animation.start()
        if (!running && animation.isRunning) animation.stop()
    }

    override fun removeNotify() {
        animation.stop()
        super.removeNotify()
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(160), JBUI.scale(8))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val height = JBUI.scale(4).toFloat()
            val top = (this.height - height) / 2
            val track = RoundRectangle2D.Float(0f, top, width.toFloat(), height, height, height)
            g2.color = Tone.GRAY.background
            g2.fill(track)
            if (!running) return
            g2.clip(track)
            val span = width * 0.35f
            val x = -span + (width + span) * phase
            val accent = tone.accent
            val clear = Color(accent.red, accent.green, accent.blue, 0)
            g2.paint = GradientPaint(x, 0f, clear, x + span / 2, 0f, accent)
            g2.fill(RoundRectangle2D.Float(x, top, span / 2, height, height, height))
            g2.paint = GradientPaint(x + span / 2, 0f, accent, x + span, 0f, clear)
            g2.fill(RoundRectangle2D.Float(x + span / 2, top, span / 2, height, height, height))
        } finally {
            g2.dispose()
        }
    }
}
