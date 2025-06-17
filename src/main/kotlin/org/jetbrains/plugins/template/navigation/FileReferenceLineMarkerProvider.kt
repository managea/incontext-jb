package org.jetbrains.plugins.template.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.Function
import java.awt.event.MouseEvent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Line marker provider that shows an icon in the gutter when a file section is referenced elsewhere
 */
class FileReferenceLineMarkerProvider : RelatedItemLineMarkerProvider() {

    companion object {
        private val LOG = Logger.getInstance(FileReferenceLineMarkerProvider::class.java)
        private val REFERENCE_ICON = AllIcons.Gutter.ImplementedMethod // Using a built-in icon
        
        // Cache of processed files to avoid duplicate processing
        private val processedFiles = ConcurrentHashMap<String, Boolean>()
        
        // Cache of processed lines to avoid duplicate gutter icons
        private val processedLines = ConcurrentHashMap<String, MutableSet<Int>>()
        
        // Cache of line ranges for each file
        private val fileLineRanges = ConcurrentHashMap<String, List<FileReferenceIndex.LineRange>>()
        
        // Debug flag for specific files - empty means debug all files
        private val DEBUG_FILE_PATTERNS = listOf<String>()
        
        /**
         * Clear all caches to force refresh of gutter icons
         */
        fun clearCache() {
            LOG.info("Clearing FileReferenceLineMarkerProvider caches")
            processedLines.clear()
            processedFiles.clear()
            fileLineRanges.clear()
        }
        
        /**
         * Debug method to dump the current state of the caches
         */
        fun dumpCacheState() {
            LOG.info("=== FileReferenceLineMarkerProvider Cache State ===")
            LOG.info("Processed Files: ${processedFiles.size}")
            LOG.info("Processed Lines: ${processedLines.size} files with processed lines")
            for ((fileKey, lines) in processedLines) {
                LOG.info("  $fileKey: ${lines.size} lines processed: ${lines.sorted().joinToString()}")
            }
            LOG.info("File Line Ranges: ${fileLineRanges.size} files with cached line ranges")
            for ((fileKey, ranges) in fileLineRanges) {
                LOG.info("  $fileKey: ${ranges.size} ranges: ${ranges.joinToString { "${it.startLine}-${it.endLine}" }}")
            }
            LOG.info("=== End Cache State ===")
        }
    }

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        // Only process elements that are part of a file
        val file = element.containingFile ?: return
        val project = element.project
        
        // Get the document for this file
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        
        // Get the virtual file
        val virtualFile = PsiUtilCore.getVirtualFile(file) ?: return
        
        // Create a unique key for this file
        val fileKey = "${project.name}:${virtualFile.path}"
        
        // Check if this is a target file for detailed logging
        val isTargetFile = DEBUG_FILE_PATTERNS.isEmpty() || DEBUG_FILE_PATTERNS.any { virtualFile.path.contains(it) }
        
        // Special handling for the first element in a file - process all lines in the file
        val isFirstElement = isFirstElementInFile(element, document)
        
        if (isFirstElement) {
            if (isTargetFile) {
                LOG.info("First element in file ${virtualFile.path}, processing all lines")
            }
            
            // Process all lines in the file
            processAllLinesInFile(virtualFile, document, element, result, fileKey, isTargetFile)
            return
        }
        
