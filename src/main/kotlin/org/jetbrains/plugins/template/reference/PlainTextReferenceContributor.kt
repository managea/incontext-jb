package org.jetbrains.plugins.template.reference

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPlainText
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext

/**
 * A specific reference contributor for plain text files to ensure references are detected
 */
class PlainTextReferenceContributor : PsiReferenceContributor() {

    companion object {
        private val LOG = Logger.getInstance(PlainTextReferenceContributor::class.java)
    }

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        LOG.info("Registering reference provider for plain text files")

        // Specific pattern for plain text elements
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiPlainText::class.java),
            FileLineReferenceProvider()
        )

        // Also register for any text in any language
        val languages = Language.getRegisteredLanguages()
        for (language in languages) {
            LOG.info("Registering reference provider for language: ${language.displayName}")
            registrar.registerReferenceProvider(
                PlatformPatterns.psiElement().withLanguage(language),
                FileLineReferenceProvider()
            )
        }
    }
}
