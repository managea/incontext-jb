package com.asanga.incontext.reference

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.asanga.incontext.util.FileReferenceUtil
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
    private val parsedReference: FileReferenceUtil.ParsedReference? by lazy {
        FileReferenceUtil.parseReference(referenceText)
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

        if (parsedRef.moduleName == project.name) {
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
}
