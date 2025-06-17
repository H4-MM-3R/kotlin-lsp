package org.kotlinlsp.actions.codeaction

import org.eclipse.lsp4j.CodeAction

/**
 * Interface for providing code actions based on diagnostics and context
 */
interface CodeActionProvider {
    /**
     * Check if this provider can handle the given context
     */
    fun isApplicable(context: CodeActionContext): Boolean

    /**
     * Create code actions for the given context
     */
    fun createCodeActions(context: CodeActionContext): List<CodeAction>
} 