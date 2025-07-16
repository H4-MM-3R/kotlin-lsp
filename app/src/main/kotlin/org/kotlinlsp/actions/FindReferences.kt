package org.kotlinlsp.actions

import com.intellij.openapi.vfs.VirtualFileManager
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
import kotlinx.coroutines.*
import org.kotlinlsp.common.normalizeUri
import java.util.concurrent.ConcurrentLinkedQueue

private data class SymbolData(
    val pointer: KaSymbolPointer<KaDeclarationSymbol>,
    val name: String
)

private val referenceDispatcher by lazy {
    val cores = Runtime.getRuntime().availableProcessors()
    val parallelism = (cores * 2 / 3).coerceAtMost(8)
    Dispatchers.Default.limitedParallelism(parallelism)
}

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
    val references = ConcurrentLinkedQueue<Location>()

    val filesToSearch = getFilesToSearch(index, targetSymbolName)

    runBlocking {
        filesToSearch.map { file ->
            async(referenceDispatcher) {
                try {
                    val fileReferences = findReferencesInFile(file, targetSymbolPointer)
                    references.addAll(fileReferences)
                } catch (e: Exception) {
                    // Silently ignore resolution errors for robustness
                }
            }
        }.awaitAll()
    }

    return references.toList().ifEmpty { null }
}

/**
 * Get list of files to search, pre-filtered by symbol name
 */
private fun getFilesToSearch(index: Index, symbolName: String): List<KtFile> {
    val filesMap = mutableMapOf<String, KtFile>()

    index.openedKtFiles.forEach { (_, file) ->
        filesMap[file.virtualFile.path] = file
    }

    val candidateFiles = index.query { db ->
        db.declarationsDb.prefixSearch<Declaration>(symbolName)
            .map { (_, declaration) -> declaration.file }
            .toSet()
    }

    candidateFiles.forEach { filePath ->
        val virtualFile = try {
            VirtualFileManager.getInstance().findFileByUrl(filePath)
        } catch (e: Exception) {
            null
        }

        virtualFile?.let { vf ->
            index.getKtFile(vf)?.let { ktFile ->
                filesMap[vf.path] = ktFile
            }
        }
    }


    return filesMap.values.toList()
}

/**
 * Find references in a single file using batched analysis
 */
private fun findReferencesInFile(ktFile: KtFile, targetSymbolPointer: KaSymbolPointer<KaDeclarationSymbol>): List<Location> {
    val references = mutableListOf<Location>()
    
    analyze(ktFile) {
        val targetSymbol = targetSymbolPointer.restoreSymbol() ?: return@analyze
        val referenceExpressions = ktFile.collectDescendantsOfType<KtReferenceExpression>()
        
        for (refExpr in referenceExpressions) {
            try {
                val resolvedSymbol = refExpr.mainReference.resolveToSymbol() as? KaDeclarationSymbol
                if (resolvedSymbol != null && resolvedSymbol == targetSymbol) {
                    references.add(Location().apply {
                        uri = ktFile.virtualFile.url.normalizeUri()
                        range = refExpr.textRange.toLspRange(ktFile)
                    })
                }
            } catch (e: Exception) {
                // Silently ignore resolution errors for individual references
            }
        }
        
        // Check declarations too (the declaration itself is also a "reference")
        val declarations = ktFile.collectDescendantsOfType<KtDeclaration>()
        for (decl in declarations) {
            try {
                val declSymbol = decl.symbol as? KaDeclarationSymbol
                if (declSymbol != null && declSymbol == targetSymbol) {
                    references.add(Location().apply {
                        uri = ktFile.virtualFile.url.normalizeUri()
                        range = decl.textRange.toLspRange(ktFile)
                    })
                }
            } catch (e: Exception) {
                // Silently ignore resolution errors for individual declarations
            }
        }
    }
    
    return references
}
