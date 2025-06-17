package org.jetbrains.plugins.template.handlers

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import java.io.File

/**
 * Handler for Command+click (Ctrl+click on Windows/Linux) navigation for file references
 * in the format @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
 */
class FileReferenceGotoDeclarationHandler : GotoDeclarationHandler {

    companion object {
        private val LOG = Logger.getInstance(FileReferenceGotoDeclarationHandler::class.java)
        private val REFERENCE_PATTERN = "@([\\w-]+/[\\w\\-./]+):L(\\d+)-(\\d+)".toRegex()

        /**
         * Data class to hold the parsed reference components
         */
        data class ParsedReference(
            val projectName: String,
            val relativePath: String,
            val startLine: Int,
            val endLine: Int
        )

        /**
         * Parse a reference in the format: @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
         */
        fun parseReference(ref: String): ParsedReference? {
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
    }

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
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
                return arrayOf(FileReferenceElement(
                    sourceElement,
                    sourceElement.project,
                    parsedRef.relativePath,
                    parsedRef.startLine,
                    parsedRef.endLine,
                    parsedRef.projectName // Pass the project name as an additional parameter
                ))
            }
        }

        return null
    }

    private fun parseReference(ref: String): Companion.ParsedReference? {
        return Companion.parseReference(ref)
    }

    /**
     * A custom PsiElement that handles navigation when clicked (not on hover)
     */
    private class FileReferenceElement(
        private val sourceElement: PsiElement,
        private val project: Project,
        private val filePath: String,
        private val startLine: Int,
        private val endLine: Int,
        private val projectName: String // Project name from the reference
    ) : FakePsiElement() {

        override fun getProject(): Project = project

        override fun getContainingFile(): com.intellij.psi.PsiFile =
            sourceElement.containingFile ?: throw IllegalStateException("No containing file found")

        override fun getParent(): PsiElement? = sourceElement

        override fun navigate(requestFocus: Boolean) {
            ApplicationManager.getApplication().invokeLater({
                navigateToFileAndSelectLines(project, filePath, startLine, endLine)
            }, ModalityState.defaultModalityState())
        }

        override fun canNavigate(): Boolean = true

        override fun canNavigateToSource(): Boolean = true

        override fun isValid(): Boolean = true

        override fun getNavigationElement(): PsiElement = this

        private fun navigateToFileAndSelectLines(project: Project, filePath: String, startLine: Int, endLine: Int): Boolean {
            val logger = Logger.getInstance(FileReferenceGotoDeclarationHandler::class.java)
            logger.debug("Command+click navigating to file: $filePath, lines: $startLine-$endLine")

            val basePath = project.basePath ?: return false

            // Parse the file path to extract project name and relative path
            val parsedRef = FileReferenceGotoDeclarationHandler.parseReference("@$projectName/$filePath:L$startLine-$endLine") ?: return false

            // Try multiple resolution strategies
            var virtualFile = findTargetFile(project, parsedRef)

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

        /**
         * Find the target file using multiple resolution strategies
         */
        private fun findTargetFile(project: Project, parsedRef: Companion.ParsedReference): com.intellij.openapi.vfs.VirtualFile? {
            val logger = Logger.getInstance(FileReferenceGotoDeclarationHandler::class.java)
            val basePath = project.basePath ?: return null

            // Strategy 1: Try direct path relative to project root
            val directPath = "$basePath/${parsedRef.relativePath}"
            logger.debug("Trying direct path: $directPath")
            var targetFile = LocalFileSystem.getInstance().findFileByPath(directPath)
            if (targetFile != null) {
                logger.debug("Found file using direct path")
                return targetFile
            }

            // Strategy 2: Try with project name in path
            val pathWithProject = "$basePath/${parsedRef.projectName}/${parsedRef.relativePath}"
            logger.debug("Trying path with project name: $pathWithProject")
            targetFile = LocalFileSystem.getInstance().findFileByPath(pathWithProject)
            if (targetFile != null) {
                logger.debug("Found file using path with project name")
                return targetFile
            }

            // Strategy 3: Look for sibling project directories
            val parentPath = File(basePath).parent ?: return null
            val siblingProjectPath = "$parentPath/${parsedRef.projectName}/${parsedRef.relativePath}"
            logger.debug("Trying sibling project path: $siblingProjectPath")
            targetFile = LocalFileSystem.getInstance().findFileByPath(siblingProjectPath)
            if (targetFile != null) {
                logger.debug("Found file in sibling project directory")
                return targetFile
            }

            // Strategy 4: Try common project directory structures
            val commonDirs = listOf("src", "app", "lib", "packages", "projects", "modules")
            for (dir in commonDirs) {
                val pathWithCommonDir = "$basePath/$dir/${parsedRef.relativePath}"
                logger.debug("Trying common directory structure: $pathWithCommonDir")
                targetFile = LocalFileSystem.getInstance().findFileByPath(pathWithCommonDir)
                if (targetFile != null) {
                    logger.debug("Found file using common directory structure: $dir")
                    return targetFile
                }
            }

            // Strategy 5: Try parent directories
            var currentDir = File(basePath)
            while (currentDir.parent != null) {
                val parentDirPath = currentDir.parent
                val pathWithParentDir = "$parentDirPath/${parsedRef.projectName}/${parsedRef.relativePath}"
                logger.debug("Trying parent directory: $pathWithParentDir")
                targetFile = LocalFileSystem.getInstance().findFileByPath(pathWithParentDir)
                if (targetFile != null) {
                    logger.debug("Found file in parent directory structure")
                    return targetFile
                }
                currentDir = File(parentDirPath)
            }

            // No file found with any strategy
            return null
        }
    }
}
