package org.kotlinlsp.analysis

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtElement
import com.intellij.mock.MockProject
import com.intellij.openapi.command.CommandProcessor
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.kotlinLsp.lsp.Logger
import java.io.File

@OptIn(KaExperimentalApi::class)
class AnalysisSession(private val onDiagnostics: (params: PublishDiagnosticsParams) -> Unit) {
    private val rootDisposable = Disposable { }
    private val project: MockProject
    private val commandProcessor: CommandProcessor
    private val psiDocumentManager: PsiDocumentManager
    private val openedFiles = mutableMapOf<String, KtFile>()
    private var sourceModificationServiceAvailable = true

    init {
        // Disable extension loading completely
        System.setProperty("idea.ignore.disabled.plugins", "true")
        System.setProperty("idea.use.native.fs.for.win", "false")
        System.setProperty("idea.plugins.path", "dummy-plugins-path")
        System.setProperty("idea.classpath.index.enabled", "false")
        
        // Create a minimal configuration
        val configuration = CompilerConfiguration()
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "kotlin-lsp-module")
        configuration.put(
            CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            PrintingMessageCollector(System.err, MessageRenderer.PLAIN_FULL_PATHS, false)
        )
        
        // Use the system classpath
        val systemClasspath = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.exists() }
        
        configuration.addJvmClasspathRoots(systemClasspath)
        configuration.put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, LanguageVersionSettingsImpl.DEFAULT)
        
        Logger.info("Creating minimal Kotlin environment...")
        try {
            val environment = KotlinCoreEnvironment.createForProduction(
                rootDisposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            
            project = environment.project as MockProject
            commandProcessor = CommandProcessor.getInstance()
            psiDocumentManager = PsiDocumentManager.getInstance(project)
            
            Logger.info("Kotlin environment created successfully")
        } catch (e: Exception) {
            Logger.error("Failed to create Kotlin environment: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    fun dispose() {
        rootDisposable.dispose()
    }

    fun onOpenFile(path: String) {
        try {
            val ktFile = loadKtFile(path)
            openedFiles[path] = ktFile
            updateDiagnostics(ktFile)
        } catch (e: Exception) {
            Logger.error("Error opening file $path: ${e.message}")
        }
    }

    fun onCloseFile(path: String) {
        openedFiles.remove(path)
    }

    fun loadKtFile(path: String): KtFile {
        val virtualFile = VirtualFileManager.getInstance()
            .findFileByUrl(path)!!
        return PsiManager.getInstance(project).findFile(virtualFile)!! as KtFile
    }

    // Stub method for finding definition - will be implemented in the future
    fun findDefinition(element: KtElement): Location? {
        // TODO: Implement using analyze session
        return null
    }
    
    // Stub method for getting hover information - will be implemented in the future
    fun getHoverInfo(element: KtElement): String? {
        // TODO: Implement using analyze session
        return null
    }

    // TODO Use version to avoid conflicts
    fun onChangeFile(path: String, version: Int, changes: List<TextDocumentContentChangeEvent>) {
        try {
            val ktFile = openedFiles[path]!!
            val doc = psiDocumentManager.getDocument(ktFile)!!
    
            // Apply changes to the document
            changes.forEach {
                try {
                    commandProcessor.executeCommand(project, {
                        val startOffset = it.range.start.toOffset(ktFile)
                        val endOffset = it.range.end.toOffset(ktFile)
    
                        doc.replaceString(startOffset, endOffset, it.text)
                        psiDocumentManager.commitDocument(doc)
                        ktFile.onContentReload()
                    }, "onChangeFile", null)
                } catch (e: Exception) {
                    Logger.error("Error applying change: ${e.message}")
                }
            }
    
            // Only try to use the modification service if it's been available before
            if (sourceModificationServiceAvailable) {
                try {
                    val service = KaSourceModificationService.getInstance(project)
                    if (service != null) {
                        service.handleElementModification(ktFile, KaElementModificationType.Unknown)
                    } else {
                        // Service is null, mark as unavailable to avoid future attempts
                        sourceModificationServiceAvailable = false
                        Logger.warning("KaSourceModificationService is not available - skipping service call")
                    }
                } catch (e: Exception) {
                    // Service failed, mark as unavailable to avoid future attempts
                    sourceModificationServiceAvailable = false
                    Logger.warning("KaSourceModificationService error (will not try again): ${e.message}")
                }
            }
    
            // Always update diagnostics
            updateDiagnostics(ktFile)
        } catch (e: Exception) {
            Logger.error("Error changing file $path: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun updateDiagnostics(ktFile: KtFile) {
        try {
            // Get file URI for publishing diagnostics
            val fileUri = "file://${ktFile.virtualFilePath}"
            
            // Collect syntax errors
            val diagnostics = PsiTreeUtil.findChildrenOfType(ktFile, PsiErrorElement::class.java).map {
                Diagnostic(
                    it.textRange.toLspRange(ktFile),
                    it.errorDescription,
                    DiagnosticSeverity.Error,
                    "Kotlin LSP"
                )
            }.toList()
            
            // Publish diagnostics
            onDiagnostics(PublishDiagnosticsParams(fileUri, diagnostics))
            
            Logger.info("Published ${diagnostics.size} syntax diagnostics for ${ktFile.virtualFilePath}")
        } catch (e: Exception) {
            Logger.error("Error in updateDiagnostics: ${e.message}")
            e.printStackTrace()
            // Send empty diagnostics to avoid client waiting
            onDiagnostics(PublishDiagnosticsParams("file://${ktFile.virtualFilePath}", emptyList()))
        }
    }
}

private fun KaSeverity.toLspSeverity(): DiagnosticSeverity =
    when(this) {
        KaSeverity.ERROR -> DiagnosticSeverity.Error
        KaSeverity.WARNING -> DiagnosticSeverity.Warning
        KaSeverity.INFO -> DiagnosticSeverity.Information
    }

private fun Position.toOffset(ktFile: KtFile): Int = StringUtil.lineColToOffset(ktFile.text, line, character)

private fun TextRange.toLspRange(ktFile: KtFile): Range {
    val text = ktFile.text
    val lineColumnStart = StringUtil.offsetToLineColumn(text, startOffset)
    val lineColumnEnd = StringUtil.offsetToLineColumn(text, endOffset)

    return Range(
        Position(lineColumnStart.line, lineColumnStart.column),
        Position(lineColumnEnd.line, lineColumnEnd.column)
    )
}