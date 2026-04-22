/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

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