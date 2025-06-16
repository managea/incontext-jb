package org.jetbrains.plugins.template.reference

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import java.io.File

/**
 * A custom reference for the format: @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
 */
class FileLineReference(
    element: PsiElement,
    textRange: TextRange,
    private val project: Project,
    private val referenceText: String
) : PsiReferenceBase<PsiElement>(element, textRange) {

    // Parse components from the reference
    private val parsedReference: ParsedReference? by lazy {
        parseReference(referenceText)
    }

    override fun resolve(): PsiElement? {
        // We're not resolving to a specific PsiElement, as we'll handle navigation in handleElementClick
        return null
    }

    override fun getVariants(): Array<Any> = emptyArray()

    /**
     * This method is called when the reference is clicked
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        // We don't support renaming through this reference
        return myElement
    }

    /**
     * Custom navigation when the reference is clicked
     */
    override fun bindToElement(element: PsiElement): PsiElement {
        // Use our custom navigation handler
        val parsedRef = parsedReference ?: return myElement

        if (parsedRef.projectName == project.name) {
            // Navigate to the file and select the lines
            FileLineReferenceProvider.navigateToFileAndSelectLines(
                project,
                parsedRef.relativePath,
                parsedRef.startLine,
                parsedRef.endLine
            )
        }

        return myElement
    }

    /**
     * Parse a reference in the format: @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
     */
    private fun parseReference(ref: String): ParsedReference? {
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
