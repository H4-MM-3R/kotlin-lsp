package org.kotlinlsp.actions.autocomplete

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.leavesAroundOffset
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.jetbrains.kotlin.psi.*
import org.kotlinlsp.index.Index

fun autocompleteAction(ktFile: KtFile, offset: Int, index: Index): Sequence<CompletionItem> {
    var leaf = ktFile.findElementAt(offset)
    if(leaf == null) return emptySequence()
    while(leaf?.text == "" || leaf is PsiWhiteSpace){
        leaf = leaf.prevSibling ?: break
    }

    if(leaf is KtProperty){
        leaf = leaf.children.find { it is KtNameReferenceExpression } ?: leaf
    }

    val prefix = leaf.text.substring(0, offset - leaf.textRange.startOffset)
    val completingElement = leaf.parentOfType<KtElement>() ?: ktFile
    if (completingElement is KtNameReferenceExpression) {
        return if (completingElement.parent is KtDotQualifiedExpression) {
            autoCompletionDotExpression(ktFile, offset, index, completingElement.parent as KtDotQualifiedExpression, prefix)
        } else {
            autoCompletionGeneric(ktFile, offset, index, completingElement, prefix)
        }
    }

    if (completingElement is KtFile) {
        if(leaf is KtPackageDirective){
            return autoCompletionPackage(ktFile, offset, index, leaf)
        }
        return autocompletionCommonCompletion()
    }

    if (completingElement is KtImportList){
        return autoCompletionImport(ktFile, offset, index, leaf)
    }

    if (completingElement is KtValueArgumentList) {
        return emptySequence() // TODO: function call arguments
    }

    return autoCompletionGeneric(ktFile, offset, index, completingElement, prefix)
}
