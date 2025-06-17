package org.jetbrains.plugins.template.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
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
        // Updated pattern to handle both formats:
        // 1. @workspace/project/path:L1-2
        // 2. @workspace/project/path:L1 (single line)
        // Also handle cases with full paths that might have double slashes
        private val REFERENCE_PATTERN = "@([\\w-]+/[\\w\\-./]+):L(\\d+)(?:-(\\d+))?".toRegex()
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

            LOG.debug("Annotating file reference: $reference in element type: ${element.javaClass.simpleName}, text: ${element.text.take(20)}...")

            // Extract components from the reference
            val referenceParts = parseReference(reference) ?: continue

            // Calculate the text range within the current element
            val start = element.textRange.startOffset + range.first
            val end = element.textRange.startOffset + range.last + 1
            val textRange = TextRange(start, end)

            LOG.debug("Reference $reference found at offsets $start-$end in element at ${element.containingFile.name}:${element.textRange.startOffset}")

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
            
            // Try to find the target file using different path resolution strategies
            var targetFile = findTargetFile(project, referenceParts)
            
            if (targetFile == null) {
                LOG.warn("Could not find target file for reference: ${referenceParts.projectName}/${referenceParts.relativePath}")
                return
            }
            
            // Get the source file (the file containing the reference)
            val sourceFile = element.containingFile.virtualFile
            val sourceOffset = element.textRange.startOffset
            val sourceEndOffset = element.textRange.endOffset

            LOG.debug("Adding reference from ${sourceFile.path} (offsets: $sourceOffset-$sourceEndOffset) to ${targetFile.path}:${referenceParts.startLine}-${referenceParts.endLine}")

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
            FileReferenceIndexer.getInstance(project).refreshFileCodeAnalysis(targetFile.path)

            LOG.debug("Added reference to index: ${sourceFile.path} -> ${targetFile.path}:${referenceParts.startLine}-${referenceParts.endLine}")
        } catch (e: Exception) {
            LOG.error("Failed to update reference index", e)
        }
    }
    
    /**
     * Try multiple strategies to find the target file
     */
    private fun findTargetFile(project: Project, referenceParts: ParsedReference): com.intellij.openapi.vfs.VirtualFile? {
        val basePath = project.basePath ?: return null
        LOG.debug("Finding target file for reference: ${referenceParts.projectName}/${referenceParts.relativePath}")
        LOG.debug("Current project base path: $basePath")
        
        // Strategy 1: Direct path from project root
        val directPath = "$basePath/${referenceParts.relativePath}"
        LOG.debug("Trying direct path: $directPath")
        var targetFile = LocalFileSystem.getInstance().findFileByPath(directPath)
        if (targetFile != null) {
            LOG.debug("Found file using direct path")
            return targetFile
        }
        
        // Strategy 2: Try with project name in path
        val pathWithProject = "$basePath/${referenceParts.projectName}/${referenceParts.relativePath}"
        LOG.debug("Trying with project name: $pathWithProject")
        targetFile = LocalFileSystem.getInstance().findFileByPath(pathWithProject)
        if (targetFile != null) {
            LOG.debug("Found file using project name path")
            return targetFile
        }
        
        // Strategy 3: Check if we're in a multi-project workspace
        // Look for sibling directories at the workspace level
        val workspacePath = basePath.substringBeforeLast("/")
        val projectNameDir = File("$workspacePath/${referenceParts.projectName}")
        
        if (projectNameDir.exists() && projectNameDir.isDirectory) {
            // The project directory exists as a sibling to the current project
            val siblingProjectPath = "$workspacePath/${referenceParts.projectName}/${referenceParts.relativePath}"
            LOG.debug("Trying sibling project path: $siblingProjectPath")
            targetFile = LocalFileSystem.getInstance().findFileByPath(siblingProjectPath)
            if (targetFile != null) {
                LOG.debug("Found file using sibling project path")
                return targetFile
            }
        }
        
        // Strategy 4: Look for common project directory structures
        LOG.debug("Workspace path: $workspacePath")
        
        // Try common project directory names
        val possibleProjectDirs = listOf("src", "app", "lib", "packages", "projects", "modules", "backend", "frontend")
        
        // First try with the project name as a directory
        val projectNamePath = "$workspacePath/${referenceParts.projectName}/${referenceParts.relativePath}"
        LOG.debug("Trying project name path: $projectNamePath")
        targetFile = LocalFileSystem.getInstance().findFileByPath(projectNamePath)
        if (targetFile != null) {
            LOG.debug("Found file using project name path")
            return targetFile
        }
        
        // Then try with common project directories
        for (projectDir in possibleProjectDirs) {
            // Try the project name as a parent directory
            val path1 = "$workspacePath/${referenceParts.projectName}/$projectDir/${referenceParts.relativePath}"
            LOG.debug("Trying path with project name as parent: $path1")
            targetFile = LocalFileSystem.getInstance().findFileByPath(path1)
            if (targetFile != null) {
                LOG.debug("Found file using project name as parent")
                return targetFile
            }
            
            // Try the project name as a child directory
            val path2 = "$workspacePath/$projectDir/${referenceParts.projectName}/${referenceParts.relativePath}"
            LOG.debug("Trying path with project name as child: $path2")
            targetFile = LocalFileSystem.getInstance().findFileByPath(path2)
            if (targetFile != null) {
                LOG.debug("Found file using project name as child")
                return targetFile
            }
        }
        
        // Strategy 5: Search in parent directories
        val parentPath = basePath.substringBeforeLast("/")
        val pathInParent = "$parentPath/${referenceParts.relativePath}"
        LOG.debug("Trying parent path: $pathInParent")
        targetFile = LocalFileSystem.getInstance().findFileByPath(pathInParent)
        if (targetFile != null) {
            LOG.debug("Found file using parent path")
            return targetFile
        }
        
        // Strategy 6: Use FileReferenceIndexer to find the file
        LOG.debug("Trying FileReferenceIndexer")
        val indexer = FileReferenceIndexer.getInstance(project)
        return indexer.findFileInProject(referenceParts.relativePath)
    }

    /**
     * Parse a reference in the format: @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
     */
    private fun parseReference(ref: String): ParsedReference? {
        try {
            // Remove the @ prefix if it exists
            val refWithoutPrefix = if (ref.startsWith("@")) ref.substring(1) else ref

            // Match the line number part (L{from-line-number}-{to-line-number})
            val lineNumbersPattern = ":L(\\d+)(?:-(\\d+))?".toRegex()
            val lineNumbersMatch = lineNumbersPattern.find(refWithoutPrefix) ?: return null

            // Extract start and end line numbers
            val startLine = lineNumbersMatch.groupValues[1].toIntOrNull() ?: return null
            val endLine = lineNumbersMatch.groupValues[2]?.toIntOrNull() ?: startLine

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
