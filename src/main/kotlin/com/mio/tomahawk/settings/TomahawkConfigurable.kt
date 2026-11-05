package com.mio.tomahawk.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.tomahawk.base.NoticeBox
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.run.AgentAutoSetup
import com.mio.tomahawk.run.AgentHost
import com.mio.tomahawk.run.AgentLaunch
import com.mio.tomahawk.run.AgentProvider
import com.mio.tomahawk.run.AgentVerifier
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.TomahawkSettings
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings > Tools > Tomahawk IA: o CLI de IA que dispara os comandos do Spec Kit, como o Interceptador do Iron
 * Dome. Configuração automática, onde está o CLI (máquina ou WSL), executável, modelo, chave do Cursor, Salvar e
 * Testar e o teste pelo terminal.
 */
class TomahawkConfigurable : Configurable {

    private val provider = ComboBox(AgentProvider.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.title} CLI (${it.executables.first()}) · integração ${it.integration}" }
        addActionListener { updateHints() }
    }
    private val host = ComboBox(AgentHost.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        addActionListener { updateHints() }
    }
    private val distro = ComboBox<String>().apply {
        isEditable = true
        addActionListener { updateHints() }
    }
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val found = small()
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(TomahawkSettings.DEFAULT_TIMEOUT_MINUTES, 1, 480, 5))
    private val runMode = ComboBox(RunMode.entries.toTypedArray()).apply { renderer = SimpleListCellRenderer.create("") { it.label } }
    private val extraArgs = JBTextField().apply { emptyText.text = "Ex.: --approve-mcps" }
    private val cursorKey = JBPasswordField()
    private val specify = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do specify"))
    }
    private val specifyFound = small()
    private val status = JBLabel()
    private val autoStatus = JBLabel()
    private val terminalStatus = JBLabel()
    private val autoFailure = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }
    private var savedKey = ""

    override fun getDisplayName() = "Tomahawk IA"

    override fun createComponent(): JComponent {
        val test = JButton("Salvar e Testar", AllIcons.Actions.Execute).apply { addActionListener { test() } }
        val auto = JButton("Configurar Automaticamente", TomahawkIcons.arsenal)
        auto.addActionListener { autoSetup(auto, test) }
        if (AgentLaunch.wslSupported) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val names = AgentLaunch.distributions()
                ApplicationManager.getApplication().invokeLater({
                    val chosen = selectedDistro() ?: TomahawkSettings.wslDistro
                    distro.removeAllItems()
                    names.forEach(distro::addItem)
                    distro.selectedItem = chosen ?: names.firstOrNull()
                }, ModalityState.any())
            }
        }
        val builder = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Configuração automática"))
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(auto)
                add(autoStatus)
            })
            .addComponent(small("Procura o Cursor CLI e o Claude Code instalados e testa cada um, com o modelo padrão, até um responder. Se nenhum responder, termine abaixo."))
            .addComponent(autoFailure)
            .addComponent(TitledSeparator("CLI de IA"))
            .addLabeledComponent("CLI:", provider)
        if (AgentLaunch.wslSupported) {
            builder.addLabeledComponent("Onde está o CLI:", host).addLabeledComponent("Distribuição do WSL:", distro)
        }
        reset()
        return builder
            .addLabeledComponent("Executável:", executable)
            .addComponentToRightColumn(found)
            .addLabeledComponent("Modelo:", model)
            .addComponentToRightColumn(small("Opcional, como no --model do CLI. Vazio usa o modelo padrão da sua conta."))
            .addLabeledComponent("Tempo máximo (min):", timeout)
            .addLabeledComponent("Argumentos extras:", extraArgs)
            .addComponentToRightColumn(small(
                "Missões na aba: o Cursor roda com -p --trust --force e o Claude Code com -p --permission-mode bypassPermissions, como no despacho de " +
                    "workflows do Spec Kit: a IA roda scripts e escreve arquivos sem perguntar. No terminal da IDE, você aprova cada passo. " +
                    "Textos (commit, PR, respostas do Rádio) e o teste rodam só leitura: Cursor em --mode ask, Claude Code em --permission-mode dontAsk.",
            ))
            .addLabeledComponent("Chave de API do Cursor:", cursorKey)
            .addComponentToRightColumn(small("Opcional: sem ela, vale o login do agent login. Fica no cofre de senhas da IDE."))
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(test)
                add(status)
            })
            .addComponent(TitledSeparator("Lançamento"))
            .addLabeledComponent("Onde lançar:", runMode)
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(JButton("Salvar e Testar no Terminal", AllIcons.Debugger.Console).apply {
                    isEnabled = LaunchTerminal.available() != null
                    toolTipText = if (isEnabled) "Abre o terminal do projeto aberto e roda o CLI com esta configuração" else "O plugin de terminal da IDE está desligado"
                    addActionListener { testTerminal(this) }
                })
                add(terminalStatus)
            })
            .addComponent(TitledSeparator("Spec Kit"))
            .addLabeledComponent("specify:", specify)
            .addComponentToRightColumn(specifyFound)
            .addComponentToRightColumn(small("Instale com: uv tool install specify-cli. Usado por Inicializar Spec Kit (specify init --here --integration …)."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(4) }
    }

    override fun isModified(): Boolean =
        provider.selectedItem != TomahawkSettings.provider ||
            host.selectedItem != TomahawkSettings.host ||
            (host.selectedItem == AgentHost.WSL && selectedDistro() != TomahawkSettings.wslDistro) ||
            executable.text.trim() != TomahawkSettings.executable.orEmpty() ||
            model.text.trim() != TomahawkSettings.model.orEmpty() ||
            timeout.value != TomahawkSettings.timeoutMinutes ||
            runMode.selectedItem != TomahawkSettings.runMode ||
            extraArgs.text.trim() != TomahawkSettings.extraArgs.orEmpty() ||
            specify.text.trim() != TomahawkSettings.specifyExecutable.orEmpty() ||
            String(cursorKey.password).trim() != savedKey

    override fun apply() {
        TomahawkSettings.provider = provider.selectedItem as AgentProvider
        TomahawkSettings.host = host.selectedItem as? AgentHost ?: AgentHost.LOCAL
        if (host.selectedItem == AgentHost.WSL) TomahawkSettings.wslDistro = selectedDistro()
        TomahawkSettings.executable = executable.text
        TomahawkSettings.model = model.text
        TomahawkSettings.timeoutMinutes = timeout.value as Int
        TomahawkSettings.runMode = runMode.selectedItem as RunMode
        TomahawkSettings.extraArgs = extraArgs.text
        TomahawkSettings.specifyExecutable = specify.text
        TomahawkSettings.cursorApiKey = String(cursorKey.password)
        savedKey = TomahawkSettings.cursorApiKey.orEmpty()
        updateHints()
    }

    override fun reset() {
        provider.selectedItem = TomahawkSettings.provider
        host.selectedItem = TomahawkSettings.host
        distro.selectedItem = TomahawkSettings.wslDistro
        executable.text = TomahawkSettings.executable.orEmpty()
        model.text = TomahawkSettings.model.orEmpty()
        timeout.value = TomahawkSettings.timeoutMinutes
        runMode.selectedItem = TomahawkSettings.runMode
        extraArgs.text = TomahawkSettings.extraArgs.orEmpty()
        specify.text = TomahawkSettings.specifyExecutable.orEmpty()
        savedKey = TomahawkSettings.cursorApiKey.orEmpty()
        cursorKey.text = savedKey
        updateHints()
        if (TomahawkSettings.verified) show("Testada em ${TomahawkSettings.verifiedAt}${TomahawkSettings.launch()?.where.orEmpty()}.", ok = true)
        else show("Ainda não testada: as missões não saem até o teste passar.", ok = false)
        TomahawkSettings.terminalVerifiedAt?.let { showTerminal("Terminal testado em $it.", ok = true) }
    }

    private fun selectedDistro(): String? = ((distro.selectedItem as? String) ?: distro.editor.item?.toString())?.trim()?.ifEmpty { null }

    private fun updateHints() {
        val chosen = provider.selectedItem as? AgentProvider ?: return
        val inWsl = host.selectedItem == AgentHost.WSL && AgentLaunch.wslSupported
        distro.isEnabled = inWsl
        if (inWsl) {
            found.text = selectedDistro()?.let { "<html>No WSL $it, o CLI é procurado pelo shell de login (~/.local/bin) ao testar; vazio usa ${chosen.executables.first()}.</html>" }
                ?: "Escolha a distribuição do WSL."
            found.foreground = if (selectedDistro() != null) UIUtil.getContextHelpForeground() else JBColor.RED
        } else {
            val path = TomahawkSettings.resolveExecutable(chosen, executable.text.trim().ifEmpty { null })
            found.text = "<html>" + (path?.let { "Encontrado: $it" } ?: "Não encontrado. ${chosen.installHint}") + "</html>"
            found.foreground = if (path != null) UIUtil.getContextHelpForeground() else JBColor.RED
        }
        cursorKey.isEnabled = chosen == AgentProvider.CURSOR
        val specifyPath = TomahawkSettings.resolveSpecify(specify.text.trim().ifEmpty { null })
        specifyFound.text = specifyPath?.let { "Encontrado: $it" } ?: "Não encontrado no PATH nem em ~/.local/bin."
        specifyFound.foreground = if (specifyPath != null) UIUtil.getContextHelpForeground() else JBColor.RED
    }

    private fun autoSetup(auto: JButton, test: JButton) {
        auto.isEnabled = false
        test.isEnabled = false
        autoFailure.isVisible = false
        AgentAutoSetup.run(
            onProgress = { message ->
                autoStatus.text = message
                autoStatus.foreground = UIUtil.getContextHelpForeground()
            },
            onDone = { result ->
                auto.isEnabled = true
                test.isEnabled = true
                reset()
                autoStatus.text = if (result.ok) result.message else "Não deu para configurar sozinho: termine abaixo."
                autoStatus.foreground = if (result.ok) JBColor(0x1A7F37, 0x3FB950) else JBColor.RED
                autoFailure.text = result.message
                autoFailure.isVisible = !result.ok
            },
        )
    }

    /** O terminal é de um projeto: vale o primeiro aberto. */
    private fun testTerminal(button: JButton) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        if (project == null) {
            showTerminal("Abra um projeto: o teste usa o terminal dele.", ok = false)
            return
        }
        apply()
        button.isEnabled = false
        showTerminal("Abrindo o terminal de ${project.name} e esperando a IA gravar OK…", ok = null)
        AgentVerifier.testTerminal(project) { outcome ->
            button.isEnabled = true
            showTerminal(outcome.message, outcome.ok)
        }
    }

    private fun test() {
        apply()
        show("Testando ${TomahawkSettings.provider.title}…", ok = null)
        AgentVerifier.test { outcome -> show(outcome.message, ok = outcome.ok) }
    }

    private fun show(text: String, ok: Boolean?) {
        status.text = "<html>$text</html>"
        status.foreground = color(ok)
    }

    private fun showTerminal(text: String, ok: Boolean?) {
        terminalStatus.text = "<html>$text</html>"
        terminalStatus.foreground = color(ok)
    }

    private fun color(ok: Boolean?) = when (ok) {
        true -> JBColor(0x1A7F37, 0x3FB950)
        false -> JBColor.RED
        null -> UIUtil.getContextHelpForeground()
    }

    private companion object {
        fun small(text: String = "") = JBLabel("<html>$text</html>").apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            foreground = UIUtil.getContextHelpForeground()
        }
    }
}
