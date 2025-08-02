package org.kotlinlsp.actions

import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.kotlinlsp.analysis.services.DirectInheritorsProvider
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.common.normalizeUri
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.db.adapters.prefixSearch
import kotlinx.coroutines.*
import org.kotlinlsp.common.CustomDispatcher
import java.util.concurrent.ConcurrentHashMap

fun renameAction(ktFile: KtFile, position: Position, newName: String, index: Index): WorkspaceEdit? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    
    val targetData = analyze(ktElement) {
        val symbol = when (ktElement) {
            is KtDeclaration -> ktElement.symbol
            else -> ktElement.mainReference?.resolveToSymbol() as? KaDeclarationSymbol
        } ?: return@analyze null
        
        val symbolPointer = symbol.createPointer()
        val symbolName = when (ktElement) {
            is KtDeclaration -> ktElement.name
            else -> ktElement.text
        } ?: return@analyze null
        
        Pair(symbolPointer, symbolName)
    } ?: return null
    
    val targetSymbolPointer = targetData.first
    val targetSymbolName = targetData.second
    
    val filesToSearch = getFilesToSearch(index, targetSymbolName)
    val documentChanges = ConcurrentHashMap<String, MutableList<TextEdit>>()
    
    runBlocking {
        filesToSearch.map { file ->
            async(CustomDispatcher.cpu) {
                try {
                    val fileEdits = findRenameOccurrencesInFile(file, targetSymbolPointer, newName)
                    if (fileEdits.isNotEmpty()) {
                        documentChanges[file.virtualFile.url.normalizeUri()] = fileEdits.toMutableList()
                    }
                } catch (e: Exception) {
                    // Silently ignore resolution errors for robustness
                }
            }
        }.awaitAll()
    }
    
    // Also find and rename implementations/overrides if this is a callable symbol
    try {
        val implementations = findImplementations(ktFile, ktElement, targetSymbolPointer, newName)
        implementations.forEach { (fileUrl, edits) ->
            val normalizedFileUrl = fileUrl.normalizeUri()
            if (documentChanges.containsKey(normalizedFileUrl)) {
                documentChanges[normalizedFileUrl]!!.addAll(edits)
            } else {
                documentChanges[normalizedFileUrl] = edits.toMutableList()
            }
        }
    } catch (e: Exception) {
        // Silently ignore implementation finding errors
    }
    
    if (documentChanges.isEmpty()) {
        return null
    }
    
    return WorkspaceEdit().apply {
        changes = documentChanges
    }
}

/**
 * Find implementations/overrides of the target symbol
 */
