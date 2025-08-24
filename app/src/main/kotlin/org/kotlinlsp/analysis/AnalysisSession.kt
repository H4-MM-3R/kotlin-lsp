package org.kotlinlsp.analysis

import com.intellij.core.CorePackageIndex
import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.roots.PackageIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleFinder
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.cli.jvm.modules.JavaModuleGraph
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.actions.autocomplete.autocompleteAction
import org.kotlinlsp.actions.codeaction.CodeActionContext
import org.kotlinlsp.actions.codeaction.CodeActionRegistry
import org.kotlinlsp.actions.documentSymbolsAction
import org.kotlinlsp.actions.findReferencesAction
import org.kotlinlsp.actions.goToDefinitionAction
import org.kotlinlsp.actions.goToImplementationAction
import org.kotlinlsp.actions.hoverAction
import org.kotlinlsp.actions.renameAction
import org.kotlinlsp.actions.semanticHighlightingAction
import org.kotlinlsp.actions.semanticHighlightingRangeAction
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.analysis.registration.Registrar
import org.kotlinlsp.analysis.registration.lspPlatform
import org.kotlinlsp.analysis.registration.lspPlatformPostInit
import org.kotlinlsp.analysis.services.*
import org.kotlinlsp.buildsystem.BuildSystemResolver
import org.kotlinlsp.common.*
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.IndexNotifier

interface DiagnosticsNotifier {
    fun onDiagnostics(params: PublishDiagnosticsParams)
}

interface ProgressNotifier {
    fun onReportProgress(phase: WorkDoneProgressKind, progressToken: String, text: String)
}

interface AnalysisSessionNotifier: IndexNotifier, DiagnosticsNotifier, ProgressNotifier

@OptIn(KaImplementationDetail::class)
class AnalysisSession(private val notifier: AnalysisSessionNotifier, rootPath: String) {
    private val app: MockApplication
    private val project: MockProject
    private val commandProcessor: CommandProcessor
    private val psiDocumentManager: PsiDocumentManager
    private val buildSystemResolver: BuildSystemResolver
    private val index: Index
    private val codeActionRegistry: CodeActionRegistry

