package org.kotlinLsp

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.Closeable
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.CompletableFuture

class KotlinLanguageServer: LanguageServer, LanguageClientAware, Closeable {
    private lateinit var rootpath: String
    private lateinit var client: LanguageClient
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply{
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Incremental)
            hoverProvider = Either.forLeft(true)
        }

        rootpath = params.workspaceFolders.first().uri.removePrefix("file://")

        return completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<in Any>? {
        TODO("Not yet implemented")
    }

    override fun exit() {
        TODO("Not yet implemented")
    }

    override fun getTextDocumentService(): TextDocumentService = KotlinTextDocumentService();
    override fun getWorkspaceService(): WorkspaceService = KotlinWorkspaceService();

    override fun connect(params: LanguageClient) {
        client = params
    }

    override fun close() {
        TODO("Not yet implemented")
    }

}