private fun findImplementations(ktFile: KtFile, ktElement: KtElement, targetSymbolPointer: KaSymbolPointer<KaDeclarationSymbol>, newName: String): Map<String, List<TextEdit>> {
    val directInheritorsProvider = ktFile.project.getService(KotlinDirectInheritorsProvider::class.java) as DirectInheritorsProvider
    val module = KotlinProjectStructureProvider.getModule(ktFile.project, ktElement, useSiteModule = null)
    val scope = ProjectScope.getContentScope(ktFile.project)
    val documentChanges = mutableMapOf<String, MutableList<TextEdit>>()
    
    // Check if this is a callable symbol that can be overridden
    val callableData = analyze(ktElement) {
        val symbol = targetSymbolPointer.restoreSymbol() as? KaCallableSymbol ?: return@analyze null
        val classSymbol = symbol.containingSymbol as? KaClassSymbol ?: return@analyze null
        val containingClassId = classSymbol.classId ?: return@analyze null
        val callableId = symbol.callableId ?: return@analyze null
        
        Triple(symbol.createPointer(), callableId, containingClassId)
    } ?: return emptyMap()
    
    val (baseCallablePtr, baseCallableId, containingClassId) = callableData
    val baseShortName = baseCallableId.callableName
    
    // Get distinct inheritors by classId to avoid processing duplicates
    val distinctInheritors = directInheritorsProvider
        .getDirectKotlinInheritorsByClassId(containingClassId, module, scope, true)
        .distinctBy { it.getClassId() }
    
    // Search for overriding declarations using single analysis session per inheritor
    distinctInheritors.forEach { ktClass ->
        try {
            analyze(ktClass) {
                // Restore the base callable symbol once per analysis session
                val baseCallableSymbol = baseCallablePtr.restoreSymbol() ?: return@analyze
                
                // Find declarations in this class that match the callable name (pre-filter)
                val matchingDeclarations = ktClass.declarations.filter { declaration ->
                    declaration.name == baseShortName.asString()
                }
                
                // Check which of the matching declarations actually overrides the base callable
                matchingDeclarations.forEach { declaration ->
                    val declarationSymbol = declaration.symbol as? KaCallableSymbol ?: return@forEach
                    
                    // Use built-in override checking
                    val isOverride = declarationSymbol.directlyOverriddenSymbols.any { overriddenSymbol ->
                        // Check if this symbol is the same as our base callable
                        overriddenSymbol.callableId == baseCallableId
                    }
                    
                    if (isOverride && declaration.name != null) {
                        // Find the name identifier within the declaration text range
                        val nameStart = declaration.textRange.startOffset + declaration.text.indexOf(declaration.name!!)
                        val nameEnd = nameStart + declaration.name!!.length
                        val nameRange = com.intellij.openapi.util.TextRange(nameStart, nameEnd)
                        
                        val edit = TextEdit().apply {
                            range = nameRange.toLspRange(declaration.containingFile)
                            this.newText = newName
                        }
                        
                        val fileUrl = declaration.containingFile.virtualFile.url.normalizeUri()
                        if (!documentChanges.containsKey(fileUrl)) {
                            documentChanges[fileUrl] = mutableListOf()
                        }
                        documentChanges[fileUrl]!!.add(edit)
                    }
                }
            }
        } catch (e: Exception) {
            // Silently ignore analysis errors for robustness
        }
    }
    
    return documentChanges
}

/**
 * Get list of files to search, pre-filtered by symbol name
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
    

    return filesMap.values.toList()
}

/**
 * Find rename occurrences in a single file using batched analysis
 */
private fun findRenameOccurrencesInFile(ktFile: KtFile, targetSymbolPointer: KaSymbolPointer<KaDeclarationSymbol>, newName: String): List<TextEdit> {
    val edits = mutableListOf<TextEdit>()
    
    analyze(ktFile) {
        val targetSymbol = targetSymbolPointer.restoreSymbol() ?: return@analyze
        val referenceExpressions = ktFile.collectDescendantsOfType<KtReferenceExpression>()
        
        for (refExpr in referenceExpressions) {
            try {
                val resolvedSymbol = refExpr.mainReference.resolveToSymbol() as? KaDeclarationSymbol
                if (resolvedSymbol != null && resolvedSymbol == targetSymbol) {
                    edits.add(TextEdit().apply {
                        range = refExpr.textRange.toLspRange(ktFile)
                        this.newText = newName
                    })
                }
            } catch (e: Exception) {
                // Silently ignore resolution errors for individual references
            }
        }
        
        // Check declarations too (the declaration itself also needs to be renamed)
        val declarations = ktFile.collectDescendantsOfType<KtDeclaration>()
        for (decl in declarations) {
            try {
                val declSymbol = decl.symbol as? KaDeclarationSymbol
                if (declSymbol != null && declSymbol == targetSymbol && decl.name != null) {
                    val nameStart = decl.textRange.startOffset + decl.text.indexOf(decl.name!!)
                    val nameEnd = nameStart + decl.name!!.length
                    val nameRange = com.intellij.openapi.util.TextRange(nameStart, nameEnd)
                    
                    edits.add(TextEdit().apply {
                        range = nameRange.toLspRange(ktFile)
                        this.newText = newName
                    })
                }
            } catch (e: Exception) {
                // Silently ignore resolution errors for individual declarations
            }
        }
    }
    
    return edits
} 
