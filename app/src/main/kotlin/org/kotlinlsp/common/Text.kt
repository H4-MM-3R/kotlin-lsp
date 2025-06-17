package org.kotlinlsp.common

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

fun Position.toOffset(ktFile: KtFile): Int = StringUtil.lineColToOffset(ktFile.text, line, character)

fun TextRange.toLspRange(ktFile: PsiFile): Range {
    val text = ktFile.text
    val lineColumnStart = StringUtil.offsetToLineColumn(text, startOffset)
    val lineColumnEnd = StringUtil.offsetToLineColumn(text, endOffset)

    return Range(
        Position(lineColumnStart.line, lineColumnStart.column),
        Position(lineColumnEnd.line, lineColumnEnd.column)
    )
}

fun getElementRange(ktFile: KtFile, element: KtElement): Range {
    val document = ktFile.viewProvider.document
    val textRange = element.textRange
    val startOffset = textRange.startOffset
    val endOffset = textRange.endOffset
    val start = document.getLineNumber(startOffset).let { line ->
        Position(line, startOffset - document.getLineStartOffset(line))
    }
    val end = document.getLineNumber(endOffset).let { line ->
        Position(line, endOffset - document.getLineStartOffset(line))
    }
    return Range(start, end)
}

fun Int.toLspPosition(ktFile: KtFile): Position {
    var lineColumn = StringUtil.offsetToLineColumn(ktFile.text, this)
    return Position(lineColumn.line, lineColumn.column)
}

fun Int.toOffset(ktFile: KtFile): Int = this
