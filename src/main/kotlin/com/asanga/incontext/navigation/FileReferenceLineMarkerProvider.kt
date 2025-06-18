package com.asanga.incontext.navigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ColorIcon
import com.asanga.incontext.util.FileReferenceUtil
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Provides line markers for file references
 */
class FileReferenceLineMarkerProvider : LineMarkerProvider {
    companion object {
        private val LOG = Logger.getInstance(FileReferenceLineMarkerProvider::class.java)

        // Debug patterns - if not empty, only files matching these patterns will log debug info
        private val DEBUG_FILE_PATTERNS = listOf<String>()

        // Icon for file references
        private val REFERENCE_ICON = ColorIcon(12, JBUIScale.scale(12), Color.BLUE, true)

        // Cache of processed lines per file
        private val processedFiles = ConcurrentHashMap<String, Boolean>()
        private val processedLines = ConcurrentHashMap<String, MutableSet<Int>>()
        private val fileLineRanges = ConcurrentHashMap<String, List<FileReferenceIndex.LineRange>>()
        
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

    /**
     * Get line marker info for a specific element
     */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    /**
     * Collect line markers for a list of elements
     */
    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty()) return
        
        // Only process elements that are part of a file
        val element = elements[0]
        val file = element.containingFile ?: return
        val project = element.project

        // Get the document for this file
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        // Get the virtual file
        val virtualFile = PsiUtilCore.getVirtualFile(file) ?: return

        // Get the module for this file
        val projectFileIndex = ProjectFileIndex.getInstance(project)
        val module = projectFileIndex.getModuleForFile(virtualFile)
        val moduleName = module?.name ?: project.name

        // Create a unique key for this file using module name instead of project name
        val fileKey = "$moduleName:${virtualFile.path}"

        // Check if we've already processed this file
        val isTargetFile = DEBUG_FILE_PATTERNS.isEmpty() || DEBUG_FILE_PATTERNS.any { virtualFile.path.contains(it) }

        // Special handling for the first element in a file - process all lines in the file
        val isFirstElement = isFirstElementInFile(element, document)

        if (isFirstElement) {
            if (isTargetFile) {
                LOG.info("Processing first element in ${virtualFile.path}")
            }

            // Process all lines in the file
            processAllLinesInFile(element, result, virtualFile, fileKey, isTargetFile)
            return
        }

