package org.kotlinlsp.actions.hover

import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.kotlinlsp.common.findSourceSymbols
import org.kotlinlsp.common.getElementRange
import org.kotlinlsp.common.info
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.index.Index
import kotlin.text.iterator

@OptIn(KaExperimentalApi::class)
private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES
private val formatter = DocFormatter()

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
    text = "```kotlin\n${formattedText(text)}\n```"
    if(docText.isNotEmpty()){
        text = "$text\n\n---\n\n${formatter.formatDoc(docText)}\n"
    }
    return Pair(text, range)
}

private fun formattedText(signature: String, indent: String = "    "): String {
        val trimmed = signature.trim()
        val openParenIndex = trimmed.indexOf('(')
        val closeParenIndex = trimmed.lastIndexOf(')')
        if (openParenIndex <= 0 || closeParenIndex < openParenIndex) return signature

        val header = trimmed.substring(0, openParenIndex).trimEnd()
        val paramsRaw = trimmed.substring(openParenIndex + 1, closeParenIndex)
        val returnType = trimmed.substring(closeParenIndex).trimStart()

        // Split on commas, but skip commas inside <...> or (...) nests
        val params = mutableListOf<String>()
        val current = StringBuilder()
        var angle = 0
        var paren = 0
        for (ch in paramsRaw) {
            when (ch) {
                '<' -> { angle++; current.append(ch) }
                '>' -> { angle--; current.append(ch) }
                '(' -> { paren++; current.append(ch) }
                ')' -> { paren--; current.append(ch) }
                ',' -> if (angle == 0 && paren == 0) {
                    params.add(current.toString().trim()); current.clear()
                } else current.append(ch)
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) params.add(current.toString().trim())

        return buildString {
            append("$header(")
            if (params.isNotEmpty()) {
                appendLine()
                params.forEachIndexed { i, p ->
                    append(indent).append(p)
                    if (i != params.lastIndex) appendLine(",") else appendLine()
                }
            }
            append(returnType)
        }
}
