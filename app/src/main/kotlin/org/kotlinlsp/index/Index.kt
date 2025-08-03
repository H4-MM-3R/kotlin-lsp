package org.kotlinlsp.index

import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.common.read
import org.kotlinlsp.index.db.Database
import org.kotlinlsp.index.worker.WorkerThread
import org.kotlinlsp.index.worker.WorkerThreadNotifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

interface IndexNotifier {
    fun onBackgroundIndexFinished()
}

class Index(
    private val modules: List<Module>,
    private val project: Project,
    rootFolder: String,
    notifier: IndexNotifier
) {
    private val sourceFileScanningFinishedSignal = CountDownLatch(1)
    private val workerThreadNotifier = object : WorkerThreadNotifier {
        override fun onSourceFileScanningFinished() {
            sourceFileScanningFinishedSignal.countDown()
        }

        override fun onBackgroundIndexFinished() = notifier.onBackgroundIndexFinished()
    }
    private val db = Database(rootFolder)
    private val workerThreadRunner = WorkerThread(db, project, workerThreadNotifier)
    private val workerThread = Thread(workerThreadRunner, "KotlinLSP-Worker")
    private val scanFilesThreadRunner = ScanFilesThread(workerThreadRunner, modules)
    private val scanFilesThread = Thread(scanFilesThreadRunner, "KotlinLSP-ScanFiles")
    private val openedFiles: MutableMap<String, KtFile> = ConcurrentHashMap()

    // This cache prevents parsing KtFiles over and over
    private val ktFileCache = Caffeine.newBuilder()
        .maximumSize(100)
        .build<String, KtFile>()

    private val sourceFileCache: List<KtFile> = modules.asFlatSequence()
            .filter { it.isSourceModule }
            .map { it.computeFiles(extended = true) }
            .flatten()
            .mapNotNull { getKtFile(it) }
            .toList()

    fun syncIndexInBackground() {
        // We have 2 threads here
        // Scan files -> It scans files to index, loads them as a KtFile and submits them to a work queue
        // Worker     -> Takes each KtFile, fetches its symbols and writes them to the index database
        // The scan files thread will stop when the background indexing is done, while the worker is kept alive
        // for the duration of the server. It will keep receiving work whenever a KtFile is edited by the user so the
        // index is always up to date
        workerThread.start()
        scanFilesThread.start()
    }

    fun queueOnFileChanged(ktFile: KtFile) {
        workerThreadRunner.submitCommand(Command.IndexModifiedFile(ktFile))
    }

    fun close() {
        scanFilesThreadRunner.signalToStop()
        workerThreadRunner.submitCommand(Command.Stop)
        scanFilesThread.join()
        workerThread.join()

        db.close()
    }

    fun <T> query(block: (connection: Database) -> T): T {
        sourceFileScanningFinishedSignal.await()
        return block(db)
    }

    fun openKtFile(path: String, ktFile: KtFile) {
        openedFiles[path] = ktFile
    }

    fun closeKtFile(path: String) {
        openedFiles.remove(path)
    }

    fun getOpenedKtFile(path: String): KtFile? = openedFiles[path]

    val openedKtFiles: Sequence<Map.Entry<String, KtFile>>
        get() = openedFiles.asSequence()

    fun getKtFile(virtualFile: VirtualFile): KtFile? {
        // First check opened files
        val openedFile = openedFiles.get(virtualFile.url)
        if(openedFile != null) return openedFile

        // Then check the cache
        val cachedKtFile = ktFileCache.getIfPresent(virtualFile.url)
        if(cachedKtFile != null) return cachedKtFile

        // If not, load from disk and store in cache
        val ktFile = project.read { PsiManager.getInstance(project).findFile(virtualFile) as? KtFile } ?: return null
        ktFileCache.put(virtualFile.url, ktFile)
        return ktFile
    }

    fun getAllSourceKtFiles(): List<KtFile> {
        return sourceFileCache
    }
}
