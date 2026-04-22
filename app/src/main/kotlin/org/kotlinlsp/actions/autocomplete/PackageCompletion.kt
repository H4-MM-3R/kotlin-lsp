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

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.subpackageNames

fun autoCompletionPackage(ktFile: KtFile, offset: Int, index: Index, leaf: PsiElement): Sequence<CompletionItem> {
    val basePackage = leaf.text.substringAfter("package ").removeSuffix(".")
    return index
        .subpackageNames(basePackage)
        .asSequence()
        .map { segment ->
            CompletionItem().apply {
                label = segment
                kind = CompletionItemKind.Module
                insertText = segment
                additionalTextEdits = emptyList()
            }
        }
}