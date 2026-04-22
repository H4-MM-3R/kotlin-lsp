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

import com.intellij.util.containers.ContainerUtil.createConcurrentSoftMap
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.allDirectDependencies
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.common.profile

class ModuleDependentsProvider: KotlinModuleDependentsProviderBase() {
    private lateinit var modules: List<Module>

    fun setup(modules: List<Module>) {
        this.modules = modules
    }

    private val directDependentsByKtModule: Map<KaModule, Set<KaModule>> by lazy {
        modules
            .asSequence()
            .map {
                buildDependentsMap(it.kaModule) { it.allDirectDependencies() }
            }
            .reduce { ac, it -> ac + it }
    }

    private val transitiveDependentsByKtModule = createConcurrentSoftMap<KaModule, Set<KaModule>>()

    private val refinementDependentsByKtModule: Map<KaModule, Set<KaModule>> by lazy {
        modules
            .asSequence()
            .map {
                buildDependentsMap(it.kaModule) { it.transitiveDependsOnDependencies.asSequence() }
            }
            .reduce { ac, it -> ac + it }
    }

    override fun getDirectDependents(module: KaModule): Set<KaModule> = profile("getDirectDependents", "$module") {
        directDependentsByKtModule[module].orEmpty()
    }

    override fun getRefinementDependents(module: KaModule): Set<KaModule> =
        profile("getRefinementDependents", "$module") {
            refinementDependentsByKtModule[module].orEmpty()
        }

    override fun getTransitiveDependents(module: KaModule): Set<KaModule> =
        profile("getTransitiveDependents", "$module") {
            transitiveDependentsByKtModule.computeIfAbsent(module) { computeTransitiveDependents(it) }
        }
}

private inline fun buildDependentsMap(
    module: KaModule,
    getDependencies: (KaModule) -> Sequence<KaModule>,
): Map<KaModule, MutableSet<KaModule>> = buildMap {
    for (dependency in getDependencies(module)) {
        if (dependency == module) continue

        val dependents = computeIfAbsent(dependency) { mutableSetOf() }
        dependents.add(module)
    }
}
