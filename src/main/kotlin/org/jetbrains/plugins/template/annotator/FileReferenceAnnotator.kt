package org.jetbrains.plugins.template.annotator

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.plugins.template.navigation.FileReferenceIndex
import org.jetbrains.plugins.template.navigation.FileReferenceIndexer
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

            LOG.info("Annotating file reference: $reference in element type: ${element.javaClass.simpleName}, text: ${element.text.take(20)}...")

            // Extract components from the reference
            val referenceParts = parseReference(reference) ?: continue

            // Calculate the text range within the current element
            val start = element.textRange.startOffset + range.first
            val end = element.textRange.startOffset + range.last + 1
            val textRange = TextRange(start, end)
            
            LOG.info("Reference $reference found at offsets $start-$end in element at ${element.containingFile.name}:${element.textRange.startOffset}")

            // Update the FileReferenceIndex with this reference
            updateReferenceIndex(element.project, referenceParts, element)

            // Create an annotation with hyperlink for Command+click navigation
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
                .newFix(CreateGotoDeclarationHandler(
                    element.project,
                    referenceParts.relativePath,
                    referenceParts.startLine,
                    referenceParts.endLine
                ))
                .registerFix()
                .create()
        }
    }

    /**
     * Update the FileReferenceIndex with this reference
     */
    private fun updateReferenceIndex(project: Project, referenceParts: ParsedReference, element: PsiElement) {
        try {
            // Find the target file
            val basePath = project.basePath ?: return
            val targetFilePath = "$basePath/${referenceParts.relativePath}"
            val targetFile = LocalFileSystem.getInstance().findFileByPath(targetFilePath)
            
            if (targetFile == null) {
                LOG.warn("Could not find target file: $targetFilePath")
                return
            }
            
            // Get the source file (the file containing the reference)
            val sourceFile = element.containingFile.virtualFile
            val sourceOffset = element.textRange.startOffset
            val sourceEndOffset = element.textRange.endOffset
            
            LOG.info("Adding reference from ${sourceFile.path} (offsets: $sourceOffset-$sourceEndOffset) to ${targetFile.path}:${referenceParts.startLine}-${referenceParts.endLine}")
            
            // Add the reference to the index
            val referenceIndex = FileReferenceIndex.getInstance(project)
            referenceIndex.addReference(
                targetFile,
                referenceParts.startLine,
                referenceParts.endLine,
                sourceFile,
                sourceOffset,
                sourceEndOffset
            )
            
            // Refresh code analysis to update gutter icons
            FileReferenceIndexer.getInstance(project).refreshFileCodeAnalysis(targetFilePath)
            
            LOG.info("Added reference to index: ${sourceFile.path} -> ${targetFile.path}:${referenceParts.startLine}-${referenceParts.endLine}")
        } catch (e: Exception) {
            LOG.error("Failed to update reference index", e)
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