    init {
        System.setProperty("java.awt.headless", "true")
        setupIdeaStandaloneExecution()

        // Create core objects for Analysis API
        val projectDisposable = Disposer.newDisposable("LSPAnalysisAPISession.project")
        val compilerConfiguration = CompilerConfiguration()
        val appEnvironment = KotlinCoreEnvironment.getOrCreateApplicationEnvironment(
            projectDisposable,
            compilerConfiguration,
            KotlinCoreApplicationEnvironmentMode.Production
        )
        val coreEnvironment = KotlinCoreProjectEnvironment(projectDisposable, appEnvironment)
        project = coreEnvironment.project
        project.registerRWLock()
        app = appEnvironment.application

        // Register the LSP platform in the Analysis API
        val registrar = Registrar(project, app, projectDisposable)
        registrar.lspPlatform()

        // Get the modules to analyze calling the appropriate build system
        buildSystemResolver = BuildSystemResolver(project, appEnvironment, notifier, rootPath)
        val modules = buildSystemResolver.resolveModules()

        // Create the index
        index = Index(modules, project, rootPath, notifier)

        // Initialize code action registry
        codeActionRegistry = CodeActionRegistry.createDefault()

        // Prepare the dependencies index for the Analysis API
        project.setupHighestLanguageLevel()
        val librariesScope = ProjectScope.getLibrariesScope(project)
        val libraryRoots = modules
            .asFlatSequence()
            .filter { !it.isSourceModule }
            .map { it.computeFiles(extended = false).map { JavaRoot(it, JavaRoot.RootType.BINARY) } }
            .flatten()
            .toList()

        val javaFileManager = project.getService(JavaFileManager::class.java) as KotlinCliJavaFileManagerImpl
        val javaModuleFinder = CliJavaModuleFinder(null, null, javaFileManager, project, null)
        val javaModuleGraph = JavaModuleGraph(javaModuleFinder)
        val delegateJavaModuleResolver = CliJavaModuleResolver(
            javaModuleGraph,
            emptyList(),
            emptyList(),    // This is always empty in standalone platform
            project,
        )

        val corePackageIndex = project.getService(PackageIndex::class.java) as CorePackageIndex

        val packagePartProvider = JvmPackagePartProvider(latestLanguageVersionSettings, librariesScope).apply {
            addRoots(libraryRoots, MessageCollector.NONE)
        }
        val rootsIndex = JvmDependenciesDynamicCompoundIndex(shouldOnlyFindFirstClass = false).apply {
            addIndex(
                JvmDependenciesIndexImpl(
                    libraryRoots,
                    shouldOnlyFindFirstClass = false
                )
            )  // TODO Should receive all (sources + libraries)
            indexedRoots.forEach { javaRoot ->
                if (javaRoot.file.isDirectory) {
                    if (javaRoot.type == JavaRoot.RootType.SOURCE) {
                        javaFileManager.addToClasspath(javaRoot.file)
                        corePackageIndex.addToClasspath(javaRoot.file)
                    } else {
                        coreEnvironment.addSourcesToClasspath(javaRoot.file)
                    }
                }
            }
        }

        javaFileManager.initialize(
            index = rootsIndex,
            packagePartProviders = listOf(packagePartProvider),
            singleJavaFileRootsIndex = SingleJavaFileRootsIndex(emptyList()),
            usePsiClassFilesReading = true,
            perfManager = null,
        )
        val fileFinderFactory = CliVirtualFileFinderFactory(rootsIndex, false, perfManager = null)

        // Register remaining services after setting up dependencies index
        registrar.lspPlatformPostInit(
            cliJavaModuleResolver = delegateJavaModuleResolver,
            cliVirtualFileFinderFactory = fileFinderFactory
        )

        // Setup platform services
        (project.getService(KotlinModuleDependentsProvider::class.java) as ModuleDependentsProvider).setup(
            modules
        )
        (project.getService(KotlinProjectStructureProvider::class.java) as ProjectStructureProvider).setup(
            modules,
            project
        )
        (project.getService(KotlinPackageProviderFactory::class.java) as PackageProviderFactory).setup(project, index)
        (project.getService(KotlinDeclarationProviderFactory::class.java) as DeclarationProviderFactory).setup(
            project,
            index
        )
        (project.getService(KotlinPackagePartProviderFactory::class.java) as PackagePartProviderFactory).setup(
            libraryRoots
        )
        (project.getService(KotlinAnnotationsResolverFactory::class.java) as AnnotationsResolverFactory).setup(project, index)
        (project.getService(KotlinDirectInheritorsProvider::class.java) as DirectInheritorsProvider).setup(project, index, modules)

        commandProcessor = app.getService(CommandProcessor::class.java)
        psiDocumentManager = PsiDocumentManager.getInstance(project)

        // Sync the index in the background
        index.syncIndexInBackground()
    }

    fun onOpenFile(path: String) {
        val ktFile = loadKtFile(path) ?: return
        index.openKtFile(path, ktFile)

        updateDiagnostics(ktFile)
    }

    fun onCloseFile(path: String) {
        index.closeKtFile(path)
    }

    private fun loadKtFile(path: String): KtFile? {
        val virtualFile = project.read { VirtualFileManager.getInstance()
            .findFileByUrl(path) } ?: return null
        return project.read { PsiManager.getInstance(project).findFile(virtualFile) as? KtFile }
    }

