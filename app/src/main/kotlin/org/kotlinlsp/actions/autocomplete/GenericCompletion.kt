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

fun autoCompletionGeneric(ktFile: KtFile, offset: Int, index: Index, completingElement: KtElement, prefix: String): Sequence<CompletionItem> {
    // /* TODO: Needs a Better Approach */
    // return emptySequence()

    /**
     * this is soo computationally expensive, which is Crashing/getting stuck for few seconds while typing
     * for " FUN " and some REFERENCE_EXPRESSIONS
     */
       // Get import list and where to add new imports
       val existingImports = ktFile.importList?.children?.filterIsInstance<KtImportDirective>() ?: emptyList()
       val (importInsertionOffset, newlineCount) = if (existingImports.isEmpty()) {
           ktFile.packageDirective?.textRange?.let { it.endOffset to 2 } ?: (ktFile.textRange.startOffset to 0)
       } else {
           existingImports.last().textRange.endOffset to 1
       }
       val importInsertionPosition =
           StringUtil.offsetToLineColumn(ktFile.text, importInsertionOffset).let { Position(it.line, it.column) }

       val externalCompletions = index
           .getCompletions(prefix) // TODO: ThisRef
           .filter { decl ->
               when(decl){
                   is Declaration.Class -> decl.isTopLevel && !decl.isPrivate
                   is Declaration.Field -> decl.isTopLevel
                   is Declaration.Function -> decl.isTopLevel && !decl.isExtension && !decl.isPrivate
                   else -> true
               }
           }
           .map { decl ->
               val additionalEdits = mutableListOf<TextEdit>()

               // Add the import if not there yet
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

       val localCompletions = fetchCompletionsFromScope(ktFile, offset, completingElement, prefix, "LocalScope")
       val fileCompletions =  fetchCompletionsFromScope(ktFile, offset, completingElement, prefix, "TypeScope")
//       val otherCompletions = fetchCompletionsFromScope(ktFile, offset, completingElement, prefix, "DefaultStartImportingScope")

       return localCompletions.plus(fileCompletions).plus(externalCompletions).toSet().asSequence()
}

@OptIn(KaExperimentalApi::class)
private fun fetchCompletionsFromScope(
    ktFile: KtFile,
    offset: Int,
    completingElement: KtElement,
    prefix: String,
    scopeKind: String
): List<CompletionItem> = analyze(ktFile) {
    ktFile
        .scopeContext(completingElement)
        .scopes
        .asSequence()
        .filter {
            when(scopeKind){
               "LocalScope" -> it.kind is KaScopeKind.LocalScope
                "TypeScope" -> it.kind is KaScopeKind.TypeScope
                "DefaultStartImportingScope" -> it.kind is KaScopeKind.DefaultStarImportingScope
                else -> false
            }
        }
        .flatMap { it.scope.declarations }
        .filter { it.name.toString().startsWith(prefix) }
        .mapNotNull { if (it.psi != null) Pair(it, it.psi!!) else null }
        .filter { (_, psi) ->
            // TODO: This is a hack to get the correct offset for function literals, can analysis tell us if a declaration is accessible?
            val declOffset =
                if (psi is KtFunctionLiteral) psi.textRange.startOffset else psi.textRange.endOffset
            declOffset < offset
        }
        .map { (decl, psi) ->
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
        }
        .toList()
}
