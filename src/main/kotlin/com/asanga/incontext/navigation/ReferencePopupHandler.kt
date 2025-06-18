package com.asanga.incontext.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Handles showing a popup with all references to a code section
 */
object ReferencePopupHandler {
    
    private val LOG = Logger.getInstance(ReferencePopupHandler::class.java)
    
    /**
     * Show a popup with all references to a specific location in a file
     */
    fun showReferencesPopup(project: Project, editor: Editor, file: VirtualFile, lineNumber: Int) {
        val referenceIndex = FileReferenceIndex.getInstance(project)
        val references = referenceIndex.findReferencesToLocation(file, lineNumber)
        
        if (references.isEmpty()) {
            LOG.debug("No references found for ${file.path}:$lineNumber")
            return
        }
        
        LOG.debug("Found ${references.size} references to ${file.path}:$lineNumber")
        
        // Deduplicate references by source file and line numbers
        val uniqueReferences = references.distinctBy { 
            "${it.sourceFile.path}:${it.selfLineNumber}" 
        }
        
        LOG.debug("After deduplication: ${uniqueReferences.size} unique references to ${file.path}:$lineNumber")
        
        // Create a list of reference items for the popup
        val items = uniqueReferences.mapIndexed { index, reference ->
            val sourceFile = reference.sourceFile
            val document = PsiDocumentManager.getInstance(project).getDocument(
                PsiManager.getInstance(project).findFile(sourceFile) ?: return@mapIndexed null
            ) ?: return@mapIndexed null
            
            // Use the selfLineNumber for display if available, otherwise calculate from offset
            val displayLine = if (reference.selfLineNumber > 0) {
                reference.selfLineNumber
            } else {
                document.getLineNumber(reference.startOffset) + 1
            }
            
            // Include line range information if available
            val displayText = if (reference.startLine > 0 && reference.endLine > 0 && reference.endLine > reference.startLine) {
                "Reference ${index + 1}: ${sourceFile.name}:L$displayLine (points to L${reference.startLine}-${reference.endLine})"
            } else {
                "Reference ${index + 1}: ${sourceFile.name}:L$displayLine"
            }
            
            ReferenceItem(
                displayText,
                reference
            )
        }.filterNotNull()
        
        if (items.isEmpty()) {
            return
        }
        
        // Create and show the popup
        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<ReferenceItem>("References to ${file.name}:$lineNumber", items) {
                override fun onChosen(selectedValue: ReferenceItem, finalChoice: Boolean): PopupStep<*>? {
                    if (finalChoice) {
                        // Navigate to the selected reference
                        val reference = selectedValue.reference
                        navigateToReference(project, reference)
                    }
                    return FINAL_CHOICE
                }
                
                override fun getTextFor(value: ReferenceItem): String {
                    return value.displayText
                }
            }
        )
        
        popup.showInBestPositionFor(editor)
    }
    
    /**
     * Navigate to a specific reference
     */
    private fun navigateToReference(project: Project, reference: FileReferenceIndex.Reference) {
        val file = reference.sourceFile
        
        // If we have a selfLineNumber, use that for navigation
        if (reference.selfLineNumber > 0) {
            LOG.debug("Navigating to reference in ${file.path} at line ${reference.selfLineNumber}")
            
            // Get the document to convert line number to offset
            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile != null) {
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                if (document != null) {
                    // Convert line number to offset (line numbers are 1-based, so subtract 1)
                    val lineOffset = document.getLineStartOffset(reference.selfLineNumber - 1)
                    val descriptor = OpenFileDescriptor(project, file, lineOffset)
                    descriptor.navigate(true)
                    return
                }
            }
        }
        
        // Fall back to using the stored offset for navigation if selfLineNumber isn't available
        LOG.debug("Navigating to reference in ${file.path} at offset ${reference.startOffset}")
        val descriptor = OpenFileDescriptor(project, file, reference.startOffset)
        descriptor.navigate(true)
    }
    
    /**
     * Data class for an item in the references popup
     */
    data class ReferenceItem(
        val displayText: String,
        val reference: FileReferenceIndex.Reference
    )
}
