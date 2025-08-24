package org.kotlinlsp.actions

import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.kotlinlsp.common.findSourceSymbols
import org.kotlinlsp.common.getElementRange
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.index.Index

@OptIn(KaExperimentalApi::class)
private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES

@OptIn(KaExperimentalApi::class)
fun hoverAction(ktFile: KtFile, position: Position, index: Index): Pair<String, Range>? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    val range = getElementRange(ktFile, ktElement)
    val sourceSymbols = analyze(ktFile) {
        if(ktElement.mainReference != null) {
            val sourceSymbols = findSourceSymbols(ktFile, ktElement.mainReference!!, index);
            return@analyze sourceSymbols
        }
        return@analyze emptyList()
    }
    var docText = ""
    if (sourceSymbols.isNotEmpty()) {
        val kdoc = sourceSymbols.distinct().first().allChildren.toList().find { ele -> ele is KDoc || ele is PsiDocComment }
        if (kdoc != null) {
            docText = kdoc.text
        }
    }
    val symbol = analyze(ktElement){
        (
        if (ktElement is KtDeclaration) ktElement.symbol
        else ktElement.mainReference?.resolveToSymbols()?.first() as? KaDeclarationSymbol ?: return null
        ).createPointer()
    }
    var text = analyze(ktElement){
        symbol.restoreSymbol()!!.render(renderer)
    }
    if(docText.isEmpty()){
        analyze(ktElement){
            docText = symbol.restoreSymbol()!!.psi?.allChildren?.toList()?.find { ele -> ele is KDoc }?.text ?: ""
        }
    }
    if(docText.isNotEmpty()){
        text = docText + "\n\n" + text
    }
    return Pair(text, range)
}
