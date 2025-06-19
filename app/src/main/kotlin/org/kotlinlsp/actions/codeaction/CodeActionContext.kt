package org.kotlinlsp.actions.codeaction

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.index.Index

/**
 * Context information passed to CodeAction factories
 */
data class CodeActionContext(
    val ktFile: KtFile,
    val range: Range,
    val diagnostics: List<Diagnostic>,
    val uri: String,
    val index: Index
) 