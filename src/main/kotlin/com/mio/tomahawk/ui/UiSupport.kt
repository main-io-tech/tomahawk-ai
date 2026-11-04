package com.mio.tomahawk.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.mio.tomahawk.view.ArtifactEditorProvider
import com.mio.tomahawk.view.ArtifactFileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.run.RunStatus
import com.mio.tomahawk.run.Verdict
import com.mio.tomahawk.speckit.StepState
import java.nio.file.Path

internal val StepState.tone: Tone
    get() = when (this) {
        StepState.DONE -> Tone.GREEN
        StepState.PARTIAL -> Tone.BLUE
        StepState.READY -> Tone.ORANGE
        StepState.RECOMMENDED -> Tone.YELLOW
        StepState.OPTIONAL, StepState.BLOCKED -> Tone.GRAY
        StepState.UNAVAILABLE -> Tone.RED
    }

internal val RunStatus.tone: Tone
    get() = when (this) {
        RunStatus.OK -> Tone.GREEN
        RunStatus.FAILED -> Tone.RED
        RunStatus.CANCELLED -> Tone.GRAY
        RunStatus.TERMINAL -> Tone.PURPLE
    }

internal val Verdict.tone: Tone
    get() = when (this) {
        Verdict.CONVERGED, Verdict.CLEAN -> Tone.GREEN
        Verdict.TASKS_APPENDED -> Tone.YELLOW
        Verdict.ISSUES -> Tone.RED
    }

/**
 * Abre [path]: sem [line], os artefatos do Spec Kit abrem na Visualização formatada; com [line] (começando em 0),
 * o texto abre na linha, para conferir ou editar o trecho.
 */
internal fun openFile(project: Project, path: Path, line: Int = 0) {
    if (line <= 0) {
        openArtifact(project, path)
        return
    }
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
    if (file.isDirectory) return
    OpenFileDescriptor(project, file, line, 0).navigate(true)
}

/** Abre [path] e escolhe a Visualização, se o Tomahawk a oferece para o arquivo. */
fun openArtifact(project: Project, path: Path) {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
    if (file.isDirectory) return
    val manager = FileEditorManager.getInstance(project)
    manager.openFile(file, true)
    if (manager.getEditors(file).any { it is ArtifactFileEditor }) manager.setSelectedEditor(file, ArtifactEditorProvider.TYPE_ID)
}

internal fun elapsed(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
else "%d:%02d".format(seconds / 60, seconds % 60)

internal fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
