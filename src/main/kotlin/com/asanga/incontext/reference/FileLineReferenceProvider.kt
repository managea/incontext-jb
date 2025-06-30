package com.asanga.incontext.reference

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.util.ProcessingContext
import com.asanga.incontext.util.FileReferenceUtil
import java.io.File

/**
 * Provides references for text patterns like: @project-name/path-from-project-root/file-name:L{from-line-number}:{to-line-number}
 */
class FileLineReferenceProvider : PsiReferenceProvider() {

    companion object {
        private val LOG = Logger.getInstance(FileLineReferenceProvider::class.java)

        // Regex to find references in the format @project/path/to/file.ext:L10:20
        val REFERENCE_PATTERN =  FileReferenceUtil.FILE_REFERENCE_PATTERN.toRegex()

        /**
         * Navigate to the specified file and select the specified lines
         */
        fun navigateToFileAndSelectLines(project: Project, filePath: String, startLine: Int, endLine: Int): Boolean {
            LOG.debug("Navigating to file: $filePath, lines: $startLine-$endLine")

            val basePath = project.basePath ?: return false
            val absolutePath = File(basePath, filePath).absolutePath

            LOG.debug("Absolute path: $absolutePath")

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

            LOG.debug("Successfully navigated to file and selected lines")
            return true
        }
    }

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val text = element.text
        LOG.debug("Checking for references in text: $text")

        val references = mutableListOf<PsiReference>()

        // Find all matches in the text
        REFERENCE_PATTERN.findAll(text).forEach { matchResult ->
            val range = matchResult.range
            val referenceText = matchResult.value

            LOG.debug("Found reference match: $referenceText at range $range")

            // Create a reference for each match
            val reference = FileLineReference(
                element,
                TextRange(range.first, range.last + 1),  // +1 because TextRange is exclusive of the end index
                element.project,
                referenceText
            )

            references.add(reference)
        }

        if (references.isEmpty()) {
            LOG.debug("No references found in text")
        } else {
            LOG.debug("Found ${references.size} references")
        }

        return references.toTypedArray()
    }
}
