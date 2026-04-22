/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions.codeaction.providers

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.kotlinlsp.actions.codeaction.CodeActionContext
import org.kotlinlsp.actions.codeaction.CodeActionFactory
import org.kotlinlsp.common.toOffset

/**
 * Simple test code action that adds a TODO comment
 * This is for testing the CodeAction infrastructure
 */
class AddTodoCommentFactory : CodeActionFactory() {

    override fun isApplicable(context: CodeActionContext): Boolean {
        // Always applicable for testing
        return true
    }

    override fun doCreateCodeActions(context: CodeActionContext): List<CodeAction> {
        val startOffset = context.range.start.toOffset(context.ktFile)
        val todoText = "// TODO: Add implementation\n"
        
        val textEdit = textEditBuilder.insertAt(context.ktFile, startOffset, todoText)
        val workspaceEdit = createWorkspaceEdit(context.uri, listOf(textEdit))
        
        val codeAction = createCodeAction(
            title = "Add TODO comment",
            kind = CodeActionKind.QuickFix,
            edit = workspaceEdit
        )
        
        return listOf(codeAction)
    }
} 