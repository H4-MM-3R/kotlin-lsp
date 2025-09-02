package org.kotlinlsp.actions.autocomplete

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

fun autocompletionCommonCompletion(): Sequence<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val keywords = listOf<String>("abstract", "interface", "package", "import", "class", "fun", "public", "private")
    keywords.forEach { keyword ->
        items.add(
            CompletionItem().apply {
                label = keyword
                kind = CompletionItemKind.Keyword
                insertText = keyword
                additionalTextEdits = emptyList()
            }
        )
    }
    return items.asSequence()
}