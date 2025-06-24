package org.kotlinlsp.actions

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.index.Index

fun findReferencesAction(ktFile: KtFile, position: Position, index: Index): List<Location>? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    
    // Get the symbol at the current position
    val targetSymbol = analyze(ktElement) {
        when (ktElement) {
            is KtDeclaration -> ktElement.symbol
            else -> ktElement.mainReference?.resolveToSymbol() as? KaDeclarationSymbol
        } ?: return null
    } ?: return null
    
    val references = mutableListOf<Location>()
    
    // Search through all opened files and files in the project
    val filesToSearch = mutableSetOf<KtFile>()
    
    // Add all opened files
    index.openedKtFiles.forEach { (_, file) ->
        filesToSearch.add(file)
    }
    
    // Add all source files from the project
    index.getAllSourceKtFiles().forEach { file ->
        filesToSearch.add(file)
    }
    
    // Search for references in each file
    filesToSearch.forEach { file ->
        val fileReferences = findReferencesInFile(file, targetSymbol)
        references.addAll(fileReferences)
    }
    
    return references.ifEmpty { null }
}

private fun findReferencesInFile(ktFile: KtFile, targetSymbol: KaDeclarationSymbol): List<Location> {
    val references = mutableListOf<Location>()
    
    // Collect all reference expressions in the file
    val referenceExpressions = ktFile.collectDescendantsOfType<KtReferenceExpression>()
    
    for (refExpr in referenceExpressions) {
        try {
            analyze(refExpr) {
                val resolvedSymbol = refExpr.mainReference.resolveToSymbol() as? KaDeclarationSymbol
                if (resolvedSymbol != null && symbolsAreEqual(resolvedSymbol, targetSymbol)) {
                    val location = Location().apply {
                        uri = ktFile.virtualFile.url
                        range = refExpr.textRange.toLspRange(ktFile)
                    }
                    references.add(location)
                }
            }
        } catch (e: Exception) {
            // Silently ignore resolution errors for robustness
            // In production, you might want to log these for debugging
        }
    }
    
    // Also check for declaration references (the declaration itself)
    val declarations = ktFile.collectDescendantsOfType<KtDeclaration>()
    for (decl in declarations) {
        try {
            analyze(decl) {
                val declSymbol = decl.symbol
                if (declSymbol != null && symbolsAreEqual(declSymbol, targetSymbol)) {
                    val location = Location().apply {
                        uri = ktFile.virtualFile.url
                        range = decl.textRange.toLspRange(ktFile)
                    }
                    references.add(location)
                }
            }
        } catch (e: Exception) {
            // Silently ignore resolution errors for robustness
        }
    }
    
    return references
}

private fun symbolsAreEqual(symbol1: KaDeclarationSymbol, symbol2: KaDeclarationSymbol): Boolean {
    // Compare symbols by their IDs/signatures  
    // The safest way is to compare the pointer/symbol identity within the same analysis session
    // However, since we're comparing across different analysis sessions, we need to use
    // more stable identifiers like the containing declaration and psi elements
    if (symbol1 == symbol2) return true
    
    // Try to compare by their PSI locations if available
    val psi1 = symbol1.psi
    val psi2 = symbol2.psi
    if (psi1 != null && psi2 != null) {
        return psi1 == psi2 || 
               (psi1.containingFile == psi2.containingFile && 
                psi1.textOffset == psi2.textOffset)
    }
    
    return false
} 