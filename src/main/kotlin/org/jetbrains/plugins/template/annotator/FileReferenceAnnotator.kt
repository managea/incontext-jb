package org.jetbrains.plugins.template.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import java.io.File

/**
 * Annotator that makes file references clickable.
 * Format: @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
 */
class FileReferenceAnnotator : Annotator {
    companion object {
        private val LOG = Logger.getInstance(FileReferenceAnnotator::class.java)
        private val REFERENCE_PATTERN = "@([\\w-]+/[\\w\\-./]+):L(\\d+)-(\\d+)".toRegex()
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val text = element.text
        if (text.isEmpty() || !text.contains('@')) {
            return
        }

        val matches = REFERENCE_PATTERN.findAll(text)

        for (match in matches) {
            val range = match.range
            val reference = match.value

            LOG.info("Annotating file reference: $reference")

            // Extract components from the reference
            val referenceParts = parseReference(reference) ?: continue

            // Calculate the text range within the current element
            val start = element.textRange.startOffset + range.first
            val end = element.textRange.startOffset + range.last + 1
            val textRange = TextRange(start, end)

            // Create an annotation with hyperlink
            holder.newAnnotation(HighlightSeverity.INFORMATION, "Navigate to file")
                .range(textRange)
                .textAttributes(DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE)
                .tooltip("Click to navigate to ${referenceParts.relativePath}, lines ${referenceParts.startLine}-${referenceParts.endLine}")
                .withFix(NavigateToFileQuickFix(
                    element.project,
                    referenceParts.relativePath,
                    referenceParts.startLine,
                    referenceParts.endLine
                ))
                .create()
        }
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
     * Data class to hold the parsed reference components
     */
    data class ParsedReference(
        val projectName: String,
        val relativePath: String,
        val startLine: Int,
        val endLine: Int
    )
}
