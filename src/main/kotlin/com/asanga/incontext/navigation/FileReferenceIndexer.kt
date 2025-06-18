package com.asanga.incontext.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.components.Service
import com.asanga.incontext.util.FileReferenceUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Service that indexes file references in the project
 */
@Service
class FileReferenceIndexer(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(FileReferenceIndexer::class.java)

        // File extensions to process
        private val SUPPORTED_EXTENSIONS = setOf(
            "md", "markdown", "txt", "text", "js", "ts", "jsx", "tsx", "kt", "java", "py", "html", "css"
        )

        // Directories to exclude from processing
        private val EXCLUDED_DIRECTORIES = setOf(
            "node_modules",
            ".git",
            "dist",
            "build",
            "out",
            "target",
            ".idea",
            ".gradle",
            "vendor",
            "bower_components"
        )

        /**
         * Get the instance of FileReferenceIndexer for the given project
         */
        fun getInstance(project: Project): FileReferenceIndexer {
            return project.getService(FileReferenceIndexer::class.java)
        }
    }

    private val referenceIndex = FileReferenceIndex.getInstance(project)
    private val fileListener = object : VirtualFileListener {
        override fun contentsChanged(event: VirtualFileEvent) {
            val file = event.file
            if (shouldProcessFile(file)) {
                LOG.debug("File contents changed: ${file.path}")
                processFile(file)
            }
        }

        override fun fileCreated(event: VirtualFileEvent) {
            val file = event.file
            if (shouldProcessFile(file)) {
                LOG.debug("File created: ${file.path}")
                processFile(file)
            }
        }

        override fun fileDeleted(event: VirtualFileEvent) {
            val file = event.file
            LOG.debug("File deleted: ${file.path}")

            // Remove all references from this file
            val referenceIndex = FileReferenceIndex.getInstance(project)
            referenceIndex.removeReferencesFromFile(file)

            // Refresh code analysis for files that might have referenced this file
            refreshCodeAnalysis()
        }
    }

    init {
        // Register file listener
        VirtualFileManager.getInstance().addVirtualFileListener(fileListener)
    }

    /**
     * Process all files in the project to build the reference index
     */
    fun indexProject() {
        LOG.debug("Indexing project files for references")

        ReadAction.run<Throwable> {
            val psiManager = PsiManager.getInstance(project)
            val projectFiles = mutableListOf<VirtualFile>()

            // Collect all project files
            VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://${project.basePath}")?.let { baseDir ->
                collectFiles(baseDir, projectFiles)
            }

            LOG.debug("Found ${projectFiles.size} files to process")

            // Process each file
            for (file in projectFiles) {
                if (shouldProcessFile(file)) {
                    processFile(file)
                }
            }
        }
    }

    /**
     * Process a single file to extract references
     */
    private fun processFile(file: VirtualFile) {
        LOG.info("Processing file for references: ${file.path}")
        try {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

            // Clear existing references from this file
            referenceIndex.removeReferencesFromFile(file)

            // Get the file content
            val content = document.text

            // Find all references in the file
            val matcher = FileReferenceUtil.FILE_REFERENCE_PATTERN.matcher(content)
            while (matcher.find()) {
                val reference = matcher.group(0)
                LOG.debug("Found reference: $reference")

                // Parse the reference
                val referenceParts = FileReferenceUtil.parseReference(reference)
                if (referenceParts == null) {
                    LOG.warn("Failed to parse reference: $reference")
                    continue
                }

                // Calculate the line number where this reference is defined
                val referenceStartOffset = matcher.start()
                val selfLineNumber = document.getLineNumber(referenceStartOffset) + 1 // Convert to 1-based line number

                LOG.debug("Found reference: ${referenceParts.moduleName}/${referenceParts.relativePath}:L${referenceParts.startLine}${if (referenceParts.endLine > referenceParts.startLine) "-${referenceParts.endLine}" else ""} at line $selfLineNumber")

                // Find the target file
                val targetFile = FileReferenceUtil.findFileInProject(project, referenceParts.moduleName, referenceParts.relativePath)
                if (targetFile != null) {
                    // Add the reference to the index
                    referenceIndex.addReference(
                        targetFile,
                        referenceParts.startLine,
                        referenceParts.endLine,
                        file,
                        matcher.start(),
                        matcher.end(),
                        selfLineNumber
                    )
                } else {
                    LOG.debug("Target file not found: ${referenceParts.moduleName}/${referenceParts.relativePath}")
                }
            }

            // Refresh code analysis for this file and the referenced files
            refreshCodeAnalysis()
        } catch (e: Exception) {
            LOG.error("Error processing file ${file.path}: ${e.message}")
        }
    }

    /**
     * Collect all files in a directory recursively
     */
    private fun collectFiles(dir: VirtualFile, result: MutableList<VirtualFile>) {
        // Skip excluded directories
        if (shouldSkipDirectory(dir)) {
            LOG.debug("Skipping excluded directory: ${dir.path}")
            return
        }

        for (child in dir.children) {
            if (child.isDirectory) {
                collectFiles(child, result)
            } else {
                result.add(child)
            }
        }
    }

    /**
     * Check if a directory should be skipped
     */
    private fun shouldSkipDirectory(dir: VirtualFile): Boolean {
        return EXCLUDED_DIRECTORIES.contains(dir.name)
    }

    /**
     * Check if a file should be processed for references
     */
    private fun shouldProcessFile(file: VirtualFile): Boolean {
        // Skip files in excluded directories
        if (isInExcludedDirectory(file)) {
            return false
        }

        val extension = file.extension?.lowercase() ?: return false
        return SUPPORTED_EXTENSIONS.contains(extension)
    }

    /**
     * Check if a file is in an excluded directory
     */
    private fun isInExcludedDirectory(file: VirtualFile): Boolean {
        var parent = file.parent
        while (parent != null) {
            if (EXCLUDED_DIRECTORIES.contains(parent.name)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    /**
     * Refresh code analysis to update gutter icons
     */
    private fun refreshCodeAnalysis() {
        LOG.debug("Refreshing code analysis for all files")

        ApplicationManager.getApplication().invokeLater {
            try {
                DaemonCodeAnalyzer.getInstance(project).restart()
                LOG.debug("Code analysis refreshed for all files")
            } catch (e: Exception) {
                LOG.error("Error refreshing code analysis", e)
            }
        }
    }

    /**
     * Manually refresh code analysis for a specific file
     */
    fun refreshFileCodeAnalysis(filePath: String) {
        LOG.debug("Manual refresh requested for file: $filePath")

        try {
            val file = VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
            if (file != null) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val psiFile = PsiManager.getInstance(project).findFile(file)
                        if (psiFile != null) {
                            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                            LOG.debug("Code analysis refreshed for file: $filePath")
                        }
                    } catch (e: Exception) {
                        LOG.error("Error refreshing code analysis for file: $filePath", e)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Error finding file for refreshing code analysis: $filePath", e)
        }
    }

    /**
     * Dispose the service
     */
    fun dispose() {
        VirtualFileManager.getInstance().removeVirtualFileListener(fileListener)
    }
}
