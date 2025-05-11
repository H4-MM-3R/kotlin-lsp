package org.kotlinLsp

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.services.TextDocumentService

class KotlinTextDocumentService : TextDocumentService {
    override fun didOpen(p0: DidOpenTextDocumentParams?) {
        TODO("Not yet implemented")
    }

    override fun didChange(p0: DidChangeTextDocumentParams?) {
        TODO("Not yet implemented")
    }

    override fun didClose(p0: DidCloseTextDocumentParams?) {
        TODO("Not yet implemented")
    }

    override fun didSave(p0: DidSaveTextDocumentParams?) {
        TODO("Not yet implemented")
    }
}
