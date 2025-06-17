package org.jetbrains.plugins.template.navigation

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap

/**
 * Service that keeps track of file references for navigation
 */
@Service(Service.Level.PROJECT)
class FileReferenceIndex(private val project: Project) {
    
    companion object {
        private val LOG = Logger.getInstance(FileReferenceIndex::class.java)
        
        /**
         * Get the instance of the service for the given project
         */
        fun getInstance(project: Project): FileReferenceIndex {
            return project.getService(FileReferenceIndex::class.java)
        }
    }
    
    // Map of target file path to line range to list of references
    private val referenceMap = ConcurrentHashMap<String, MutableMap<LineRange, MutableList<Reference>>>()
    
    /**
     * Add a reference to the index
     */
    fun addReference(
        targetFile: VirtualFile,
        targetStartLine: Int,
        targetEndLine: Int,
        sourceFile: VirtualFile,
        sourceStartOffset: Int,
        sourceEndOffset: Int
    ) {
        val targetPath = targetFile.path
        val lineRange = LineRange(targetStartLine, targetEndLine)
        val reference = Reference(sourceFile, sourceStartOffset, sourceEndOffset, targetStartLine, targetEndLine)
        
        LOG.info("Adding reference from ${sourceFile.path} to $targetPath:$targetStartLine-$targetEndLine")
        
        // Get or create the map for this target file
        val fileReferences = referenceMap.computeIfAbsent(targetPath) { ConcurrentHashMap() }
        
        // Get or create the list for this line range
        val references = fileReferences.computeIfAbsent(lineRange) { mutableListOf() }
        
        // Check if this reference already exists to avoid duplicates
        val existingReference = references.find { 
            it.sourceFile.path == sourceFile.path && 
            it.startLine == targetStartLine && 
            it.endLine == targetEndLine 
        }
        
        if (existingReference == null) {
            // Only add if it doesn't already exist
            references.add(reference)
            LOG.info("Added new reference from ${sourceFile.path} to $targetPath:$targetStartLine-$targetEndLine")
        } else {
            LOG.info("Reference from ${sourceFile.path} to $targetPath:$targetStartLine-$targetEndLine already exists, skipping")
        }
    }
    
    /**
     * Find all references to a specific location in a file
     */
    fun findReferencesToLocation(file: VirtualFile, lineNumber: Int): List<Reference> {
        val filePath = file.path
        val fileReferences = referenceMap[filePath]
        
        LOG.info("Finding references to $filePath:$lineNumber")
        
        if (fileReferences == null) {
            LOG.info("No references found for file: $filePath")
            return emptyList()
        }
        
        LOG.info("File has ${fileReferences.size} line ranges with references")
        
        val result = mutableListOf<Reference>()
        
        // Check all line ranges for this file
        for ((lineRange, references) in fileReferences) {
            LOG.info("Checking line range ${lineRange.startLine}-${lineRange.endLine} for line $lineNumber")
            
            // Check if the current line is within the referenced range
            if (lineNumber >= lineRange.startLine && lineNumber <= lineRange.endLine) {
                LOG.info("Line $lineNumber is within range ${lineRange.startLine}-${lineRange.endLine}, adding ${references.size} references")
                result.addAll(references)
            }
        }
        
        LOG.info("Found ${result.size} references to $filePath:$lineNumber")
        
        return result
    }
    
    /**
     * Get all line ranges for a specific file
     */
    fun getAllLineRangesForFile(file: VirtualFile): List<LineRange> {
        val filePath = file.path
        val fileReferences = referenceMap[filePath]
        
        if (fileReferences == null) {
            LOG.info("No references found for file: $filePath")
            return emptyList()
        }
        
        return fileReferences.keys.toList()
    }
    
    /**
     * Get all files that have references in the index
     */
    fun getAllFilesWithReferences(): Set<String> {
        return referenceMap.keys
    }
    
    /**
     * Remove all references from a specific file
     */
    fun removeReferencesFromFile(file: VirtualFile) {
        val filePath = file.path
        LOG.info("Removing all references from file: $filePath")
        
        // Remove references where this file is the source
        for ((targetPath, lineRanges) in referenceMap) {
            for ((lineRange, references) in lineRanges.toMap()) {
                // Create a new list without references from this file
                val newReferences = references.filterNot { it.sourceFile.path == filePath }.toMutableList()
                
                if (newReferences.isEmpty()) {
                    // If no references left, remove the line range
                    lineRanges.remove(lineRange)
                } else {
                    // Otherwise, update the references
                    lineRanges[lineRange] = newReferences
                }
            }
            
            // If no line ranges left, remove the file entry
            if (lineRanges.isEmpty()) {
                referenceMap.remove(targetPath)
            }
        }
        
        // Remove references where this file is the target
        referenceMap.remove(filePath)
    }
    
    /**
     * Clear the entire index
     */
    fun clear() {
        LOG.info("Clearing reference index")
        referenceMap.clear()
    }
    
    /**
     * Represents a range of lines in a file
     */
    data class LineRange(val startLine: Int, val endLine: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LineRange) return false
            return startLine == other.startLine && endLine == other.endLine
        }
        
        override fun hashCode(): Int {
            return 31 * startLine + endLine
        }
    }
    
    /**
     * Represents a reference from one file to another
     */
    data class Reference(
        val sourceFile: VirtualFile,
        val startOffset: Int,
        val endOffset: Int,
        val startLine: Int = 0,  // Store the target start line
        val endLine: Int = 0     // Store the target end line
    )
}
