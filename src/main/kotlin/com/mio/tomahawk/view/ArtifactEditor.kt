package com.mio.tomahawk.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Os Markdown do Spec Kit (`specs/`, a constituição) e do Tomahawk (`tomahawk/`) abrem primeiro na
 * Visualização, formatada; a aba de texto continua ao lado para editar.
 */
class ArtifactEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val base = project.basePath?.replace('\\', '/') ?: return false
        val path = file.path.replace('\\', '/')
        if (!path.startsWith("$base/")) return false
        return ArtifactKind.handles(path.removePrefix("$base/"))
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ArtifactFileEditor(project, file)

    override fun getEditorTypeId() = TYPE_ID

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    companion object {
        const val TYPE_ID = "tomahawk-artifact"
    }
}

class ArtifactFileEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val view = ArtifactView(project, this)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val panel = JPanel(BorderLayout())

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "Tomahawk.ArtifactEditor",
            DefaultActionGroup(
                object : DumbAwareAction("Atualizar", "Relê o arquivo", AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) = view.reload()
                },
                object : DumbAwareAction("Editar Texto", "Abre a aba de texto deste arquivo", AllIcons.Actions.Edit) {
                    override fun actionPerformed(e: AnActionEvent) {
                        FileEditorManager.getInstance(project).setSelectedEditor(file, TEXT_EDITOR_ID)
                    }
                },
            ),
            true,
        )
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(view, BorderLayout.CENTER)
        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ view.reload() }, RELOAD_DELAY_MS)
            }
        }, this)
        view.show(file.toNioPath())
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = view

    override fun getName() = "Visualização"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified() = false

    override fun isValid() = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit

    private companion object {
        const val RELOAD_DELAY_MS = 300
        const val TEXT_EDITOR_ID = "text-editor"
    }
}
