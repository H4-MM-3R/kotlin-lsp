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

import com.intellij.openapi.vfs.VirtualFile
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
import org.kotlinlsp.index.db.Database
import org.kotlinlsp.index.db.File
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.file
import java.time.Instant

class ScanFilesThread(
    private val worker: WorkerThread,
    private val modules: List<Module>,
    private val db: Database
) : Runnable {
    private val shouldStop = AtomicBoolean(false)

    private fun isAlreadyIndexedLibrarySource(file: VirtualFile): Boolean {
        return db.sourceFilesDb.get<String>(file.url) != null
    }

    private fun shouldIndexVirtualFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false

        val existing = db.file(file.url)

        if (existing == null) {
            // Library source files are tracked by indexSourceFile() in sourceFilesDb, not filesDb.
            // If sourceFilesDb already has this URL, do not enqueue it again through allFiles.
            if (isAlreadyIndexedLibrarySource(file)) return false
            return true
        }

        val current = File(
            path = file.url,
            packageFqName = existing.packageFqName,
            lastModified = Instant.ofEpochMilli(file.timeStamp),
            modificationStamp = 0L,
            indexed = true,
            declarationKeys = mutableListOf()
        )

        return !File.shouldBeSkipped(existingFile = existing, newFile = current) || !existing.indexed
    }

    private fun shouldScanSourceFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false

        val existing = db.file(file.url) ?: return true

        val current = File(
            path = file.url,
            packageFqName = existing.packageFqName,
            lastModified = Instant.ofEpochMilli(file.timeStamp),
            modificationStamp = 0L,
            indexed = false,
            declarationKeys = mutableListOf()
        )

        return !File.shouldBeSkipped(existingFile = existing, newFile = current)
    }

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
                .filter { shouldScanSourceFile(it) }
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
                .filter { shouldIndexVirtualFile(it) }
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
                .filter { vf ->
                    when {
                        vf.url.startsWith("file://") ->
                            vf.extension == "kt"

                        vf.extension == "class" ->
                            !ClassFileViewProvider.isInnerClass(vf)

                        else ->
                            false
                    }
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
                .filter { shouldIndexVirtualFile(it) }
                .toList()

            val remainingByExtension = allFiles
                .groupingBy { it.extension ?: "<no-ext>" }
                .eachCount()

            val remainingByProtocol = allFiles
                .groupingBy {
                    when {
                        it.url.startsWith("file://") -> "file"
                        it.url.contains("!/") -> "archive"
                        else -> "other"
                    }
                }
                .eachCount()

            info("${allFiles.size} files to index, starting indexing...")
            info("Remaining files to index by extension: $remainingByExtension")
            info("Remaining files to index by protocol: $remainingByProtocol")
            
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
