package com.asanga.incontext.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.util.regex.Pattern

/**
 * Utility class for file reference operations
 */
object FileReferenceUtil {
    private val LOG = Logger.getInstance(FileReferenceUtil::class.java)

    val FILE_REFERENCE_PATTERN = Pattern.compile(
        "@([\\w-]+/[\\w\\-./()]+):L(\\d+)-(\\d+)",
        Pattern.CASE_INSENSITIVE
    )
    /**
     * Data class to hold the parsed reference components
     */
    data class ParsedReference(
        val moduleName: String,
        val relativePath: String,
        val startLine: Int,
        val endLine: Int
    )

    /**
     * Parse a reference in the format: @project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number}
     * or project-name/path-from-project-root/file-name:L{from-line-number}-{to-line-number} (without @ prefix)
     */
    fun parseReference(ref: String): ParsedReference? {
        try {
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
            val moduleName = pathComponents[0]

            // Get the relative path within the project
            val relativePath = if (pathComponents.size > 1) pathComponents[1] else ""

            return ParsedReference(moduleName, relativePath, startLine, endLine)
        } catch (e: Exception) {
            LOG.warn("Failed to parse reference: $ref", e)
            return null
        }
    }

    fun findFileInProject(project: Project, moduleName: String, filePath: String): VirtualFile? {
        // Get all project modules
        val moduleManager = ModuleManager.getInstance(project)
        val modules = moduleManager.modules

        // Try to find the file in each module
        for (module in modules) {
            if (module.name == moduleName) {
                // This is the module we're looking for
                val moduleRootManager = ModuleRootManager.getInstance(module)
                val contentRoots = moduleRootManager.contentRoots

                // Check each content root
                for (contentRoot in contentRoots) {
                    val fullPath = "${contentRoot.path}/$filePath"
                    val file = VirtualFileManager.getInstance().findFileByUrl("file://$fullPath")
                    if (file != null && file.exists()) {
                        return file
                    }
                }
            }
        }

        // Try to find the file relative to the project root
        val projectBasePath = project.basePath
        if (projectBasePath != null) {
            val fullPath = "$projectBasePath/$moduleName/$filePath"
            val file = VirtualFileManager.getInstance().findFileByUrl("file://$fullPath")
            if (file != null && file.exists()) {
                return file
            }
        }

        // Try to find the file by name
        val fileName = File(filePath).name
        val filesByName = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
        if (filesByName.isNotEmpty()) {
            // If there's only one file with this name, return it
            if (filesByName.size == 1) {
                return filesByName.first()
            }

            // Otherwise, try to find the best match
            for (file in filesByName) {
                val path = file.path
                if (path.contains(filePath)) {
                    return file
                }
            }

            // If no match found, return the first one
            return filesByName.first()
        }

        return null
    }
}
