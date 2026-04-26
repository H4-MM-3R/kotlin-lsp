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
import org.kotlinlsp.analysis.modules.LibraryModule
import org.kotlinlsp.index.db.ARTIFACT_KIND_BINARY
import org.kotlinlsp.index.db.ARTIFACT_KIND_JDK_BINARY
import org.kotlinlsp.index.db.ARTIFACT_KIND_JDK_SOURCE
import org.kotlinlsp.index.db.ARTIFACT_KIND_SOURCE
import org.kotlinlsp.index.db.Artifact
import org.kotlinlsp.index.db.Database
import org.kotlinlsp.index.db.File
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.artifact
import org.kotlinlsp.index.db.file
import org.kotlinlsp.index.db.setArtifact
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ScanFilesThread(
    private val worker: WorkerThread,
    private val modules: List<Module>,
    private val db: Database
) : Runnable {
    private val shouldStop = AtomicBoolean(false)
    private val skippedBinaryArtifacts = AtomicInteger(0)
    private val skippedJdkBinaryArtifacts = AtomicInteger(0)
    private val skippedJdkSourceArtifacts = AtomicInteger(0)
    private val skippedSourceArtifacts = AtomicInteger(0)
    private val indexedBinaryArtifacts = AtomicInteger(0)
    private val indexedJdkBinaryArtifacts = AtomicInteger(0)
    private val indexedJdkSourceArtifacts = AtomicInteger(0)
    private val indexedSourceArtifacts = AtomicInteger(0)

    private fun shouldSkipArtifact(path: Path, kind: String): Boolean {
        val current = Artifact.fromPath(path, kind, indexed = true) ?: return false
        val existing = db.artifact(current.path, kind)
        return Artifact.shouldBeSkipped(existing, current)
    }

    private fun markArtifactIndexed(path: Path, kind: String) {
        val artifact = Artifact.fromPath(path, kind, indexed = true) ?: return
        db.setArtifact(artifact)
    }

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
            skippedBinaryArtifacts.set(0)
            skippedJdkBinaryArtifacts.set(0)
            skippedJdkSourceArtifacts.set(0)
            skippedSourceArtifacts.set(0)
            indexedBinaryArtifacts.set(0)
            indexedJdkBinaryArtifacts.set(0)
            indexedJdkSourceArtifacts.set(0)
            indexedSourceArtifacts.set(0)

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
            info("${sourceFiles.size} source files to scan")

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

            val libraryModules = modules.asFlatSequence()
                .filterIsInstance<LibraryModule>()
                .filter { it.sourceRoots != null }
                .toList()

            val jdkSourceFiles = libraryModules
                .asSequence()
                .filter { it.isJdk }
                .flatMap { module ->
                    val roots = module.sourceRoots ?: return@flatMap emptySequence()

                    val changedRoots = roots.filterNot { root ->
                        val skip = shouldSkipArtifact(root, ARTIFACT_KIND_JDK_SOURCE)
                        if (skip) skippedJdkSourceArtifacts.incrementAndGet()
                        skip
                    }

                    if (changedRoots.isEmpty()) {
                        emptySequence()
                    } else {
                        indexedJdkSourceArtifacts.addAndGet(changedRoots.size)

                        val files = module.computeSources()

                        // TODO: For perfect crash-safety, mark artifacts indexed after worker completion.
                        // Current PR marks after scan/submission to keep the change surgical.
                        changedRoots.forEach { markArtifactIndexed(it, ARTIFACT_KIND_JDK_SOURCE) }

                        files
                    }
                }

            val nonJdkSourceFiles = libraryModules
                .asSequence()
                .filterNot { it.isJdk }
                .flatMap { module ->
                    val roots = module.sourceRoots ?: return@flatMap emptySequence()

                    val changedRoots = roots.filterNot { root ->
                        val skip = shouldSkipArtifact(root, ARTIFACT_KIND_SOURCE)
                        if (skip) skippedSourceArtifacts.incrementAndGet()
                        skip
                    }

                    if (changedRoots.isEmpty()) {
                        emptySequence()
                    } else {
                        indexedSourceArtifacts.addAndGet(changedRoots.size)

                        val files = module.computeSources()

                        // TODO: For perfect crash-safety, mark artifacts indexed after worker completion.
                        // Current PR marks after scan/submission to keep the change surgical.
                        changedRoots.forEach { markArtifactIndexed(it, ARTIFACT_KIND_SOURCE) }

                        files
                    }
                }

            val sources = (jdkSourceFiles + nonJdkSourceFiles)
                .takeWhile { !shouldStop.get() }
                .filter { it.extension == "kt" || it.extension == "java" }
                .filter { shouldIndexVirtualFile(it) }
                .toList()
            info("${sources.size} library sources to index")

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
            val sourceModuleCandidates = modules.asFlatSequence()
                .filter { it.isSourceModule }
                .flatMap { it.computeFiles(extended = true) }

            val libraryModulesForFiles = modules.asFlatSequence()
                .filterIsInstance<LibraryModule>()
                .toList()

            val jdkLibraryCandidates = libraryModulesForFiles
                .asSequence()
                .filter { it.isJdk }
                .flatMap { module ->
                    val roots = module.contentRoots

                    val changedRoots = roots.filterNot { root ->
                        val skip = shouldSkipArtifact(root, ARTIFACT_KIND_JDK_BINARY)
                        if (skip) skippedJdkBinaryArtifacts.incrementAndGet()
                        skip
                    }

                    if (changedRoots.isEmpty()) {
                        emptySequence()
                    } else {
                        indexedJdkBinaryArtifacts.addAndGet(changedRoots.size)

                        val files = module.computeFiles(extended = true)

                        // TODO: For perfect crash-safety, mark artifacts indexed after worker completion.
                        // Current PR marks after scan/submission to keep the change surgical.
                        changedRoots.forEach { markArtifactIndexed(it, ARTIFACT_KIND_JDK_BINARY) }

                        files
                    }
                }

            val nonJdkLibraryCandidates = libraryModulesForFiles
                .asSequence()
                .filterNot { it.isJdk }
                .flatMap { module ->
                    val changedRoots = module.contentRoots.filterNot { root ->
                        val skip = shouldSkipArtifact(root, ARTIFACT_KIND_BINARY)
                        if (skip) skippedBinaryArtifacts.incrementAndGet()
                        skip
                    }

                    if (changedRoots.isEmpty()) {
                        emptySequence()
                    } else {
                        indexedBinaryArtifacts.addAndGet(changedRoots.size)

                        val files = module.computeFiles(extended = true)

                        // TODO: For perfect crash-safety, mark artifacts indexed after worker completion.
                        // Current PR marks after scan/submission to keep the change surgical.
                        changedRoots.forEach { markArtifactIndexed(it, ARTIFACT_KIND_BINARY) }

                        files
                    }
                }

            val allFiles = (sourceModuleCandidates + jdkLibraryCandidates + nonJdkLibraryCandidates)
                .takeWhile { !shouldStop.get() }
                .filter { vf ->
                    when {
                        vf.url.startsWith("file://") -> vf.extension == "kt"
                        vf.extension == "class" -> !ClassFileViewProvider.isInnerClass(vf)
                        else -> false
                    }
                }
                .filterNot { vf ->
                    val n = vf.name
                    n == "module-info.class" || n == "package-info.class" ||
                            n.startsWith("LocaleNames_") || n.startsWith("FormatData_") ||
                            n.startsWith("metal_") || n.startsWith("synth_") ||
                            n.startsWith("CurrencyNames_")
                }
                .distinctBy { it.url }
                .filter { shouldIndexVirtualFile(it) }
                .toList()
            info("${allFiles.size} files to index")

            info(
                "Index artifact summary: " +
                    "source skipped=${skippedSourceArtifacts.get()}, source changed=${indexedSourceArtifacts.get()}, " +
                    "jdkSource skipped=${skippedJdkSourceArtifacts.get()}, jdkSource changed=${indexedJdkSourceArtifacts.get()}, " +
                    "binary skipped=${skippedBinaryArtifacts.get()}, binary changed=${indexedBinaryArtifacts.get()}, " +
                    "jdkBinary skipped=${skippedJdkBinaryArtifacts.get()}, jdkBinary changed=${indexedJdkBinaryArtifacts.get()}"
            )

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
