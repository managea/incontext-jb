package org.jetbrains.plugins.template.markers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.template.navigation.FileReferenceIndex
import org.jetbrains.plugins.template.util.FileReferenceUtil
import com.intellij.icons.AllIcons
import java.io.File

/**
 * Line marker provider for file references in the format:
 * @module-name/path-from-module-root/file-name:L{from-line-number}-{to-line-number}
 */
class FileReferenceLineMarkerProvider : LineMarkerProvider {
    companion object {
        private val LOG = Logger.getInstance(FileReferenceLineMarkerProvider::class.java)
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process string literals
        if (element.text == null || !element.text.contains("@") || !element.text.contains(":L")) {
            return null
        }

        val text = element.text
        val referencePattern =  FileReferenceUtil.FILE_REFERENCE_PATTERN.toRegex()
        val match = referencePattern.find(text) ?: return null
        val reference = match.value

        // Parse the reference
        val parsedRef = FileReferenceUtil.parseReference(reference) ?: return null

        // Create a line marker with navigation action
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.General.MoreTabs, // Use a suitable icon
            { "Navigate to ${parsedRef.moduleName}/${parsedRef.relativePath}:L${parsedRef.startLine}-${parsedRef.endLine}" },
            { e, elt ->
                navigateToFileAndSelectLines(
                    elt.project,
                    parsedRef.relativePath,
                    parsedRef.startLine,
                    parsedRef.endLine
                )
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Navigate to file reference" }
        )
    }

    /**
     * Navigate to the specified file and select the specified lines
     */
    private fun navigateToFileAndSelectLines(project: Project, filePath: String, startLine: Int, endLine: Int): Boolean {
        LOG.debug("Navigating to file: $filePath:L$startLine-$endLine")

        try {
            // Find the file
            val basePath = project.basePath ?: return false
            val fullPath = "$basePath/$filePath"
            val file = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return false

            // Open the file in the editor
            ApplicationManager.getApplication().invokeLater({
                try {
                    val fileEditorManager = FileEditorManager.getInstance(project)
                    val descriptor = OpenFileDescriptor(project, file, startLine - 1, 0)
                    val editor = fileEditorManager.openTextEditor(descriptor, true)

                    // Select the lines
                    if (editor != null) {
                        val document = editor.document
                        val startOffset = document.getLineStartOffset(startLine - 1)
                        val endOffset = if (endLine < document.lineCount) document.getLineEndOffset(endLine - 1) else document.textLength

                        editor.selectionModel.setSelection(startOffset, endOffset)

                        // Scroll to the selection
                        editor.scrollingModel.scrollTo(
                            LogicalPosition(startLine - 1, 0),
                            ScrollType.CENTER
                        )
                    }

                    LOG.debug("Successfully navigated to file and selected lines")
                } catch (e: Exception) {
                    LOG.error("Error navigating to file", e)
                }
            }, ModalityState.NON_MODAL)

            return true
        } catch (e: Exception) {
            LOG.error("Error navigating to file", e)
            return false
        }
    }
}
