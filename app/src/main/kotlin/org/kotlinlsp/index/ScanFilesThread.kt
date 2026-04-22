/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index

import com.intellij.psi.ClassFileViewProvider
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.index.worker.WorkerThread
import java.util.concurrent.atomic.AtomicBoolean
import org.kotlinlsp.common.info
import org.kotlinlsp.common.CustomDispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.kotlin.cli.common.GroupedKtSources
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.analysis.modules.LibraryModule

class ScanFilesThread(
    private val worker: WorkerThread,
    private val modules: List<Module>
) : Runnable {
    private val shouldStop = AtomicBoolean(false)

    override fun run() {
        runBlocking {
            // Get optimal concurrency level based on our dispatcher
            val maxConcurrency = 8 // Same as CustomDispatcher.cpu max parallelism
            
            // Scan phase - hyper-fast flow processing
            val sourceFiles = modules.asFlatSequence()
                .filter { it.isSourceModule }
                .flatMap { it.computeFiles(extended = true) }
                .takeWhile { !shouldStop.get() }
                .filter { it.extension == "kt" || it.extension == "java" }
                .toList()

            @OptIn(ExperimentalCoroutinesApi::class)
            sourceFiles.asFlow()
                .filter { !shouldStop.get() }
                .flatMapMerge(concurrency = maxConcurrency) { file ->
                    flow {
                        emit(worker.submitCommand(Command.ScanSourceFile(file)))
                    }.flowOn(CustomDispatcher.cpu)
                }
                .collect()

            worker.submitCommand(Command.SourceScanningFinished)

            val sources = modules.asFlatSequence()
                .filter { it.sourceRoots != null && it is LibraryModule }
                .flatMap {
                    (it as LibraryModule)
                    it.computeSources()
                }.takeWhile { !shouldStop.get() }
                .filter { it.extension == "kt" || it.extension == "java" }
                .toList()
            info("${sources.size} sources to index, starting indexing...")

            @OptIn(ExperimentalCoroutinesApi::class)
            sources.asFlow()
                .filter { !shouldStop.get() }
                .flatMapMerge(concurrency = maxConcurrency) { file ->
                    flow {
                        emit(worker.submitCommand(Command.IndexSource(file)))
                    }.flowOn(CustomDispatcher.cpu)
                }
                .collect()

            worker.submitCommand(Command.SourceIndexingFinished)

            // Index phase - hyper-fast flow processing
            val allFiles = modules.asFlatSequence()
                .sortedByDescending { it.isSourceModule }
                .flatMap { it.computeFiles(extended = true) }
                .takeWhile { !shouldStop.get() }
                .filter {
                    if(it.url.startsWith("file://")) it.extension == "kt"
                    else if (it.extension == "class") !ClassFileViewProvider.isInnerClass(it)
                    else true
                }
                .filterNot { vf ->
                    val n = vf.name
                    // Filter out noisy files early
                    n == "module-info.class" || n == "package-info.class" ||
                            n.startsWith("LocaleNames_") || n.startsWith("FormatData_") ||
                            n.startsWith("metal_") || n.startsWith("synth_") || n.startsWith("CurrencyNames_")
                            // Filter obfuscated single-letter class names and Kotlin metadata

                }
                .distinctBy { it.url }
                .toList()

            info("${allFiles.size} files to index, starting indexing...")
            
            @OptIn(ExperimentalCoroutinesApi::class)
            allFiles.asFlow()
                .filter { !shouldStop.get() }
                .flatMapMerge(concurrency = maxConcurrency) { file ->
                    flow {
                        emit(worker.submitCommand(Command.IndexFile(file)))
                    }.flowOn(CustomDispatcher.cpu)
                }
                .collect()

            worker.submitCommand(Command.IndexingFinished)
        }
    }

    fun signalToStop() {
        shouldStop.set(true)
    }
}
