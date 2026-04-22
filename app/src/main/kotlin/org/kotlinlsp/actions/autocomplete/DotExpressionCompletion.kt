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
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.getCompletions

fun autoCompletionDotExpression(
    ktFile: KtFile,
    offset: Int,
    index: Index,
    completingElement: KtDotQualifiedExpression,
    prefix: String
): Sequence<CompletionItem> {
    val receiverType = analyze(completingElement) {
        completingElement.receiverExpression.expressionType.toString()
    }

    return index
        .getCompletions(prefix) // TODO: ThisRef
        .filterMatchesReceiver(receiverType)
        .map { decl ->
            val (inserted, insertionType) = decl.insertInfo()
            CompletionItem().apply {
                label = decl.name
                labelDetails = decl.details()
                kind = decl.completionKind()
                insertText = inserted
                insertTextFormat = insertionType
                additionalTextEdits = emptyList()
            }
        }
}
