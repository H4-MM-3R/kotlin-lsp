/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.common.error
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
import org.kotlinlsp.common.profile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface WorkerThreadNotifier: IndexNotifier {
    fun onSourceFileScanningFinished()
}

class WorkerThread(
    private val db: Database,
    private val project: Project,
    private val notifier: WorkerThreadNotifier
): Runnable {
    private val workQueue = WorkQueue<Command>()
    
    // Thread pool for parallel .class file indexing (use 6-8 threads for good parallelism)
    private val indexThreadPool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
    )
    private val sourceThreadPool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
    )
    private val isShuttingDown = AtomicBoolean(false)

    override fun run() {
        val scanCount = AtomicInteger(0)
        val indexCount = AtomicInteger(0)
        val sourceCount = AtomicInteger(0)

        while(true) {
            when(val command = workQueue.take() ) {
                is Command.Stop -> {
                    isShuttingDown.set(true)
                    indexThreadPool.shutdown()
                    sourceThreadPool.shutdown()
                    try {
                        indexThreadPool.awaitTermination(60, TimeUnit.SECONDS)
                        sourceThreadPool.awaitTermination(60, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        indexThreadPool.shutdownNow()
                        sourceThreadPool.shutdownNow()
                    }
                    break
                }
                is Command.ScanSourceFile -> {
                    if(!command.virtualFile.url.startsWith("file://")) continue

                    // Only process Kotlin files
                    if (!command.virtualFile.extension.equals("kt", ignoreCase = true)) continue

                    val ktFile = project.read { PsiManager.getInstance(project).findFile(command.virtualFile) } as KtFile
                    scanKtFile(project, ktFile, db)
                    scanCount.incrementAndGet()
                }
                is Command.IndexSource -> {
                    sourceThreadPool.submit {
                        try {
                            if( command.virtualFile.extension.equals("kt", true) || command.virtualFile.extension.equals("java", true)) {
                                sourceCount.incrementAndGet()
                                indexSourceFile(project, command.virtualFile, db)
                            }
                        } catch (e: Exception) {
                            error("Failed to index source file ${command.virtualFile.url}: ${e.message}")
                            error(e.stackTraceToString())
                        }
                    }
                }
                is Command.IndexFile -> {
                    // Submit to thread pool for parallel processing
                    indexThreadPool.submit {
                        try {
                            if(command.virtualFile.url.startsWith("file://")) {
                                // Only process Kotlin files
                                if (!command.virtualFile.extension.equals("kt", ignoreCase = true)) {
                                    indexCount.incrementAndGet()
                                    return@submit
                                }

                                val ktFile = project.read { PsiManager.getInstance(project).findFile(command.virtualFile) } as? KtFile
                                if (ktFile != null) {
                                    indexKtFile(project, ktFile, db)
                                }
                                indexCount.incrementAndGet()
                            } else {
                                // .class file - parallel processing
                                indexClassFile(project, command.virtualFile, db)
                                indexCount.incrementAndGet()
                            }
                        } catch (e: Exception) {
                            error("Failed to index file ${command.virtualFile.url}: ${e.message}")
                            error(e.stackTraceToString())
                        }
                    }
                }
                is Command.IndexModifiedFile -> {
                    info("Indexing modified file: ${command.ktFile.virtualFile.name}")
                    indexKtFile(project, command.ktFile, db)
                }
                is Command.IndexingFinished -> {
                    // Wait for all indexing tasks to complete before reporting
                    indexThreadPool.shutdown()
                    try {
                        indexThreadPool.awaitTermination(60, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        indexThreadPool.shutdownNow()
                    }
                    
                    info("Background indexing finished!, ${indexCount.get()} files!")
                    notifier.onBackgroundIndexFinished()
                }
                is Command.SourceIndexingFinished -> {
                    sourceThreadPool.shutdown()
                    try {
                        sourceThreadPool.awaitTermination(60, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        sourceThreadPool.shutdownNow()
                    }
                    info("Source file indexing finished!, ${sourceCount.get()} files!")
                }
                is Command.SourceScanningFinished -> {
                    info("Source file scanning finished!, ${scanCount.get()} files!")
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
