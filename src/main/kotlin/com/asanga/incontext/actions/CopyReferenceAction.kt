package com.asanga.incontext.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection
import java.io.File

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
        val project = e.getProject() ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val virtualFile = psiFile.virtualFile

        // Get the selected text range
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1 // 1-based
        val endLine = editor.document.getLineNumber(selectionModel.selectionEnd) + 1 // 1-based

        // Get the module name for the file
        val moduleName = getModuleNameForFile(project, virtualFile)

        // Format the reference
        val reference = formatReference(moduleName, virtualFile, startLine, endLine)

        // Copy to clipboard
        CopyPasteManager.getInstance().setContents(StringSelection(reference))
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)

        // Only enable the action if there's an editor with a selection
        e.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection()
    }

    private fun formatReference(moduleName: String, file: VirtualFile, startLine: Int, endLine: Int): String {
        val filePath = file.path
        
        // Try to find the module name in the path to determine the relative path
        val moduleNameIndex = filePath.indexOf("/$moduleName/")
        
        val relativePath = if (moduleNameIndex >= 0) {
            // If module name is found in the path, use everything after it
            filePath.substring(moduleNameIndex + moduleName.length + 2) // +2 for the two slashes
        } else {
            // If module name is not found in the path, just use the file name
            file.name
        }

        return "@$moduleName/$relativePath:L$startLine${if (endLine > startLine) "-$endLine" else ""}"
    }
    
    /**
     * Get the module name for a file using IntelliJ API
     */
    private fun getModuleNameForFile(project: Project, file: VirtualFile): String {
        // Use ProjectFileIndex to get the module for the file
        val projectFileIndex = ProjectFileIndex.getInstance(project)
        val module = projectFileIndex.getModuleForFile(file)
        
        // If we found a module, use its name
        if (module != null) {
            return module.name
        }
        
        // If no module found, fall back to project name
        return project.name
    }
}
