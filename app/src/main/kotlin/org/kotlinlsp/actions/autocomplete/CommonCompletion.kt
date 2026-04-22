/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

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