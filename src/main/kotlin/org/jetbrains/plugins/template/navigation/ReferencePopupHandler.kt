package org.jetbrains.plugins.template.navigation

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
            LOG.info("No references found for ${file.path}:$lineNumber")
            return
        }
        
        LOG.info("Found ${references.size} references to ${file.path}:$lineNumber")
        
        // Deduplicate references by source file and line numbers
        val uniqueReferences = references.distinctBy { 
            "${it.sourceFile.path}:${it.startLine}-${it.endLine}" 
        }
        
        LOG.info("After deduplication: ${uniqueReferences.size} unique references to ${file.path}:$lineNumber")
        
        // Create a list of reference items for the popup
        val items = uniqueReferences.mapIndexed { index, reference ->
            val sourceFile = reference.sourceFile
            val document = PsiDocumentManager.getInstance(project).getDocument(
                PsiManager.getInstance(project).findFile(sourceFile) ?: return@mapIndexed null
            ) ?: return@mapIndexed null
            
            // Convert offset to line number for display
            val startLine = document.getLineNumber(reference.startOffset) + 1
            
            // Include line range information if available
            val displayText = if (reference.startLine > 0 && reference.endLine > 0 && reference.endLine > reference.startLine) {
                "Reference ${index + 1}: ${sourceFile.name}:L${reference.startLine}-${reference.endLine}"
            } else {
                "Reference ${index + 1}: ${sourceFile.name}:$startLine"
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
        LOG.info("Navigating to reference in ${file.path} at offset ${reference.startOffset}")
        
        // Use the stored offset for precise navigation
        val descriptor = OpenFileDescriptor(project, file, reference.startOffset)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        
        // If we have a line range, select the text in that range
        if (editor != null && reference.startLine > 0 && reference.endLine > 0 && reference.endLine > reference.startLine) {
            try {
                val document = editor.document
                val startOffset = document.getLineStartOffset(reference.startLine - 1)
                val endOffset = document.getLineEndOffset(reference.endLine - 1)
                editor.selectionModel.setSelection(startOffset, endOffset)
                editor.caretModel.moveToOffset(startOffset)
                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
            } catch (e: Exception) {
                LOG.error("Error selecting text range", e)
            }
        }
    }
    
    /**
     * Data class for an item in the references popup
     */
    data class ReferenceItem(
        val displayText: String,
        val reference: FileReferenceIndex.Reference
    )
}
