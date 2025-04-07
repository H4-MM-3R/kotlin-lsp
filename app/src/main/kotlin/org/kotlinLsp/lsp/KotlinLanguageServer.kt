package org.kotlinLsp.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture

class KotlinLanguageServer: LanguageServer, LanguageClientAware {
    // Initialize with a dummy client that will be replaced in connect()
    private var client: LanguageClient = NoOpLanguageClient()
    private val textDocuments: KotlinTextDocumentService = KotlinTextDocumentService(client)
    private val workspaces = KotlinWorkSpaceService()
    private var isShutdown = false

    override fun getTextDocumentService(): TextDocumentService = textDocuments
    override fun getWorkspaceService(): WorkspaceService  = workspaces

    override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> {
        Logger.info("Initializing language server")
        
        val capabilities = ServerCapabilities()
        // Enable text document synchronization
        capabilities.textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
        
        // Enable hover support
        capabilities.setHoverProvider(true)
        
        // Enable definition support
        capabilities.setDefinitionProvider(true)
        
        val result = InitializeResult(capabilities)
        Logger.info("Server capabilities initialized with text document sync, hover and definition support")
        return CompletableFuture.completedFuture(result)
    }

    override fun shutdown(): CompletableFuture<Any> {
        Logger.info("Shutting down language server")
        isShutdown = true
        textDocuments.dispose()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        Logger.info("Exiting language server")
        if (!isShutdown) {
            Logger.warning("Server was not properly shut down")
            textDocuments.dispose()
            System.exit(1)
        }
        System.exit(0)
    }

    override fun connect(client: LanguageClient?) {
        Logger.info("Connecting to language client")
        val newClient = client ?: throw IllegalArgumentException("Client must not be null")
        this.client = newClient
        // Update the client in the text document service
        textDocuments.updateClient(newClient)
    }
}

// A no-op implementation of LanguageClient to use before the real client is connected
private class NoOpLanguageClient : LanguageClient {
    override fun telemetryEvent(object_: Any?) {}
    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) {}
    override fun showMessage(messageParams: MessageParams?) {}
    override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> {
        return CompletableFuture.completedFuture(null)
    }
    override fun logMessage(message: MessageParams?) {}
}