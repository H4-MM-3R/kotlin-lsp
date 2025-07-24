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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val commandChannel = Channel<Command>(UNLIMITED)

    override fun run() {
        runBlocking {
            val scanCount = AtomicInteger(0)
            val indexCount = AtomicInteger(0)

            // Launch parallel command processors
            val processors: List<Job> = List(4) { processorId ->
                scope.launch {
                    for (command in commandChannel) {
                        when(command) {
                            is Command.Stop -> {
                                commandChannel.close()
                                return@launch
                            }
                            is Command.ScanSourceFile -> {
                                if(!command.virtualFile.url.startsWith("file://")) continue
                                
                                // Only process Kotlin files
                                if (!command.virtualFile.extension.equals("kt", ignoreCase = true)) continue

                                val ktFile = project.read { PsiManager.getInstance(project).findFile(command.virtualFile) } as KtFile
                                    scanKtFile(project, ktFile, db)
                                    scanCount.incrementAndGet()
                            }
                            is Command.IndexFile -> {
                                if(command.virtualFile.url.startsWith("file://")) {
                                    // Only process Kotlin files
                                    if (!command.virtualFile.extension.equals("kt", ignoreCase = true)) {
                                        indexCount.incrementAndGet()
                                        continue
                                    }
                                    
                                    val ktFile = project.read { PsiManager.getInstance(project).findFile(command.virtualFile) } as KtFile
                                    indexKtFile(project, ktFile, db)
                                    indexCount.incrementAndGet()
                                } else {
                                    indexClassFile(project, command.virtualFile, db)
                                    indexCount.incrementAndGet()
                                }
                            }
                            is Command.IndexModifiedFile -> {
                                info("Indexing modified file: ${command.ktFile.virtualFile.name}")
                                indexKtFile(project, command.ktFile, db)
                            }
                            is Command.IndexingFinished -> {
                                info("Background indexing finished!, ${indexCount.get()} files!")
                                notifier.onBackgroundIndexFinished()
                            }
                            is Command.SourceScanningFinished -> {
                                info("Source file scanning finished!, ${scanCount.get()} files!")
                                notifier.onSourceFileScanningFinished()
                            }
                        }
                    }
                }
            }

            // Main loop to feed commands to processors
            while(true) {
                val command = workQueue.take()
                if (command is Command.Stop) {
                    commandChannel.send(command)
                    break
                }
                commandChannel.send(command)
            }

            // Wait for all processors to finish
            processors.joinAll()
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
