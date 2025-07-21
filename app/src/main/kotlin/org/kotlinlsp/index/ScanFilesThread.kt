package org.kotlinlsp.index

import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.index.worker.WorkerThread
import java.util.concurrent.atomic.AtomicBoolean
import org.kotlinlsp.common.info
import kotlinx.coroutines.*

class ScanFilesThread(
    private val worker: WorkerThread,
    private val modules: List<Module>
) : Runnable {
    private val shouldStop = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun run() {
        runBlocking {
            // Scan phase - parallel processing
            val sourceFiles = modules.asFlatSequence()
                .filter { it.isSourceModule }
                .map { it.computeFiles(extended = true) }
                .flatten()
                .takeWhile { !shouldStop.get() }
                .toList()

            // Process source files in parallel
            val scanJobs: List<Job> = sourceFiles.chunked(100).map { chunk ->
                scope.launch {
                    chunk.forEach { file ->
                        if (!shouldStop.get()) {
                            val command = Command.ScanSourceFile(file)
                            worker.submitCommand(command)
                        }
                    }
                }
            }
            scanJobs.joinAll()

            worker.submitCommand(Command.SourceScanningFinished)

            // Index phase - parallel processing
            val allFiles = modules.asFlatSequence()
                .sortedByDescending { it.isSourceModule }
                .map { it.computeFiles(extended = true) }
                .flatten()
                .takeWhile { !shouldStop.get() }
                .toList()

            info("${allFiles.size} files to index, starting indexing...")
            // Process all files in parallel for indexing
            val indexJobs: List<Job> = allFiles.chunked(allFiles.size/32).map { chunk ->
                scope.launch {
                    chunk.forEach { file ->
                        if (!shouldStop.get()) {
                            worker.submitCommand(Command.IndexFile(file))
                        }
                    }
                }
            }
            indexJobs.joinAll()

            worker.submitCommand(Command.IndexingFinished)
        }
    }

    fun signalToStop() {
        shouldStop.set(true)
        scope.cancel()
    }
}
