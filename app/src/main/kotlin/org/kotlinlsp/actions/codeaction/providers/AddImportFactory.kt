package org.kotlinlsp.actions.codeaction.providers

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.kotlinlsp.actions.codeaction.CodeActionContext
import org.kotlinlsp.actions.codeaction.CodeActionFactory
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions
import org.kotlinlsp.index.Index
import org.kotlinlsp.common.toOffset

class AddImportFactory : CodeActionFactory() {
    /**
     * Subclasses implement this method to create specific code actions
     */
    override fun doCreateCodeActions(context: CodeActionContext): List<CodeAction> {
        val codeActions = mutableListOf<CodeAction>()

        for (diagnostic in unresolvedReferenceDiagnostics(context)) {
            val offset = diagnostic.range.start.toOffset(context.ktFile)
            val unresolvedSymbolName = getUnresolvedSymbolName(context.ktFile, offset) ?: continue

            val candidates = findImportCandidates(context.index, unresolvedSymbolName)

            for (fqn in candidates) {
                val importText = "import $fqn\n"
                val insertOffset = findImportInsertionPoint(context.ktFile)
                val textEdit = TextEdit(
                    org.eclipse.lsp4j.Range(
                        context.ktFile.viewProvider.document!!.let {
                            val line = it.getLineNumber(insertOffset)
                            org.eclipse.lsp4j.Position(line, 0)
                        },
                        context.ktFile.viewProvider.document!!.let {
                            val line = it.getLineNumber(insertOffset)
                            org.eclipse.lsp4j.Position(line, 0)
                        }
                    ),
                    importText
                )
                val workspaceEdit = WorkspaceEdit(mapOf(context.uri to listOf(textEdit)))

                val action = CodeAction().apply {
                    title = "Import '$fqn'"
                    kind = CodeActionKind.QuickFix
                    edit = workspaceEdit
                    isPreferred = fqn.startsWith("kotlin.")
                    diagnostics = listOf(diagnostic)
                }
                codeActions.add(action)
            }
        }

        return codeActions.distinctBy { it.title }
    }

    private fun findImportCandidates(index: Index, symbolName: String): List<String> {
        val declarations = index.getCompletions(symbolName)
            .filter { it.name == symbolName }
            .toList()

        val fqns = declarations.mapNotNull {
            when (it) {
                is Declaration.Function -> it.fqName
                is Declaration.Class -> it.fqName
                else -> null
            }
        }

        return fqns.distinct()
    }

    private fun getUnresolvedSymbolName(ktFile: KtFile, offset: Int): String? {
        val element = ktFile.findElementAt(offset)
        return element?.parentOfType<KtReferenceExpression>(withSelf = true)?.text
    }

    /**
     * Finds where to best insert import statements
     */
    private fun findImportInsertionPoint(ktFile: KtFile): Int {
        val importList = ktFile.importList
        if (importList != null) {
            return importList.textRange.endOffset
        }

        // if no import list, then find package directive
        val packageDirective = ktFile.packageDirective
        if (packageDirective != null) {
            return packageDirective.textRange.endOffset
        }

        return 0
    }

    private fun unresolvedReferenceDiagnostics(context: CodeActionContext): List<Diagnostic> {
        return context.diagnostics.filter {
            it.code.left == "UNRESOLVED_REFERENCE"
        }
    }

    /**
     * Check if this provider can handle the given context
     */
    override fun isApplicable(context: CodeActionContext): Boolean {
        return unresolvedReferenceDiagnostics(context).isNotEmpty()
    }
}
