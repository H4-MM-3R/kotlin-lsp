package org.kotlinlsp.common

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.psi.KtElement
import java.nio.file.Paths
import java.net.URI
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
    val lineColumn = StringUtil.offsetToLineColumn(ktFile.text, this)
    return Position(lineColumn.line, lineColumn.column)
}

fun Int.toOffset(ktFile: KtFile): Int = this

fun String.normalizeUri(): String {
    return try {
        val uri = URI(this)
        if (uri.scheme == "file") {
            val raw = Paths.get(uri).toUri().toString()
            raw.fixWindowsFileUri()
        } else {
            this
        }
    } catch (e: Exception) {
        info("normalizeUri failed on ‘$this’, returning original. reason: ${e.message}")
        this
    }
}

private fun String.fixWindowsFileUri(): String {
    if (!startsWith("file://") || startsWith("file:///")) return this

    val pathPart = removePrefix("file://")
    return when {
        pathPart.matches("^[a-zA-Z]:/.*".toRegex()) ->
            "file:///$pathPart"

        pathPart.matches("^[a-zA-Z]/.*".toRegex()) -> {
            val drive = pathPart.first()
            val rest  = pathPart.drop(1)
            "file:///$drive:$rest"
        }
        else -> this
    }
}
