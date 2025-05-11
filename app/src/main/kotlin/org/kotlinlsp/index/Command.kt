package org.kotlinlsp.index

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.psi.KtFile

sealed class Command {
    data object Stop : Command()
    data object SourceScanningFinished: Command()
    data object ScanningFinished: Command()
    data class ScanFile(val virtualFile: VirtualFile) : Command()
    data class IndexModifiedFile(val ktFile: KtFile) : Command()
    data class IndexFile(val virtualFile: VirtualFile) : Command()
}
