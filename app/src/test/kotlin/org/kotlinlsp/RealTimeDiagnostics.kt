/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp

import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kotlinlsp.setup.scenario
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.verify

@Tag("CI")
class RealTimeDiagnostics {
    @Test
    fun `analyzes basic codebase with no error diagnostics`() = scenario("playground") { server, client, projectUrl, _ ->
        // Act
        server.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                uri = "$projectUrl/Main.kt"
            }
        })

        // Assert
        verify(client).publishDiagnostics(argThat { !it.diagnostics.any { it.severity == DiagnosticSeverity.Error } })
    }

    @Test
    fun `analyzes basic codebase and reports syntax error`() = scenario("playground") { server, client, projectUrl, _ ->
        // Act
        server.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                uri = "$projectUrl/Errors.kt"
            }
        })

        // Assert
        verify(client).publishDiagnostics(argThat {
            it.diagnostics.any {
                it.severity == DiagnosticSeverity.Error && it.message == "Expecting a top level declaration"
            }
        })
    }
}
