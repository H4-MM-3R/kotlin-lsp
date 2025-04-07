package org.kotlinLsp.lsp

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture

class KotlinLanguageServer: LanguageServer, LanguageClientAware {
    private val textDocuments = KotlinTextDocumentService()
    private val workspaces = KotlinWorkSpaceService()
    private lateinit var client: LanguageClient
    private var isShutdown = false

    override fun getTextDocumentService(): TextDocumentService = textDocuments
    override fun getWorkspaceService(): WorkspaceService  = workspaces

    override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> {
        Logger.info("Initializing language server")
        
        val capabilities = ServerCapabilities()
        // Enable text document synchronization
        capabilities.textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
        
        val result = InitializeResult(capabilities)
        Logger.info("Server capabilities initialized with text document sync support")
        return CompletableFuture.completedFuture(result)
    }

    override fun shutdown(): CompletableFuture<Any> {
        Logger.info("Shutting down language server")
        isShutdown = true
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        Logger.info("Exiting language server")
        if (!isShutdown) {
            Logger.warning("Server was not properly shut down")
            System.exit(1)
        }
        System.exit(0)
    }

    override fun connect(client: LanguageClient?) {
        Logger.info("Connecting to language client")
        this.client = client!!
    }
}