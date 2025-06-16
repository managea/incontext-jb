package org.jetbrains.plugins.template.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection

/**
 * Action that copies a reference to the selected text in the format:
 * project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
 */
class CopyReferenceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val virtualFile = psiFile.virtualFile

        // Get the selected text range
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1 // 1-based
        val endLine = editor.document.getLineNumber(selectionModel.selectionEnd) + 1 // 1-based

        // Format the reference
        val reference = formatReference(project, virtualFile, startLine, endLine)

        // Copy to clipboard
        CopyPasteManager.getInstance().setContents(StringSelection(reference))
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)

        // Only enable the action if there's an editor with a selection
        e.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection()
    }

    private fun formatReference(project: Project, file: VirtualFile, startLine: Int, endLine: Int): String {
        val projectName = project.name
        val projectBasePath = project.basePath ?: ""
        val filePath = file.path

        // Get relative path from project root
        val relativePath = if (filePath.startsWith(projectBasePath)) {
            filePath.substring(projectBasePath.length + 1) // +1 to remove leading slash
        } else {
            filePath
        }

        return "$projectName/$relativePath:L$startLine-$endLine"
    }
}
