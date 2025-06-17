package org.jetbrains.plugins.template.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import java.util.regex.Pattern

/**
 * Service that indexes file references in markdown and other files
 */
@Service(Service.Level.PROJECT)
class FileReferenceIndexer(private val project: Project) {
    
    companion object {
        private val LOG = Logger.getInstance(FileReferenceIndexer::class.java)
        
        // Pattern to match file references like @path/to/file.ts:L10 or @path/to/file.ts:L10-20
        private val FILE_REFERENCE_PATTERN = Pattern.compile(
            "@([\\w\\-./]+)(?::L(\\d+)(?:-(\\d+))?)?",
            Pattern.CASE_INSENSITIVE
        )
        
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
         * Get the instance of the service for the given project
         */
        fun getInstance(project: Project): FileReferenceIndexer {
            return project.getService(FileReferenceIndexer::class.java)
        }
    }
    
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
    fun processFile(file: VirtualFile) {
        LOG.debug("Processing file for references: ${file.path}")
        
        ReadAction.run<Throwable> {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@run
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@run
                
                // Clear existing references from this file
                val referenceIndex = FileReferenceIndex.getInstance(project)
                referenceIndex.removeReferencesFromFile(file)
                
                // Get the file content
                val content = document.text
                
                // Find all references in the file
                val matcher = FILE_REFERENCE_PATTERN.matcher(content)
                while (matcher.find()) {
                    val filePath = matcher.group(1)
                    val startLineStr = matcher.group(2)
                    val endLineStr = matcher.group(3)
                    
                    // Convert line numbers to integers
                    val startLine = startLineStr?.toIntOrNull() ?: 1
                    val endLine = endLineStr?.toIntOrNull() ?: startLine
                    
                    LOG.debug("Found reference: $filePath:L$startLine${if (endLine > startLine) "-$endLine" else ""}")
                    
                    // Find the target file
                    val targetFile = findFileInProject(filePath)
                    if (targetFile != null) {
                        // Add the reference to the index
                        referenceIndex.addReference(
                            targetFile,
                            startLine,
                            endLine,
                            file,
                            matcher.start(),
                            matcher.end()
                        )
                    } else {
                        LOG.debug("Target file not found: $filePath")
                    }
                }
                
                // Refresh code analysis for this file and the referenced files
                refreshCodeAnalysis()
            } catch (e: Exception) {
                LOG.error("Error processing file ${file.path}: ${e.message}")
            }
        }
    }
    
    /**
     * Find a file in the project by its path
     */
    private fun findFileInProject(filePath: String): VirtualFile? {
        // Try to find the file relative to the project root
        val projectBasePath = project.basePath
        if (projectBasePath != null) {
            val fullPath = "$projectBasePath/$filePath"
            val file = VirtualFileManager.getInstance().findFileByUrl("file://$fullPath")
            if (file != null && file.exists()) {
                return file
            }
        }
        
        // Try to find the file by its name in the project
        val fileName = filePath.substringAfterLast('/')
        val filesByName = mutableListOf<VirtualFile>()
        
        ReadAction.run<Throwable> {
            VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://${project.basePath}")?.let { baseDir ->
                collectFilesByName(baseDir, fileName, filesByName)
            }
        }
        
        // If we found exactly one file with this name, use it
        if (filesByName.size == 1) {
            return filesByName[0]
        }
        
        // If we found multiple files, try to match by path components
        if (filesByName.size > 1) {
            val pathComponents = filePath.split('/')
            
            for (file in filesByName) {
                val filePathComponents = file.path.split('/')
                
                // Check if the file path ends with the components we're looking for
                if (filePathComponents.size >= pathComponents.size) {
                    val endComponents = filePathComponents.takeLast(pathComponents.size)
                    if (endComponents == pathComponents) {
                        return file
                    }
                }
            }
        }
        
        return null
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
     * Collect all files with a specific name
     */
    private fun collectFilesByName(dir: VirtualFile, name: String, result: MutableList<VirtualFile>) {
        // Skip excluded directories
        if (shouldSkipDirectory(dir)) {
            LOG.debug("Skipping excluded directory: ${dir.path}")
            return
        }
        
        for (child in dir.children) {
            if (child.isDirectory) {
                collectFilesByName(child, name, result)
            } else if (child.name == name) {
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
        // Use DaemonCodeAnalyzer to refresh code analysis
        val daemonCodeAnalyzer = DaemonCodeAnalyzer.getInstance(project)
        
        // Clear the line marker provider caches to ensure fresh rendering
        ApplicationManager.getApplication().invokeLater {
            // Clear file line range caches in the line marker provider
            FileReferenceLineMarkerProvider.clearCache()
            
            ReadAction.run<Throwable> {
                val psiManager = PsiManager.getInstance(project)
                val fileIndex = FileReferenceIndex.getInstance(project)
                
                // Get all files with references
                val filesWithReferences = mutableSetOf<VirtualFile>()
                
                // Add all files that have references
                for (filePath in fileIndex.getAllFilesWithReferences()) {
                    val fileUrl = "file://$filePath"
                    LOG.debug("Looking for file: $fileUrl")
                    val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl)
                    if (file != null && file.exists()) {
                        LOG.debug("Found file: ${file.path}")
                        filesWithReferences.add(file)
                    } else {
                        LOG.warn("Could not find file: $filePath")
                    }
                }
                
                LOG.debug("Refreshing code analysis for ${filesWithReferences.size} files")
                
                // Refresh code analysis for each file
                for (file in filesWithReferences) {
                    val psiFile = psiManager.findFile(file)
                    if (psiFile != null) {
                        LOG.debug("Restarting daemon code analyzer for ${file.path}")
                        daemonCodeAnalyzer.restart(psiFile)
                    } else {
                        LOG.warn("Could not find PSI file for ${file.path}")
                    }
                }
                
                // Dump the cache state for debugging
                FileReferenceLineMarkerProvider.dumpCacheState()
            }
        }
    }
    
    /**
     * Manually refresh code analysis for a specific file
     */
    fun refreshFileCodeAnalysis(filePath: String) {
        LOG.debug("Manual refresh requested for file: $filePath")
        
        // Clear the line marker provider caches
        FileReferenceLineMarkerProvider.clearCache()
        
        ApplicationManager.getApplication().invokeLater {
            ReadAction.run<Throwable> {
                val fileUrl = "file://$filePath"
                LOG.debug("Looking for file: $fileUrl")
                val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl)
                
                if (file != null && file.exists()) {
                    LOG.debug("Found file: ${file.path}")
                    val psiFile = PsiManager.getInstance(project).findFile(file)
                    
                    if (psiFile != null) {
                        LOG.debug("Restarting daemon code analyzer for ${file.path}")
                        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                        
                        // Dump the cache state for debugging
                        FileReferenceLineMarkerProvider.dumpCacheState()
                    } else {
                        LOG.warn("Could not find PSI file for ${file.path}")
                    }
                } else {
                    LOG.warn("Could not find file: $filePath")
                }
            }
        }
    }
    
    /**
     * Dispose the service
     */
    fun dispose() {
        VirtualFileManager.getInstance().removeVirtualFileListener(fileListener)
    }
}
