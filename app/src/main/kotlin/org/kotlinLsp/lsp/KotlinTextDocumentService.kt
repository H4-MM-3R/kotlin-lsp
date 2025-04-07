package org.kotlinLsp.lsp

import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtElement
import org.kotlinlsp.analysis.AnalysisSession
import java.util.concurrent.CompletableFuture
import java.net.URI
import java.nio.file.Paths
import com.intellij.openapi.util.text.StringUtil
import org.eclipse.lsp4j.services.LanguageClient

class KotlinTextDocumentService(private var client: LanguageClient) : TextDocumentService {
    // Use lazy initialization for analysisSession to defer creation until first use
    private val analysisSession by lazy {
        try {
            Logger.info("Initializing AnalysisSession...")
            val session = AnalysisSession(this::publishDiagnostics)
            Logger.info("AnalysisSession initialized successfully")
            session
        } catch (e: Exception) {
            Logger.error("Failed to initialize AnalysisSession: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    private val openDocuments = mutableMapOf<String, String>()

    // Method to update the client reference
    fun updateClient(newClient: LanguageClient) {
        Logger.info("Updating language client in TextDocumentService")
        client = newClient
    }

    override fun didOpen(params: DidOpenTextDocumentParams?) {
        if (params == null) return
        
        try {
            val uri = params.textDocument.uri
            Logger.info("Document opened: $uri")
            
            // Store document content
            openDocuments[uri] = params.textDocument.text
            
            // Convert to file URI
            val path = uriToFsPath(uri)
            analysisSession.onOpenFile(path)
        } catch (e: Exception) {
            Logger.error("Error in didOpen: ${e.message}")
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams?) {
        if (params == null) return
        
        try {
            val uri = params.textDocument.uri
            val version = params.textDocument.version
            Logger.info("Document changed: $uri, version: $version")
            
            // Update document content with changes
            if (params.contentChanges.isNotEmpty()) {
                // For full sync, just replace the entire content
                openDocuments[uri] = params.contentChanges.last().text
            }
            
            // Convert to file URI
            val path = uriToFsPath(uri)
            analysisSession.onChangeFile(path, version, params.contentChanges)
        } catch (e: Exception) {
            Logger.error("Error in didChange: ${e.message}")
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams?) {
        if (params == null) return
        
        try {
            val uri = params.textDocument.uri
            Logger.info("Document closed: $uri")
            
            // Remove from open documents
            openDocuments.remove(uri)
            
            // Convert to file URI
            val path = uriToFsPath(uri)
            analysisSession.onCloseFile(path)
        } catch (e: Exception) {
            Logger.error("Error in didClose: ${e.message}")
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams?) {
        if (params == null) return
        Logger.info("Document saved: ${params.textDocument.uri}")
    }

    override fun definition(params: DefinitionParams?): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.supplyAsync {
            if (params == null) return@supplyAsync Either.forLeft<List<Location>, List<LocationLink>>(mutableListOf())
            
            try {
                val uri = params.textDocument.uri
                val position = params.position
                
                // Convert to file URI for finding the file
                val path = uriToFsPath(uri)
                
                // TODO: Implement actual definition lookup using AnalysisAPI
                // This is a placeholder until we implement the actual lookup
                
                Either.forLeft<List<Location>, List<LocationLink>>(mutableListOf())
            } catch (e: Exception) {
                Logger.error("Error in definition: ${e.message}")
                Either.forLeft<List<Location>, List<LocationLink>>(mutableListOf())
            }
        }
    }
    
    override fun hover(params: HoverParams?): CompletableFuture<Hover> {
        return CompletableFuture.supplyAsync {
            if (params == null) return@supplyAsync createDefaultHover("No parameters provided")
            
            try {
                val uri = params.textDocument.uri
                val position = params.position
                
                // Convert to file URI for finding the file
                val path = uriToFsPath(uri)
                
                // TODO: Implement actual hover information lookup using AnalysisAPI
                // This is a placeholder until we implement the actual hover
                
                createDefaultHover("Hover information not yet implemented")
            } catch (e: Exception) {
                Logger.error("Error in hover: ${e.message}")
                createDefaultHover("Error: ${e.message}")
            }
        }
    }
    
    private fun createDefaultHover(message: String): Hover {
        val content = MarkupContent(MarkupKind.MARKDOWN, message)
        return Hover(content)
    }
    
    private fun uriToFsPath(uri: String): String {
        return try {
            // Should return a path like file:///path/to/file.kt
            val uriObj = URI.create(uri)
            val path = Paths.get(uriObj).toString()
            "file://$path"
        } catch (e: Exception) {
            // Handle malformed URIs
            if (uri.startsWith("file://")) uri else "file://$uri"
        }
    }
    
    private fun findElementAtOffset(file: KtFile, offset: Int): KtElement? {
        // Find the leaf element at the offset
        val leafElement = file.findElementAt(offset) ?: return null
        
        // Find the most specific parent that is a KtElement
        var current: PsiElement? = leafElement
        while (current != null && current !is KtElement) {
            current = current.parent
        }
        
        return current as? KtElement
    }
    
    private fun Position.toOffset(ktFile: KtFile): Int = 
        StringUtil.lineColToOffset(ktFile.text, line, character)
    
    private fun publishDiagnostics(params: PublishDiagnosticsParams) {
        client.publishDiagnostics(params)
    }
    
    fun dispose() {
        analysisSession.dispose()
    }
}