        // For non-first elements, process just this line
        processLine(virtualFile, element, document, result, fileKey, isTargetFile)
    }
    
    /**
     * Check if this element is the first element in the file
     */
    private fun isFirstElementInFile(element: PsiElement, document: Document): Boolean {
        val startOffset = element.textRange.startOffset
        val lineNumber = document.getLineNumber(startOffset)
        return lineNumber == 0
    }
    
    /**
     * Process all lines in a file
     */
    private fun processAllLinesInFile(
        virtualFile: VirtualFile,
        document: Document,
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
        fileKey: String,
        isTargetFile: Boolean
    ) {
        // Get or compute line ranges for this file
        val lineRanges = getLineRangesForFile(element.project, virtualFile, fileKey)
        
        if (lineRanges.isEmpty()) {
            if (isTargetFile) {
                LOG.info("No line ranges found for ${virtualFile.path}")
            }
            return
        }
        
        if (isTargetFile) {
            LOG.info("File ${virtualFile.path} has ${lineRanges.size} line ranges: ${lineRanges.joinToString { "${it.startLine}-${it.endLine}" }}")
        }
        
        // Get the processed lines for this file
        val processedLinesForFile = processedLines.computeIfAbsent(fileKey) { ConcurrentHashMap.newKeySet() }
        
        // Process each line range
        for (lineRange in lineRanges) {
            for (line in lineRange.startLine..lineRange.endLine) {
                // Skip if we've already processed this line
                if (processedLinesForFile.contains(line)) {
                    if (isTargetFile) {
                        LOG.info("Line $line already processed, skipping")
                    }
                    continue
                }
                
                // Mark this line as processed
                processedLinesForFile.add(line)
                
                // Find references to this location
                val referenceIndex = FileReferenceIndex.getInstance(element.project)
                val references = referenceIndex.findReferencesToLocation(virtualFile, line)
                
                if (references.isEmpty()) {
                    if (isTargetFile) {
                        LOG.info("No references found for ${virtualFile.path}:$line")
                    }
                    continue
                }
                
                if (isTargetFile) {
                    LOG.info("Found ${references.size} references to ${virtualFile.path}:$line")
                }
                
                // Create a line marker for this line
                try {
                    // Get the offset for this line
                    val lineStartOffset = document.getLineStartOffset(line - 1)
                    val lineEndOffset = document.getLineEndOffset(line - 1)
                    
                    // Create a text range for this line
                    val lineRange = TextRange(lineStartOffset, lineEndOffset)
                    
                    // Create a custom gutter icon renderer
                    val lineMarkerInfo = createCustomGutterIconRenderer(element, virtualFile, line, references.size, lineRange)
                    result.add(lineMarkerInfo)
                    
                    if (isTargetFile) {
                        LOG.info("Added gutter icon for ${virtualFile.path}:$line")
                    }
                } catch (e: Exception) {
                    LOG.error("Error creating gutter icon for ${virtualFile.path}:$line", e)
                }
            }
        }
    }
    
    /**
     * Process a single line
     */
    private fun processLine(
        virtualFile: VirtualFile,
        element: PsiElement,
        document: Document,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
        fileKey: String,
        isTargetFile: Boolean
    ) {
        // Get the current line number (1-based)
        val startOffset = element.textRange.startOffset
        val lineNumber = document.getLineNumber(startOffset) + 1
        
        if (isTargetFile) {
            LOG.info("Processing element at ${virtualFile.path}:$lineNumber (element type: ${element.javaClass.simpleName}, text: ${element.text.take(20)})")
        }
        
        // Get or compute line ranges for this file
        val lineRanges = getLineRangesForFile(element.project, virtualFile, fileKey)
        
        if (lineRanges.isEmpty()) {
            if (isTargetFile) {
                LOG.info("No line ranges found for ${virtualFile.path}")
            }
            return
        }
        
        if (isTargetFile) {
            LOG.info("File ${virtualFile.path} has ${lineRanges.size} line ranges: ${lineRanges.joinToString { "${it.startLine}-${it.endLine}" }}")
        }
        
        // Check if this line is within any of the line ranges
        val matchingRanges = findMatchingLineRanges(lineNumber, lineRanges)
        
        if (matchingRanges.isEmpty()) {
            if (isTargetFile) {
                LOG.info("Line $lineNumber is not within any ranges for ${virtualFile.path}")
            }
            return
        }
        
        if (isTargetFile) {
            LOG.info("Line $lineNumber is within ${matchingRanges.size} ranges: ${matchingRanges.joinToString { "${it.startLine}-${it.endLine}" }}")
        }
        
        // Check if we've already processed this line for this file
        val processedLinesForFile = processedLines.computeIfAbsent(fileKey) { ConcurrentHashMap.newKeySet() }
        if (processedLinesForFile.contains(lineNumber)) {
            if (isTargetFile) {
                LOG.info("Line $lineNumber already processed, skipping")
            }
            return
        }
        
        // Mark this line as processed
        processedLinesForFile.add(lineNumber)
        
        // Find references to this location
        val referenceIndex = FileReferenceIndex.getInstance(element.project)
        val references = referenceIndex.findReferencesToLocation(virtualFile, lineNumber)
        
        if (references.isEmpty()) {
            if (isTargetFile) {
                LOG.info("No references found for ${virtualFile.path}:$lineNumber")
            }
            return
        }
        
        if (isTargetFile) {
            LOG.info("Found ${references.size} references to ${virtualFile.path}:$lineNumber")
        }
        
        // Create a custom gutter icon renderer
        val lineMarkerInfo = createCustomGutterIconRenderer(element, virtualFile, lineNumber, references.size)
        result.add(lineMarkerInfo)
        
        if (isTargetFile) {
            LOG.info("Added gutter icon for ${virtualFile.path}:$lineNumber")
        }
    }
    
    /**
     * Get the line ranges for a file, computing them if necessary
     */
    private fun getLineRangesForFile(project: Project, virtualFile: VirtualFile, fileKey: String): List<FileReferenceIndex.LineRange> {
        return fileLineRanges.computeIfAbsent(fileKey) {
            ReadAction.compute<List<FileReferenceIndex.LineRange>, Throwable> {
                val referenceIndex = FileReferenceIndex.getInstance(project)
                val ranges = referenceIndex.getAllLineRangesForFile(virtualFile)
                LOG.info("Computed ${ranges.size} line ranges for ${virtualFile.path}: ${ranges.joinToString { "${it.startLine}-${it.endLine}" }}")
                ranges
            }
        }
    }
    
    /**
     * Find all line ranges that include the given line number
     */
    private fun findMatchingLineRanges(lineNumber: Int, lineRanges: List<FileReferenceIndex.LineRange>): List<FileReferenceIndex.LineRange> {
        return lineRanges.filter { lineNumber >= it.startLine && lineNumber <= it.endLine }
    }
    
    /**
     * Create a custom gutter icon renderer that will show a popup with all references when clicked
     */
    private fun createCustomGutterIconRenderer(
        element: PsiElement, 
        file: VirtualFile, 
        lineNumber: Int,
        referenceCount: Int,
        textRange: TextRange = element.textRange
    ): RelatedItemLineMarkerInfo<PsiElement> {
        // Create a custom renderer
        val renderer = object : GutterIconRenderer() {
            override fun getIcon(): Icon = REFERENCE_ICON
            
            override fun getTooltipText(): String = "Referenced in $referenceCount location(s)"
            
            override fun isNavigateAction(): Boolean = true
            
            override fun getClickAction(): AnAction = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    val project = element.project
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    
                    if (editor != null) {
                        LOG.info("Showing references popup for ${file.path}:$lineNumber")
                        ReferencePopupHandler.showReferencesPopup(project, editor, file, lineNumber)
                    }
                }
            }
            
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                return true
            }
            
            override fun hashCode(): Int {
                return javaClass.hashCode()
            }
        }
        
        // Create the line marker info with the custom renderer
        return RelatedItemLineMarkerInfo(
            element,
            textRange,
            REFERENCE_ICON,
            { "Referenced in $referenceCount location(s)" },
            { _, _ -> 
                val editor = FileEditorManager.getInstance(element.project).selectedTextEditor
                if (editor != null) {
                    ReferencePopupHandler.showReferencesPopup(element.project, editor, file, lineNumber)
                }
                Unit
            },
            GutterIconRenderer.Alignment.LEFT,
            { emptyList<GotoRelatedItem>() }
        )
    }
}
