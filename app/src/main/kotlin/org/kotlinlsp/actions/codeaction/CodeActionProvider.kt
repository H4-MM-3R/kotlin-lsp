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

import org.eclipse.lsp4j.CodeAction

/**
 * Interface for providing code actions based on diagnostics and context
 */
interface CodeActionProvider {
    /**
     * Check if this provider can handle the given context
     */
    fun isApplicable(context: CodeActionContext): Boolean

    /**
     * Create code actions for the given context
     */
    fun createCodeActions(context: CodeActionContext): List<CodeAction>
} 