    private fun updateDiagnostics(ktFile: KtFile) {
        val syntaxDiagnostics = project.read {
            PsiTreeUtil.collectElementsOfType(ktFile, PsiErrorElement::class.java).map {
                return@map Diagnostic(
                    it.textRange.toLspRange(ktFile),
                    it.errorDescription,
                    DiagnosticSeverity.Error,
                    "Kotlin LSP",
                    "SYNTAX"
                )
            }
        }
        val analysisDiagnostics = project.read {
            analyze(ktFile) {
                val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)

                val lspDiagnostics = diagnostics.map {
                    return@map Diagnostic(
                        it.textRanges.first().toLspRange(ktFile),
                        it.defaultMessage,
                        it.severity.toLspSeverity(),
                        "Kotlin LSP",
                        it.factoryName
                    )
                }

                return@analyze lspDiagnostics
            }
        }
        notifier.onDiagnostics(PublishDiagnosticsParams(ktFile.virtualFile.url.normalizeUri(), syntaxDiagnostics + analysisDiagnostics))
        logProfileInfo()
    }

    // TODO Use version to avoid conflicts
    fun editFile(path: String, version: Int, changes: List<TextDocumentContentChangeEvent>) {
        val ktFile = index.getOpenedKtFile(path) ?: return
        val doc = project.read { psiDocumentManager.getDocument(ktFile) } ?: return

        project.write {
            changes.forEach {
                commandProcessor.executeCommand(project, {
                    val startOffset = it.range.start.toOffset(ktFile)
                    val endOffset = it.range.end.toOffset(ktFile)

                    // VS Code usually sends edits with CRLF ("\r\n"). The PSI Document counts
                    // offsets based on LF ("\n") line delimiters, and StringUtil's line/column
                    // helpers do the same.  Converting to LF before applying keeps offsets
                    // consistent and prevents losing or mis-placing newlines.
                    val normalizedText = it.text.replace("\r\n", "\n")
                    doc.replaceString(startOffset, endOffset, normalizedText)
                    psiDocumentManager.commitDocument(doc)
                    ktFile.onContentReload()
                }, "onChangeFile", null)
            }

            // TODO Optimize the KaElementModificationType
            KaSourceModificationService.getInstance(project)
                .handleElementModification(ktFile, KaElementModificationType.Unknown)
        }
    }

    fun lintFile(path: String) {
        val ktFile = index.getOpenedKtFile(path) ?: return
        index.queueOnFileChanged(ktFile)

        // Update diagnostics on the edited file first so feedback is faster
        updateDiagnostics(ktFile)
        index.openedKtFiles.forEach { (key, file) ->
            if(key != path) updateDiagnostics(file)
        }
    }

    fun dispose() {
        index.close()
    }

    private fun getKtFile(path: String): KtFile? {
        val openedFile = index.getOpenedKtFile(path)
        if (openedFile != null) {
            return openedFile
        }

        val virtualFile = project.read { 
            VirtualFileManager.getInstance().findFileByUrl(path) 
        } ?: return null
        
        val result = index.getKtFile(virtualFile)
        return result
    }

    fun hover(path: String, position: Position): Pair<String, Range>? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { hoverAction(ktFile, position, index) }
    }

    fun goToDefinition(path: String, position: Position): List<Location?>? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { goToDefinitionAction(ktFile, position, index) }
    }

    fun goToImplementation(path: String, position: Position): List<Location?>? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { goToImplementationAction(ktFile, position) }
    }

    fun autocomplete(path: String, position: Position): List<CompletionItem> {
        val ktFile = getKtFile(path) ?: return emptyList()
        val offset = position.toOffset(ktFile)

        return project.read { autocompleteAction(ktFile, offset, index) }.toList()
    }

    fun getCodeActions(path: String, range: Range, diagnostics: List<Diagnostic>): List<CodeAction> {
        val ktFile = getKtFile(path) ?: return emptyList()

        return project.read {
            val context = CodeActionContext(ktFile, range, diagnostics, path, index)
            codeActionRegistry.getCodeActions(context)
        }
    }

    fun findReferences(path: String, position: Position): List<Location>? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { findReferencesAction(ktFile, position, index) }
    }

    fun documentSymbols(path: String): List<DocumentSymbol> {
        val ktFile = getKtFile(path) ?: return emptyList()
        return project.read { documentSymbolsAction(ktFile) }
    }

    fun rename(path: String, position: Position, newName: String): WorkspaceEdit? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { renameAction(ktFile, position, newName, index) }
    }

    fun semanticTokens(path: String): SemanticTokens? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { semanticHighlightingAction(ktFile) }
    }

    fun semanticTokensRange(path: String, range: Range): SemanticTokens? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { semanticHighlightingRangeAction(ktFile, range) }
    }
}
