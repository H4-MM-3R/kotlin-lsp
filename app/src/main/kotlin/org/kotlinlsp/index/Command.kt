package org.kotlinlsp.index

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.psi.KtFile

sealed class Command {
    data object Stop : Command()
    data object SourceScanningFinished: Command()
    data object SourceIndexingFinished: Command()
    data object IndexingFinished: Command()
    data class ScanSourceFile(val virtualFile: VirtualFile) : Command()
    data class IndexSource(val virtualFile: VirtualFile) : Command()
    data class IndexModifiedFile(val ktFile: KtFile) : Command()
    data class IndexFile(val virtualFile: VirtualFile) : Command()
}
