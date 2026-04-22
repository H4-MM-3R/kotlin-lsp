/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.NotUnderContentRootModule
import org.kotlinlsp.common.profile
import org.kotlinlsp.common.trace

class ProjectStructureProvider: KotlinProjectStructureProviderBase() {
    private lateinit var modules: List<Module>
    private lateinit var project: Project

    private val notUnderContentRootModuleWithoutPsiFile by lazy {
        NotUnderContentRootModule(
            name = "unnamed-outside-content-root",
            moduleDescription = "not-under-content-root module without a PSI file.",
            project = project,
        )
    }

    fun setup(modules: List<Module>, project: Project) {
        this.modules = modules
        this.project = project
    }

    override fun getImplementingModules(module: KaModule): List<KaModule> = profile("getImplementingModules", "$module") {
        emptyList()  // TODO Implement for KMP support
    }

    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule = profile("getModule", "$element, useSiteModule: $useSiteModule") {
        val virtualFile = element.containingFile.virtualFile
        val visited = mutableSetOf<KaModule>()

        modules.forEach {
            val moduleFound = searchVirtualFileInModule(virtualFile, useSiteModule ?: it.kaModule, visited)
            if(moduleFound != null) return@profile moduleFound
        }

        return@profile NotUnderContentRootModule(
            file = element.containingFile,
            name = "unnamed-outside-content-root",
            moduleDescription = "Standalone not-under-content-root module with a PSI file.",
            project = project,
        )
    }

    private fun searchVirtualFileInModule(virtualFile: VirtualFile, module: KaModule, visited: MutableSet<KaModule>): KaModule? {
        if(visited.contains(module)) return null
        if(module.contentScope.contains(virtualFile)) return module

        for(it in module.directRegularDependencies) {
            val submodule = searchVirtualFileInModule(virtualFile, it, visited)
            if(submodule != null) return submodule
        }

        visited.add(module)
        return null
    }

    @OptIn(KaPlatformInterface::class)
    override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule {
        trace("getNotUnderContentRootModule")
        return notUnderContentRootModuleWithoutPsiFile
    }
}
