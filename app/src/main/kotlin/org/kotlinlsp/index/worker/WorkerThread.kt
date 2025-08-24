package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.common.info
import org.kotlinlsp.common.read
import org.kotlinlsp.index.Command
import org.kotlinlsp.index.IndexNotifier
import org.kotlinlsp.index.db.Database
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.eclipse.lsp4j.WorkDoneProgressKind
import org.kotlinlsp.buildsystem.GradleBuildSystem.Companion.PROGRESS_TOKEN
import java.util.concurrent.atomic.AtomicInteger

interface WorkerThreadNotifier: IndexNotifier {
    fun onSourceFileScanningFinished()
}

class WorkerThread(
    private val db: Database,
    private val project: Project,
    private val notifier: WorkerThreadNotifier
): Runnable {
    private val workQueue = WorkQueue<Command>()

    override fun run() {
        var scanCount = 0
        var indexCount = 0
        var sourceCount = 0

        while(true) {
            when(val command = workQueue.take() ) {
                is Command.Stop -> break
                is Command.ScanSourceFile -> {
                    if(!command.virtualFile.url.startsWith("file://")) return

                    // Only process Kotlin files
                    if (!command.virtualFile.extension.equals("kt", ignoreCase = true)) continue

                    val ktFile = project.read { PsiManager.getInstance(project).findFile(command.virtualFile) } as KtFile
                    scanKtFile(project, ktFile, db)
                    scanCount++;
                }
                is Command.IndexSource -> {
                    if( command.virtualFile.extension.equals("kt", true) || command.virtualFile.extension.equals("java", true)) {
                        sourceCount++
                        indexSourceFile(project, command.virtualFile, db)
                    }
                }
                is Command.IndexFile -> {
                    if(command.virtualFile.url.startsWith("file://")) {

                        // Only process Kotlin files
                        if (!command.virtualFile.extension.equals("kt", ignoreCase = true)) {
                            indexCount++
                            continue
                        }

                        val ktFile = project.read { PsiManager.getInstance(project).findFile(command.virtualFile) } as KtFile
                        indexKtFile(project, ktFile, db)
                        indexCount++
                    } else {
                        indexClassFile(project, command.virtualFile, db)
                        indexCount++
                    }
                }
                is Command.IndexModifiedFile -> {
                    info("Indexing modified file: ${command.ktFile.virtualFile.name}")
                    indexKtFile(project, command.ktFile, db)
                }
                is Command.IndexingFinished -> {
                    info("Background indexing finished!, ${indexCount} files!")
                    notifier.onBackgroundIndexFinished()
                }
                is Command.SourceIndexingFinished -> {
                    info("Source file indexing finished!, ${sourceCount} files!")
                }
                is Command.SourceScanningFinished -> {
                    info("Source file scanning finished!, ${scanCount} files!")
                    notifier.onSourceFileScanningFinished()
                }
            }
        }
    }

    fun submitCommand(command: Command) {
        when(command) {
            is Command.ScanSourceFile, Command.SourceScanningFinished -> {
                workQueue.putScanQueue(command)
            }
            is Command.IndexModifiedFile -> {
                workQueue.putEditQueue(command)
            }
            else -> {
                workQueue.putIndexQueue(command)
            }
        }
    }
}
