package org.kotlinlsp.actions.autocomplete

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.subpackageNames

fun autoCompletionPackage(ktFile: KtFile, offset: Int, index: Index, leaf: PsiElement): Sequence<CompletionItem> {
    val basePackage = leaf.text.substringAfter("package ").removeSuffix(".")
    return index
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
}