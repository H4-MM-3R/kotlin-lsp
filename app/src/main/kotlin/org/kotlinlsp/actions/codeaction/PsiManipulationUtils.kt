package org.kotlinlsp.actions.codeaction

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.psi.*
import org.kotlinlsp.common.toOffset

/**
 * Utility class for common PSI manipulation operations
 */
object PsiManipulationUtils {

    /**
     * Find PSI elements that intersect with the given range
     */
    fun findElementsInRange(ktFile: KtFile, range: Range): List<KtElement> {
        val startOffset = range.start.toOffset(ktFile)
        val endOffset = range.end.toOffset(ktFile)
        
        val elements = mutableListOf<KtElement>()
        
        fun collectElements(element: PsiElement) {
            if (element is KtElement) {
                val elementRange = element.textRange
                if (elementRange.intersects(TextRange(startOffset, endOffset))) {
                    elements.add(element)
                }
            }
            
            element.children.forEach { collectElements(it) }
        }
        
        collectElements(ktFile)
        return elements
    }

    /**
     * Find the smallest element that contains the given range
     */
    fun findContainingElement(ktFile: KtFile, range: Range): KtElement? {
        val startOffset = range.start.toOffset(ktFile)
        val endOffset = range.end.toOffset(ktFile)
        
        var element = ktFile.findElementAt(startOffset)
        while (element != null) {
            if (element is KtElement && element.textRange.contains(startOffset) && element.textRange.contains(endOffset)) {
                return element
            }
            element = element.parent
        }
        return null
    }

    /**
     * Find the element at a specific offset
     */
    fun findElementAtOffset(ktFile: KtFile, offset: Int): KtElement? {
        val element = ktFile.findElementAt(offset)
        return element?.let { findKtElement(it) }
    }

    /**
     * Find the nearest KtElement parent
     */
    private fun findKtElement(element: PsiElement): KtElement? {
        var current = element
        while (current !is KtElement && current.parent != null) {
            current = current.parent
        }
        return current as? KtElement
    }

    /**
     * Check if an element is a function declaration
     */
    fun isFunction(element: KtElement): Boolean = element is KtFunction

    /**
     * Check if an element is a property declaration
     */
    fun isProperty(element: KtElement): Boolean = element is KtProperty

    /**
     * Check if an element is a class declaration
     */
    fun isClass(element: KtElement): Boolean = element is KtClass

    /**
     * Get the text of an element with proper indentation
     */
    fun getElementText(element: KtElement): String = element.text

    /**
     * Get the import statements in a file
     */
    fun getImportStatements(ktFile: KtFile): List<KtImportDirective> {
        return ktFile.importDirectives
    }

    /**
     * Find the insertion point for a new import
     */
    fun findImportInsertionPoint(ktFile: KtFile): Int {
        val imports = getImportStatements(ktFile)
        return if (imports.isNotEmpty()) {
            imports.last().textRange.endOffset
        } else {
            // Insert after package declaration
            ktFile.packageDirective?.textRange?.endOffset ?: 0
        }
    }
} 