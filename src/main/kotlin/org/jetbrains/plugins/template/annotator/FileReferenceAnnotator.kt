package org.jetbrains.plugins.template.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.template.navigation.FileReferenceIndex
import org.jetbrains.plugins.template.navigation.FileReferenceIndexer
import org.jetbrains.plugins.template.navigation.ReferenceNavigationHistory
import org.jetbrains.plugins.template.util.FileReferenceUtil
import org.jetbrains.plugins.template.util.FileReferenceUtil.FILE_REFERENCE_PATTERN

/**
 * Annotator for file references in the format: @module-name/path-from-module-root/file-name:L{from-line-number}-{to-line-number}
 */
class FileReferenceAnnotator : Annotator {
    companion object {
        private val LOG = Logger.getInstance(FileReferenceAnnotator::class.java)
    }

    /**
     * Update the FileReferenceIndex with this reference
     */
    private fun updateReferenceIndex(
        project: Project,
        referenceParts: FileReferenceUtil.ParsedReference,
        element: PsiElement
    ) {
        try {
            // Find the target file
            val targetFile = findTargetFile(project, referenceParts)

            if (targetFile != null) {
                // Get the source file
                val sourceFile = element.containingFile.virtualFile

                // Calculate the line number where this reference is defined
                val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
                val selfLineNumber = document?.getLineNumber(element.textRange.startOffset)?.plus(1)
                    ?: 0 // Convert to 1-based line number

                // Add the reference to the index
                val referenceIndex = FileReferenceIndex.getInstance(project)
                referenceIndex.addReference(
                    targetFile,
                    referenceParts.startLine,
                    referenceParts.endLine,
                    sourceFile,
                    element.textRange.startOffset,
                    element.textRange.endOffset,
                    selfLineNumber
                )

                LOG.debug("Added reference from ${sourceFile.path} to ${targetFile.path}:${referenceParts.startLine}-${referenceParts.endLine}")
            } else {
                LOG.warn("Target file not found for reference: ${referenceParts.moduleName}/${referenceParts.relativePath}")
            }
        } catch (e: Exception) {
            LOG.error("Error updating reference index", e)
        }
    }

    /**
     * Try multiple strategies to find the target file
     */
    private fun findTargetFile(
        project: Project,
        referenceParts: FileReferenceUtil.ParsedReference
    ): com.intellij.openapi.vfs.VirtualFile? {
        val basePath = project.basePath ?: return null
        LOG.debug("Finding target file for reference: ${referenceParts.moduleName}/${referenceParts.relativePath}")
        LOG.debug("Current project base path: $basePath")

        return FileReferenceUtil.findFileInProject(project, referenceParts.moduleName, referenceParts.relativePath)
    }

    /**
     * Check if the text contains a file reference and annotate it
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Only process string literals
        if (element.text == null || !element.text.contains("@") || !element.text.contains(":L")){
            return
        }

        val project = element.project
        val text = element.text

        // Look for file references in the text
        val referencePattern = FILE_REFERENCE_PATTERN.toRegex()
        val matches = referencePattern.findAll(text)

        for (match in matches) {
            val reference = match.value
            val range = match.range
            LOG.info("Found reference in text: $reference")

            // Parse the reference
            val referenceParts = FileReferenceUtil.parseReference(reference)
            if (referenceParts == null) {
                LOG.warn("Failed to parse reference: $reference")
                continue
            }

            // Calculate the text range within the current element
            val start = element.textRange.startOffset + range.first
            val end = element.textRange.startOffset + range.last + 1
            val textRange = TextRange(start, end)

            // Add navigation action
            // Create an annotation with hyperlink for Command+click navigation
            holder.newAnnotation(HighlightSeverity.INFORMATION, "Navigate to file")
                .range(textRange)
                .textAttributes(DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE)
                .tooltip("Click to navigate to ${referenceParts.relativePath}, lines ${referenceParts.startLine}-${referenceParts.endLine}")
                .newFix(CreateGotoDeclarationHandler(
                    element.project,
                    referenceParts.moduleName,
                    referenceParts.relativePath,
                    referenceParts.startLine,
                    referenceParts.endLine
                ))
                .registerFix()
                .create()

            // Update the reference index for navigation
            updateReferenceIndex(project, referenceParts, element)
        }
    }
}
