package org.jetbrains.plugins.template.handlers

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import org.jetbrains.plugins.template.reference.FileLineReferenceProvider.Companion.REFERENCE_PATTERN
import org.jetbrains.plugins.template.util.FileReferenceUtil
import org.jetbrains.plugins.template.util.FileReferenceUtil.findFileInProject
import org.jetbrains.plugins.template.util.FileReferenceUtil.parseReference

/**
 * Handler for Command+click (Ctrl+click on Windows/Linux) navigation for file references
 * in the format @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
 */
class FileReferenceGotoDeclarationHandler : GotoDeclarationHandler {

    companion object {
        private val LOG = Logger.getInstance(FileReferenceGotoDeclarationHandler::class.java)
    }


    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null || editor == null) return null

        // Get the text of the current line
        val document = editor.document
        val lineNumber = document.getLineNumber(offset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))

        // Find all references in the line
        val matches = REFERENCE_PATTERN.findAll(lineText)

        for (match in matches) {
            val range = match.range

            // Check if the offset is within this reference
            val referenceStartOffset = lineStartOffset + range.first
            val referenceEndOffset = lineStartOffset + range.last + 1

            if (offset in referenceStartOffset until referenceEndOffset) {
                val reference = match.value
                LOG.debug("Found reference at cursor: $reference")

                // Parse the reference
                val parsedRef = parseReference(reference) ?: continue

                // Create a navigable element
                return arrayOf(
                    FileReferenceElement(
                        sourceElement,
                        sourceElement.project,
                        parsedRef.moduleName,
                        parsedRef.relativePath,
                        parsedRef.startLine,
                        parsedRef.endLine
                    )
                )
            }
        }

        return null
    }

    private class FileReferenceElement(
        private val sourceElement: PsiElement,
        private val project: Project,
        private val moduleName: String,
        private val filePath: String,
        private val startLine: Int,
        private val endLine: Int
    ) : FakePsiElement() {

        override fun getProject(): Project = project

        override fun getContainingFile(): com.intellij.psi.PsiFile =
            sourceElement.containingFile ?: throw IllegalStateException("No containing file found")

        override fun getParent(): PsiElement? = sourceElement

        override fun navigate(requestFocus: Boolean) {
            ApplicationManager.getApplication().invokeLater({
                navigateToFileAndSelectLines(project,moduleName, filePath, startLine, endLine)
            }, ModalityState.defaultModalityState())
        }

        override fun canNavigate(): Boolean = true

        override fun canNavigateToSource(): Boolean = true

        override fun isValid(): Boolean = true

        override fun getNavigationElement(): PsiElement = this

        private fun navigateToFileAndSelectLines(
            project: Project,
            moduleName: String,
            filePath: String,
            startLine: Int,
            endLine: Int
        ): Boolean {
            val logger = Logger.getInstance(FileReferenceGotoDeclarationHandler::class.java)
            logger.debug("Command+click navigating to file: $filePath, lines: $startLine-$endLine")

            // Try multiple resolution strategies
            var virtualFile = findFileInProject(project, moduleName,filePath )

            if (virtualFile == null) {
                logger.warn("Virtual file not found for reference: $filePath")
                return false
            }

            // Open the file in the editor
            val fileEditorManager = FileEditorManager.getInstance(project)
            val descriptor = OpenFileDescriptor(project, virtualFile, startLine - 1, 0) // Convert to 0-based
            val editor = fileEditorManager.openTextEditor(descriptor, true)
            if (editor == null) {
                logger.warn("Failed to open editor for file: $filePath")
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

            logger.debug("Successfully navigated to file and selected lines")
            return true
        }
    }
}
