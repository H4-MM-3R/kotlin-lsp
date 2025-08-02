package org.kotlinlsp.index

import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.index.worker.WorkerThread
import java.util.concurrent.atomic.AtomicBoolean
import org.kotlinlsp.common.info
import org.kotlinlsp.common.CustomDispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
                .map { it.computeFiles(extended = true) }
                .flatten()
                .takeWhile { !shouldStop.get() }
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

            // Index phase - hyper-fast flow processing
            val allFiles = modules.asFlatSequence()
                .sortedByDescending { it.isSourceModule }
                .map { it.computeFiles(extended = true) }
                .flatten()
                .takeWhile { !shouldStop.get() }
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
