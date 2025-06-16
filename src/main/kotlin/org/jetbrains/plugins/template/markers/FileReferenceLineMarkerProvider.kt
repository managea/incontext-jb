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
import com.intellij.psi.util.PsiUtilBase
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.psi.PsiFile

/**
 * Line marker provider for file references in the format:
 * @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
 */
class FileReferenceLineMarkerProvider : LineMarkerProvider {
    companion object {
        private val LOG = Logger.getInstance(FileReferenceLineMarkerProvider::class.java)
        private val REFERENCE_PATTERN = "@([\\w-]+/[\\w\\-./]+):L(\\d+)-(\\d+)".toRegex()
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val text = element.text ?: return null
        val matches = REFERENCE_PATTERN.findAll(text)

        for (match in matches) {
            val range = match.range
            val reference = match.value

            LOG.info("Found file reference: $reference")

            // Extract components from the reference
            val referenceParts = parseReference(reference) ?: continue

            // Create a line marker info for this reference
            return LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.General.MoreTabs, // Use a suitable icon
                { "Navigate to ${referenceParts.relativePath}, lines ${referenceParts.startLine}-${referenceParts.endLine}" },
                { e, _ ->
                    navigateToFileAndSelectLines(
                        element.project,
                        referenceParts.relativePath,
                        referenceParts.startLine,
                        referenceParts.endLine
                    )
                },
                GutterIconRenderer.Alignment.LEFT
            )
        }

        return null
    }

    /**
     * Parse a reference in the format: @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
     */
    private fun parseReference(ref: String): ParsedReference? {
        try {
            // Remove the @ prefix if it exists
            val refWithoutPrefix = if (ref.startsWith("@")) ref.substring(1) else ref

            // Match the line number part (L{from-line-number}-{to-line-number})
            val lineNumbersPattern = ":L(\\d+)-(\\d+)$".toRegex()
            val lineNumbersMatch = lineNumbersPattern.find(refWithoutPrefix) ?: return null

            // Extract start and end line numbers
            val startLine = lineNumbersMatch.groupValues[1].toIntOrNull() ?: return null
            val endLine = lineNumbersMatch.groupValues[2].toIntOrNull() ?: return null

            // Extract the path part (everything before the line numbers)
            val pathPart = refWithoutPrefix.substring(0, lineNumbersMatch.range.first)

            // Check if the path starts with a project name
            val pathComponents = pathPart.split("/", limit = 2)
            val projectName = pathComponents[0]

            // Get the relative path within the project
            val relativePath = if (pathComponents.size > 1) pathComponents[1] else ""

            return ParsedReference(projectName, relativePath, startLine, endLine)
        } catch (e: Exception) {
            LOG.warn("Failed to parse reference: $ref", e)
            return null
        }
    }

    /**
     * Navigate to the specified file and select the specified lines
     */
    private fun navigateToFileAndSelectLines(project: Project, filePath: String, startLine: Int, endLine: Int): Boolean {
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

    /**
     * Data class to hold the parsed reference components
     */
    data class ParsedReference(
        val projectName: String,
        val relativePath: String,
        val startLine: Int,
        val endLine: Int
    )
}
