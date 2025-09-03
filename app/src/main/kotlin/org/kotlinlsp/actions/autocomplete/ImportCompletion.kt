package org.kotlinlsp.actions.autocomplete

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.file
import org.kotlinlsp.index.queries.subpackageNames

fun autoCompletionImport(ktFile: KtFile, offset: Int, index: Index, leaf: PsiElement): Sequence<CompletionItem> {
    val basePackage = leaf.text.substringAfter("import ").removeSuffix(".")
    val packageItems = index
        .subpackageNames(basePackage)
        .asSequence()
        .map { segment ->
            CompletionItem().apply {
                label = segment
                kind = CompletionItemKind.Module
                insertText = segment
                additionalTextEdits = emptyList()
            }
        }

    val declarationItems = index.query { db ->
        val files = db.packagesDb.get<List<String>>(basePackage) ?: emptyList()
        files.asSequence()
            .mapNotNull { path -> db.file(path) }
            .flatMap { file -> file.declarationKeys.asSequence() }
            .mapNotNull { key -> db.declarationsDb.get<Declaration>(key) }
            .filter{
                if(it is Declaration.Function){
                    it.isTopLevel
                } else if (it is Declaration.Field) {
                    it.isTopLevel
                } else if (it is Declaration.Class){
                    it.isTopLevel
                } else {
                    true
                }
            }
            .distinctBy { it.name }
            .map { decl ->
                CompletionItem().apply {
                    label = decl.name
                    labelDetails = decl.details()
                    kind = decl.completionKind()
                    insertText = decl.name
                    additionalTextEdits = emptyList()
                }
            }
    }

    return packageItems + declarationItems
}

//private fun parseQualifiedPrefixForImport(ktFile: KtFile, offset: Int): Pair<String, String> {
//    val leaf = ktFile.findElementAt(offset)
//    val directive = leaf?.parentOfType<KtImportDirective>(withSelf = true)
//    val start = directive?.textRange?.startOffset ?: return "" to ""
//    if (offset < start) return "" to ""
//    val before = ktFile.text.substring(start, offset)
//    val typed = before
//        .substringAfter("import", "")
//        .substringBefore(" as")
//        .trim()
//        .trimEnd('*')
//        .trim()
//    return splitBaseAndPrefix(typed)
//}
//
//private fun splitBaseAndPrefix(typed: String): Pair<String, String> {
//    if (typed.isBlank()) return "" to ""
//    return if (typed.endsWith('.')) {
//        typed.removeSuffix(".") to ""
//    } else {
//        val idx = typed.lastIndexOf('.')
//        if (idx == -1) "" to typed else typed.substring(0, idx) to typed.substring(idx + 1)
//    }
//}


