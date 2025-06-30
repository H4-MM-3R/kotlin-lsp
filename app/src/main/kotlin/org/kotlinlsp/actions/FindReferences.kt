package org.kotlinlsp.actions

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.db.adapters.prefixSearch

private data class SymbolData(
    val pointer: KaSymbolPointer<KaDeclarationSymbol>,
    val name: String
)

fun findReferencesAction(ktFile: KtFile, position: Position, index: Index): List<Location>? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    
    // Get the symbol at the current position and create a pointer for efficient comparison
    val targetData = analyze(ktElement) {
        val symbol = when (ktElement) {
            is KtDeclaration -> ktElement.symbol
            else -> ktElement.mainReference?.resolveToSymbol() as? KaDeclarationSymbol
        } ?: return@analyze null
        
        val symbolPointer = symbol.createPointer()
        // Get symbol name for pre-filtering files
        val symbolName = when (ktElement) {
            is KtDeclaration -> ktElement.name
            else -> ktElement.text
        } ?: return@analyze null
        
        SymbolData(symbolPointer, symbolName)
    } ?: return null
    
    val targetSymbolPointer = targetData.pointer
    val targetSymbolName = targetData.name
    
    // Get deduplicated list of files to search, using virtualFile.path as the key
    val filesToSearch = getFilesToSearch(index, targetSymbolName)
    
    // Search for references in each file (could be parallelized with coroutines in the future)
    val references = mutableListOf<Location>()
    filesToSearch.forEach { file ->
        try {
            val fileReferences = findReferencesInFile(file, targetSymbolPointer)
            references.addAll(fileReferences)
        } catch (e: Exception) {
            // Silently ignore resolution errors for robustness
        }
    }
    
    return references.ifEmpty { null }
}

/**
 * Get deduplicated list of files to search, pre-filtered by symbol name
 */
private fun getFilesToSearch(index: Index, symbolName: String): List<KtFile> {
    // Use a map with virtualFile.path as key to automatically deduplicate
    val filesMap = mutableMapOf<String, KtFile>()
    
    // Add all opened files
    index.openedKtFiles.forEach { (_, file) ->
        filesMap[file.virtualFile.path] = file
    }
    
    // Name pre-filter: Find files that contain declarations with the target symbol name
    val candidateFiles = index.query { db ->
        db.declarationsDb.prefixSearch<Declaration>(symbolName)
            .map { (_, declaration) -> declaration.file }
            .toSet()
    }
    
    // Add files that potentially contain the symbol
    candidateFiles.forEach { filePath ->
        val virtualFile = try {
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(filePath)
        } catch (e: Exception) {
            null
        }
        
        virtualFile?.let { vf ->
            index.getKtFile(vf)?.let { ktFile ->
                filesMap[vf.path] = ktFile
            }
        }
    }
    
    // Add all source files from the project as fallback for cases where the symbol
    // might not be indexed yet (e.g., just added) or for implicit references
    index.getAllSourceKtFiles().forEach { file ->
        filesMap[file.virtualFile.path] = file
    }
    
    return filesMap.values.toList()
}

/**
 * Find references in a single file using batched analysis
 */
private fun findReferencesInFile(ktFile: KtFile, targetSymbolPointer: KaSymbolPointer<KaDeclarationSymbol>): List<Location> {
    val references = mutableListOf<Location>()
    
    // Single analyze session for the entire file
    analyze(ktFile) {
        // Collect all reference expressions in the file
        val referenceExpressions = ktFile.collectDescendantsOfType<KtReferenceExpression>()
        
        for (refExpr in referenceExpressions) {
            try {
                val resolvedSymbol = refExpr.mainReference.resolveToSymbol() as? KaDeclarationSymbol
                if (resolvedSymbol != null) {
                    val resolvedPointer = resolvedSymbol.createPointer()
                    // Compare symbol pointers by restoring and comparing symbols
                    val targetSymbol = targetSymbolPointer.restoreSymbol()
                    val resolvedRestoredSymbol = resolvedPointer.restoreSymbol()
                    if (targetSymbol != null && resolvedRestoredSymbol != null && targetSymbol == resolvedRestoredSymbol) {
                        val location = Location().apply {
                            uri = ktFile.virtualFile.url
                            range = refExpr.textRange.toLspRange(ktFile)
                        }
                        references.add(location)
                    }
                }
            } catch (e: Exception) {
                // Silently ignore resolution errors for individual references
            }
        }
        
        // Check declarations too (the declaration itself is also a "reference")
        val declarations = ktFile.collectDescendantsOfType<KtDeclaration>()
        for (decl in declarations) {
            try {
                val declSymbol = decl.symbol
                if (declSymbol != null) {
                    val declPointer = declSymbol.createPointer()
                    // Compare symbol pointers by restoring and comparing symbols
                    val targetSymbol = targetSymbolPointer.restoreSymbol()
                    val declRestoredSymbol = declPointer.restoreSymbol()
                    if (targetSymbol != null && declRestoredSymbol != null && targetSymbol == declRestoredSymbol) {
                        val location = Location().apply {
                            uri = ktFile.virtualFile.url
                            range = decl.textRange.toLspRange(ktFile)
                        }
                        references.add(location)
                    }
                }
            } catch (e: Exception) {
                // Silently ignore resolution errors for individual declarations
            }
        }
    }
    
    return references
} 