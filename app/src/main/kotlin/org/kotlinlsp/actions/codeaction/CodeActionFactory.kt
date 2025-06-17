package org.kotlinlsp.actions.codeaction

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.jetbrains.kotlin.psi.KtElement

/**
 * Abstract base class for creating specific code action fixes
 * Similar to IntelliJ's KotlinQuickFixFactory pattern
 */
abstract class CodeActionFactory : CodeActionProvider {

    /**
     * Create a WorkspaceEdit from a list of TextEdits for a single file
     */
    protected fun createWorkspaceEdit(uri: String, edits: List<TextEdit>): WorkspaceEdit {
        val changes = mapOf(uri to edits)
        return WorkspaceEdit(changes)
    }

    /**
     * Create a basic CodeAction with the given parameters
     */
    protected fun createCodeAction(
        title: String,
        kind: String = CodeActionKind.QuickFix,
        edit: WorkspaceEdit,
        isPreferred: Boolean = false
    ): CodeAction {
        return CodeAction().apply {
            this.title = title
            this.kind = kind
            this.edit = edit
            this.isPreferred = isPreferred
        }
    }

    /**
     * Template method for creating code actions
     */
    final override fun createCodeActions(context: CodeActionContext): List<CodeAction> {
        if (!isApplicable(context)) return emptyList()
        
        return try {
            doCreateCodeActions(context)
        } catch (e: Exception) {
            // Log error and return empty list to prevent LSP failure
            emptyList()
        }
    }

    /**
     * Subclasses implement this method to create specific code actions
     */
    protected abstract fun doCreateCodeActions(context: CodeActionContext): List<CodeAction>

    /**
     * Utility method to find PSI elements at the range
     */
    protected fun findElementsInRange(context: CodeActionContext): List<KtElement> {
        return PsiManipulationUtils.findElementsInRange(context.ktFile, context.range)
    }

    /**
     * Create TextEdit utilities
     */
    protected val textEditBuilder = PsiTextEditBuilder()
} 