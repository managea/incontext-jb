package org.jetbrains.plugins.template.startup

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.jetbrains.plugins.template.navigation.FileReferenceIndexer

/**
 * Startup activity to initialize the file reference indexer
 */
class FileReferenceStartupActivity : StartupActivity {
    
    companion object {
        private val LOG = Logger.getInstance(FileReferenceStartupActivity::class.java)
    }
    
    override fun runActivity(project: Project) {
        LOG.info("Initializing file reference indexer")
        
        // Get the indexer service and index the project
        val indexer = FileReferenceIndexer.getInstance(project)
        indexer.indexProject()
    }
}
