package org.kotlinLsp.lsp

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.services.TextDocumentService

class KotlinTextDocumentService : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams?) {
        Logger.info("Document opened: ${params?.textDocument?.uri}")
    }

    override fun didChange(params: DidChangeTextDocumentParams?) {
        Logger.info("Document changed: ${params?.textDocument?.uri}")
    }

    override fun didClose(params: DidCloseTextDocumentParams?) {
        Logger.info("Document closed: ${params?.textDocument?.uri}")
    }

    override fun didSave(params: DidSaveTextDocumentParams?) {
        Logger.info("Document saved: ${params?.textDocument?.uri}")
    }
}
