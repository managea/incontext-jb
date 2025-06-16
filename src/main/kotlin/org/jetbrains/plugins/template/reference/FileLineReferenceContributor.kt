package org.jetbrains.plugins.template.reference

import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.StandardPatterns
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.tree.LeafPsiElement

/**
 * Contributes the FileLineReferenceProvider to detect file references in text files
 */
class FileLineReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // Register our provider for leaf PSI elements (text)
        // This will enable detection in all types of files including plain text
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(LeafPsiElement::class.java)
                .withText(StandardPatterns.string().contains("@")),
            FileLineReferenceProvider()
        )
    }
}