        // Process just this element's line
        processLine(element, result, virtualFile, document.getLineNumber(element.textRange.startOffset) + 1, fileKey, isTargetFile)
    }

    /**
     * Check if this is the first element in the file
     */
    private fun isFirstElementInFile(element: PsiElement, document: Document): Boolean {
        val startOffset = element.textRange.startOffset
        val lineNumber = document.getLineNumber(startOffset)
        return lineNumber == 0 && startOffset < 50 // First line and near the beginning of the file
    }

    /**
     * Process all lines in a file that have references
     */
    private fun processAllLinesInFile(
        element: PsiElement,
        result: MutableCollection<in LineMarkerInfo<*>>,
        virtualFile: VirtualFile,
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

        val processedLinesForFile = processedLines.computeIfAbsent(fileKey) { ConcurrentHashMap.newKeySet() }

        // Process each line range
        for (lineRange in lineRanges) {
            for (line in lineRange.startLine..lineRange.endLine) {
                // Check if we've already processed this line AND if a gutter icon already exists
                if (processedLinesForFile.contains(line) && hasExistingGutterIcon(element.project, virtualFile, line)) {
                    if (isTargetFile) {
                        LOG.info("Line $line already processed and has gutter icon, skipping")
                    }
                    continue
                }

                // Mark this line as processed
                processedLinesForFile.add(line)

                // Get the document for this file
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: continue

                // Skip if the line number is out of bounds
                if (line <= 0 || line > document.lineCount) {
                    LOG.warn("Line number $line is out of bounds for document with ${document.lineCount} lines")
                    continue
                }

                try {
                    // Get the offset for this line (0-based to 1-based conversion)
                    val lineStartOffset = document.getLineStartOffset(line - 1)
                    val lineEndOffset = document.getLineEndOffset(line - 1)

                    // Find a suitable element at this offset
                    val psiFile = PsiManager.getInstance(element.project).findFile(virtualFile) ?: continue
                    val elementAtLine = psiFile.findElementAt(lineStartOffset) ?: continue

                    // Get the reference count for this line
                    val referenceCount = getReferenceCountForLine(element.project, virtualFile, line)
                    if (referenceCount > 0) {
                        // Create a custom gutter icon for this line
                        val lineRange = TextRange(lineStartOffset, lineEndOffset)
                        val lineMarkerInfo = createCustomGutterIconRenderer(elementAtLine, virtualFile, line, referenceCount, lineRange)
                        result.add(lineMarkerInfo)
                        if (isTargetFile) {
                            LOG.info("Added gutter icon for ${virtualFile.path}:$line with $referenceCount references")
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Error creating gutter icon for ${virtualFile.path}:$line", e)
                }
            }
        }
    }

    /**
     * Check if a gutter icon already exists for the given line
     */
    private fun hasExistingGutterIcon(project: Project, file: VirtualFile, line: Int): Boolean {
        try {
            // Get the editor for this file if it's open
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editor = fileEditorManager.getSelectedTextEditor() ?: return false
            
            // Check if this editor is for our file
            val currentFile = FileDocumentManager.getInstance().getFile(editor.document)
            if (currentFile != file) {
                return false
            }
            
            val document = editor.document
            
            // Skip if line is out of bounds (1-based to 0-based conversion)
            if (line <= 0 || line > document.lineCount) {
                return false
            }
            
            val lineStartOffset = document.getLineStartOffset(line - 1)
            val markupModel = editor.markupModel
            
            // Check if there are any gutter icons at this line
            val renderers = markupModel.allHighlighters
                .filter { 
                    it.gutterIconRenderer != null && 
                    it.startOffset <= lineStartOffset && 
                    it.endOffset >= lineStartOffset 
                }
            
            return renderers.isNotEmpty()
        } catch (e: Exception) {
            LOG.warn("Error checking for existing gutter icons: ${e.message}")
            return false
        }
    }

    /**
     * Get the reference count for a specific line in a file
     */
    private fun getReferenceCountForLine(project: Project, file: VirtualFile, line: Int): Int {
        val referenceIndex = FileReferenceIndex.getInstance(project)
        val references = referenceIndex.findReferencesToLocation(file, line)
        return references.size
    }

    /**
     * Process a single line in a file
     */
    private fun processLine(
        element: PsiElement,
        result: MutableCollection<in LineMarkerInfo<*>>,
        virtualFile: VirtualFile,
        lineNumber: Int,
        fileKey: String,
        isTargetFile: Boolean
    ) {
        if (isTargetFile) {
            LOG.info("Processing element at ${virtualFile.path}:$lineNumber (element type: ${element.javaClass.simpleName}, text: ${element.text.take(20)})")
        }

        // Check if we've already processed this line for this file
        val processedLinesForFile = processedLines.computeIfAbsent(fileKey) { ConcurrentHashMap.newKeySet() }
        
        // Check if we've already processed this line AND if a gutter icon already exists
        if (processedLinesForFile.contains(lineNumber) && hasExistingGutterIcon(element.project, virtualFile, lineNumber)) {
            if (isTargetFile) {
                LOG.info("Line $lineNumber already processed and has gutter icon, skipping")
            }
            return
        }

        // Mark this line as processed
        processedLinesForFile.add(lineNumber)

        // Get the reference count for this line
        val referenceCount = getReferenceCountForLine(element.project, virtualFile, lineNumber)
        if (referenceCount > 0) {
            // Create a custom gutter icon for this line
            val lineMarkerInfo = createCustomGutterIconRenderer(element, virtualFile, lineNumber, referenceCount)
            result.add(lineMarkerInfo)
            if (isTargetFile) {
                LOG.info("Added gutter icon for ${virtualFile.path}:$lineNumber with $referenceCount references")
            }
        } else if (isTargetFile) {
            LOG.info("No references found for ${virtualFile.path}:$lineNumber")
        }
    }

    /**
     * Get the line ranges for a file, computing them if necessary
     */
    private fun getLineRangesForFile(project: Project, virtualFile: VirtualFile, fileKey: String): List<FileReferenceIndex.LineRange> {
        // Use ReadAction to ensure thread safety when accessing PSI elements
        return ReadAction.compute<List<FileReferenceIndex.LineRange>, Throwable> {
            val referenceIndex = FileReferenceIndex.getInstance(project)
            val ranges = referenceIndex.getAllLineRangesForFile(virtualFile)
            LOG.info("Computed ${ranges.size} line ranges for ${virtualFile.path}: ${ranges.joinToString { "${it.startLine}-${it.endLine}" }}")
            ranges
        }
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
    ): LineMarkerInfo<PsiElement> {
        // Wrap the creation in a try-catch to handle potential ProcessCanceledException
        try {
            // Create a custom renderer
            val renderer = object : GutterIconRenderer() {
                override fun getIcon(): Icon = REFERENCE_ICON

                override fun getTooltipText(): String = "Referenced in $referenceCount location(s)"

                override fun isNavigateAction(): Boolean = true

                override fun getClickAction(): AnAction = object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        val project = element.project
                        val editor = e.getData(CommonDataKeys.EDITOR)

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
            return LineMarkerInfo(
                element,
                textRange,
                REFERENCE_ICON,
                { "Referenced in $referenceCount location(s)" },
                { e, elt ->
                    val editor = FileEditorManager.getInstance(element.project).selectedTextEditor
                    if (editor != null) {
                        ReferencePopupHandler.showReferencesPopup(element.project, editor, file, lineNumber)
                    }
                },
                GutterIconRenderer.Alignment.RIGHT,
                { "Referenced in $referenceCount location(s)" }
            )
        } catch (e: ProcessCanceledException) {
            // Re-throw ProcessCanceledException as it's a special case that should be propagated
            throw e
        } catch (e: Exception) {
            LOG.error("Error creating gutter icon for ${file.path}:$lineNumber", e)
            // Create a minimal fallback marker to avoid crashing
            return LineMarkerInfo(
                element,
                textRange,
                REFERENCE_ICON,
                { "Referenced in $referenceCount location(s)" },
                null,
                GutterIconRenderer.Alignment.RIGHT,
                { "Referenced in $referenceCount location(s)" }
            )
        }
    }
}
