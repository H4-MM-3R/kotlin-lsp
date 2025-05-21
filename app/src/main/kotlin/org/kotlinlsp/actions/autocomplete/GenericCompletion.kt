package org.kotlinlsp.actions.autocomplete

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions

private val newlines = arrayOf("", "\n", "\n\n")

fun autoCompletionGeneric(ktFile: KtFile, offset: Int, index: Index, completingElement: KtElement, prefix: String): List<CompletionItem> {
    val localVariableCompletions = fetchLocalCompletions(ktFile, offset, completingElement, prefix)

    val existingImports = ktFile.importList?.children?.filterIsInstance<KtImportDirective>() ?: emptyList()
    val (importInsertionOffset, newlineCount) = if (existingImports.isEmpty()) {
        ktFile.packageDirective?.textRange?.let { it.endOffset to 2 } ?: (ktFile.textRange.startOffset to 0)
    } else {
        existingImports.last().textRange.endOffset to 1
    }
    val importInsertionPosition =
        StringUtil.offsetToLineColumn(ktFile.text, importInsertionOffset).let { Position(it.line, it.column) }

    val completions = index
        .getCompletions(prefix, "", "") // TODO: ThisRef
        .map { decl ->
            val additionalEdits = mutableListOf<TextEdit>()

            if (decl is Declaration.Class) {
                val exists = existingImports.any {
                    it.importedFqName?.asString() == decl.fqName
                }
                if (!exists) {
                    val importText = "import ${decl.fqName}"
                    val edit = TextEdit().apply {
                        range = Range(importInsertionPosition, importInsertionPosition)
                        newText = "${newlines[newlineCount]}$importText"
                    }
                    additionalEdits.add(edit)
                }
            }

            val (inserted, insertionType) = decl.insertInfo()

            CompletionItem().apply {
                label = decl.name
                labelDetails = decl.details()
                kind = decl.completionKind()
                insertText = inserted
                insertTextFormat = insertionType
                additionalTextEdits = additionalEdits
            }
        }

    return localVariableCompletions + completions
}

@OptIn(KaExperimentalApi::class)
private fun fetchLocalCompletions(
    ktFile: KtFile,
    offset: Int,
    completingElement: KtElement,
    prefix: String
): List<CompletionItem> = analyze(completingElement) {
    ktFile
        .scopeContext(completingElement)
        .scopes
        .filter { it.kind is KaScopeKind.LocalScope }
        .flatMap {
            it.scope.declarations.mapNotNull { decl ->
                if (!decl.name.toString().startsWith(prefix)) return@mapNotNull null
                val psi = decl.psi ?: return@mapNotNull null

                // TODO: This is a hack to get the correct offset for function literals, can analysis tell us if a declaration is accessible?
                val declOffset = if (psi is KtFunctionLiteral) psi.textRange.startOffset else psi.textRange.endOffset
                if (declOffset >= offset) return@mapNotNull null

                val detail = when (decl) {
                    is KaVariableSymbol -> decl.returnType.render(
                        KaTypeRendererForSource.WITH_SHORT_NAMES,
                        Variance.INVARIANT
                    )

                    else -> "Missing ${decl.javaClass.simpleName}"
                }

                val preview = when (psi) {
                    is KtProperty -> psi.text
                    is KtParameter -> {
                        if (psi.isLoopParameter) {
                            val loop = psi.parentOfType<KtLoopExpression>()!!
                            loop.text.replace(loop.body!!.text, "")
                        } else psi.text
                    }

                    is KtFunctionLiteral -> decl.name // TODO: Show the function call containing the lambda?
                    else -> "TODO: Preview for ${psi.javaClass.simpleName}"
                }

                CompletionItem().apply {
                    label = decl.name.toString()
                    labelDetails = CompletionItemLabelDetails().apply {
                        this.detail = "  $detail"
                        description = ""
                    }
                    documentation = Either.forRight(
                        MarkupContent("markdown", "```kotlin\n${preview}\n```")
                    )
                    kind = CompletionItemKind.Variable
                    insertText = decl.name.toString()
                    insertTextFormat = InsertTextFormat.PlainText
                }
            }.toList()
        }
}