package org.jetbrains.plugins.template.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import java.io.File

/**
 * Quick fix action that navigates to a file and selects specific lines
 */
class NavigateToFileQuickFix(
    private val project: Project,
    private val filePath: String,
    private val startLine: Int,
    private val endLine: Int
) : IntentionAction {

    companion object {
        private val LOG = Logger.getInstance(NavigateToFileQuickFix::class.java)
    }

    override fun getText(): String = "Navigate to $filePath:L$startLine-$endLine"

    override fun getFamilyName(): String = "Navigate to File Reference"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        navigateToFileAndSelectLines()
    }

    override fun startInWriteAction(): Boolean = false

    /**
     * Navigate to the specified file and select the specified lines
     */
    private fun navigateToFileAndSelectLines(): Boolean {
        LOG.info("Navigating to file: $filePath, lines: $startLine-$endLine")

        val basePath = project.basePath ?: return false
        val absolutePath = File(basePath, filePath).absolutePath

        LOG.info("Absolute path: $absolutePath")

        // Find the virtual file
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        if (virtualFile == null) {
            LOG.warn("Virtual file not found: $absolutePath")
            return false
        }

        // Open the file in the editor
        val fileEditorManager = FileEditorManager.getInstance(project)
        val descriptor = OpenFileDescriptor(project, virtualFile, startLine - 1, 0) // Convert to 0-based
        val editor = fileEditorManager.openTextEditor(descriptor, true)
        if (editor == null) {
            LOG.warn("Failed to open editor for file: $absolutePath")
            return false
        }

        // Calculate document offsets for the selection
        val document = editor.document
        val startOffset = document.getLineStartOffset(startLine - 1) // Convert to 0-based
        val endLineOffset = if (endLine <= document.lineCount) {
            val endLineInDocument = endLine - 1 // Convert to 0-based
            document.getLineEndOffset(endLineInDocument)
        } else {
            document.textLength
        }

        // Set the selection
        editor.selectionModel.setSelection(startOffset, endLineOffset)

        // Scroll to make the selection visible
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

        LOG.info("Successfully navigated to file and selected lines")
        return true
    }
}
