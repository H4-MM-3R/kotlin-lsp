package org.kotlinlsp.analysis.modules

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import java.nio.file.Path

interface Module {
    val id: String
    val dependencies: List<Module>
    val isSourceModule: Boolean
    val contentRoots: List<Path>
    val sourceRoots: List<Path>?
    val kaModule: KaModule

    // Extended = true includes each file from .jar file
    fun computeFiles(extended: Boolean): Sequence<VirtualFile>
}

fun List<Module>.asFlatSequence(): Sequence<Module> {
    val processedModules = mutableSetOf<String>()

    return this
        .asSequence()
        .map {
            getModuleFlatSequence(it, processedModules)
        }
        .flatten()
}

private fun getModuleFlatSequence(module: Module, processedModules: MutableSet<String>): Sequence<Module> = sequence {
    if(processedModules.contains(module.id)) return@sequence

    yield(module)

    module.dependencies.forEach {
        yieldAll(getModuleFlatSequence(it, processedModules))
    }

    processedModules.add(module.id)
}
