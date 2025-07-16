package org.kotlinlsp.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinReadActionConfinementLifetimeToken
import org.kotlinlsp.analysis.AnalysisSession
import org.kotlinlsp.analysis.AnalysisSessionNotifier
import org.kotlinlsp.common.getLspVersion
import org.kotlinlsp.common.info
import org.kotlinlsp.common.setupLogger
import org.kotlinlsp.common.normalizeUri
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

interface KotlinLanguageServerNotifier {
    fun onExit() {}
    fun onBackgroundIndexingFinished() {}
}

class KotlinLanguageServer(
    private val notifier: KotlinLanguageServerNotifier
) : LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware {
    private lateinit var client: LanguageClient
    private lateinit var analysisSession: AnalysisSession
    private lateinit var rootPath: String
    private val lintExecutor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "KotlinLSP-Lint")
    }
    private var lintFuture: ScheduledFuture<*>? = null

    private val analysisSessionNotifier = object : AnalysisSessionNotifier {
        override fun onBackgroundIndexFinished() {
            notifier.onBackgroundIndexingFinished()
        }

        override fun onDiagnostics(params: PublishDiagnosticsParams) {
            client.publishDiagnostics(params)
        }

        override fun onReportProgress(phase: WorkDoneProgressKind, progressToken: String, text: String) {
            val notification = when (phase) {
                WorkDoneProgressKind.begin -> WorkDoneProgressBegin().apply { title = text }
                WorkDoneProgressKind.report -> WorkDoneProgressReport().apply { message = text }
                WorkDoneProgressKind.end -> WorkDoneProgressEnd().apply { message = text }
            }
            val params = ProgressParams().apply {
                token = Either.forLeft(progressToken)
                value = Either.forLeft(notification)
            }
            client.notifyProgress(params)
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Incremental)
            hoverProvider = Either.forLeft(true)
            definitionProvider = Either.forLeft(true)
            // completionProvider = CompletionOptions(false, listOf("."))
            implementationProvider = Either.forLeft(true)
            codeActionProvider = Either.forLeft(true)
            referencesProvider = Either.forLeft(true)
            documentSymbolProvider = Either.forLeft(true)
            renameProvider = Either.forLeft(true)
            semanticTokensProvider = SemanticTokensWithRegistrationOptions().apply {
                legend = SemanticTokensLegend().apply {
                    tokenTypes = listOf(
                        "namespace", "class", "enum", "interface", "struct", "typeParameter", "type",
                        "parameter", "variable", "property", "enumMember", "decorator", "event",
                        "function", "method", "macro", "label", "comment", "string", "keyword",
                        "number", "regexp", "operator"
                    )
                    tokenModifiers = listOf(
                        "declaration", "definition", "readonly", "static", "deprecated", "abstract",
                        "async", "modification", "documentation", "defaultLibrary"
                    )
                }
                full = Either.forLeft(true)
                range = Either.forLeft(true)
            }
        }
        val serverInfo = ServerInfo().apply {
            name = "kotlin-lsp"
            version = getLspVersion()
        }

        val workspaceUri = URI(params.workspaceFolders.first().uri)
        rootPath = Paths.get(workspaceUri).toString()

        return completedFuture(InitializeResult(capabilities, serverInfo))
    }

    override fun initialized(params: InitializedParams) {
        setupLogger(client)
        info(rootPath)

        analysisSession = AnalysisSession(analysisSessionNotifier, rootPath)
    }

    override fun shutdown(): CompletableFuture<Any> {
        exit()  // TODO Nvim does not call exit so the server is kept alive and reparented to the init process (?)
        return completedFuture(null)
    }

    override fun exit() {
        lintExecutor.close()
        analysisSession.dispose()
        notifier.onExit()
    }

    override fun getTextDocumentService(): TextDocumentService = this
    override fun getWorkspaceService(): WorkspaceService = this

    override fun didOpen(params: DidOpenTextDocumentParams) {
        analysisSession.onOpenFile(params.textDocument.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri.normalizeUri()
        analysisSession.editFile(uri, params.textDocument.version, params.contentChanges)

        // Debounce the linting so it is not triggered on every keystroke
        lintFuture?.cancel(false)
        lintFuture = lintExecutor.schedule({
            analysisSession.lintFile(uri)
        }, 250, TimeUnit.MILLISECONDS)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        analysisSession.onCloseFile(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {

    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {

    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {

    }

    // override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
    //     val completions = analysisSession.autocomplete(params.textDocument.uri, params.position)
    //     return completedFuture(Either.forRight(CompletionList(false, completions)))
    // }

    override fun connect(params: LanguageClient) {
        client = params
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        // TODO Add javadoc
        val (text, range) = analysisSession.hover(params.textDocument.uri, params.position) ?: return completedFuture(
            null
        )
        val content = MarkupContent().apply {
            kind = "markdown"
            value = "```kotlin\n${text}\n```"
        }

        val hover = Hover().apply {
            contents = Either.forRight(content)
            this.range = range
        }

        return completedFuture(hover)
    }

        override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>?> {
        val locations = analysisSession.goToDefinition(params.textDocument.uri, params.position)
            ?: return completedFuture(null)
        
        val nonNullLocations = locations.filterNotNull().toMutableList()
        if (nonNullLocations.isEmpty()) {
            return completedFuture(null)
        }
        
        return completedFuture(Either.forLeft(nonNullLocations))
    }

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>?> {
        val locations = analysisSession.goToImplementation(params.textDocument.uri, params.position)
            ?: return completedFuture(null)
        
        val nonNullLocations = locations.filterNotNull()
        if (nonNullLocations.isEmpty()) {
            return completedFuture(null)
        }
        
        return completedFuture(Either.forLeft(nonNullLocations))
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        val actions = analysisSession.getCodeActions(
            params.textDocument.uri,
            params.range,
            params.context.diagnostics
        )
        return completedFuture(actions.map { Either.forRight(it)})
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>?> {
        val locations = analysisSession.findReferences(params.textDocument.uri, params.position)
        return completedFuture(locations)
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val symbols = analysisSession.documentSymbols(params.textDocument.uri)
        return completedFuture(symbols.map { Either.forRight(it) })
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        val workspaceEdit = analysisSession.rename(params.textDocument.uri, params.position, params.newName)
        return completedFuture(workspaceEdit)
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens?> {
        val tokens = analysisSession.semanticTokens(params.textDocument.uri)
        return completedFuture(tokens)
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens?> {
        val tokens = analysisSession.semanticTokensRange(params.textDocument.uri, params.range)
        return completedFuture(tokens)
    }
}
