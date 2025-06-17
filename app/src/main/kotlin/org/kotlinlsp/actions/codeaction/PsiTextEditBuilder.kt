package org.kotlinlsp.actions.codeaction

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.common.toLspPosition
import org.kotlinlsp.common.toLspRange

/**
 * Utility class for creating LSP TextEdits from PSI manipulations
 */
class PsiTextEditBuilder {

    /**
     * Create a TextEdit that replaces the given element with new text
     */
    fun replaceElement(element: KtElement, newText: String): TextEdit {
        val range = element.textRange.toLspRange(element.containingKtFile)
        return TextEdit(range, newText)
    }

    /**
     * Create a TextEdit that inserts text at a specific position
     */
    fun insertAt(ktFile: KtFile, offset: Int, text: String): TextEdit {
        val position = offset.toLspPosition(ktFile)
        val range = Range(position, position)
        return TextEdit(range, text)
    }

    /**
     * Create a TextEdit that deletes the given element
     */
    fun deleteElement(element: KtElement): TextEdit {
        return replaceElement(element, "")
    }

    /**
     * Create a TextEdit that inserts text before an element
     */
    fun insertBefore(element: KtElement, text: String): TextEdit {
        val startOffset = element.textRange.startOffset
        return insertAt(element.containingKtFile, startOffset, text)
    }

    /**
     * Create a TextEdit that inserts text after an element
     */
    fun insertAfter(element: KtElement, text: String): TextEdit {
        val endOffset = element.textRange.endOffset
        return insertAt(element.containingKtFile, endOffset, text)
    }

    /**
     * Create a TextEdit that replaces a text range with new text
     */
    fun replaceRange(ktFile: KtFile, startOffset: Int, endOffset: Int, newText: String): TextEdit {
        val startPos = startOffset.toLspPosition(ktFile)
        val endPos = endOffset.toLspPosition(ktFile)
        val range = Range(startPos, endPos)
        return TextEdit(range, newText)
    }

    /**
     * Merge multiple TextEdits into a single list, ensuring they don't overlap
     */
    fun mergeEdits(edits: List<TextEdit>): List<TextEdit> {
        // Sort edits by start position (reverse order for safe application)
        return edits.sortedWith { a, b ->
            val lineCompare = b.range.start.line.compareTo(a.range.start.line)
            if (lineCompare != 0) lineCompare
            else b.range.start.character.compareTo(a.range.start.character)
        }
    }
} 