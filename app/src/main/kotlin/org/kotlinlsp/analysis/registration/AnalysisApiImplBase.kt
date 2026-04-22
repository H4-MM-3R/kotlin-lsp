/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.registration

import com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KaLifetimeTracker
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder

fun Registrar.analysisApiImplBase() {
    Registry.get(
        "kotlin.analysis.statistics"
    ).setValue(false)
    projectService(
        "org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade",
        "org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseKotlinJavaPsiFacade"
    )
    projectService(
        "org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver",
        "org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseJavaModuleResolver"
    )
    projectService(
        "org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory",
        "org.jetbrains.kotlin.analysis.api.impl.base.java.source.JavaElementSourceWithSmartPointerFactory"
    )
    projectService(
        "org.jetbrains.kotlin.psi.KotlinReferenceProvidersService",
        "org.jetbrains.kotlin.analysis.api.impl.base.references.HLApiReferenceProviderService"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider",
        "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseModuleProvider"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.platform.KotlinMessageBusProvider",
        "org.jetbrains.kotlin.analysis.api.platform.KotlinProjectMessageBusProvider"
    )
    appService(
        "org.jetbrains.kotlin.analysis.api.permissions.KaAnalysisPermissionRegistry",
        "org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionRegistry"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.platform.permissions.KaAnalysisPermissionChecker",
        "org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionChecker"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider",
        "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseResolutionScopeProvider"
    )
    projectServiceSingleton<KaLifetimeTracker>(
        "org.jetbrains.kotlin.analysis.api.platform.lifetime.KaLifetimeTracker",
        "org.jetbrains.kotlin.analysis.api.impl.base.lifetime.KaBaseLifetimeTracker"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaContentScopeProvider",
        "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseContentScopeProvider"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaGlobalSearchScopeMerger",
        "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KotlinOptimizingGlobalSearchScopeMerger"
    )
    appServiceClass(
        "org.jetbrains.kotlin.analysis.decompiled.light.classes.origin.KotlinDeclarationInCompiledFileSearcher"
    )
    // Here we don't register java.elementFinder as it will conflict
    // with our platform
    Registry.get(
        "kotlin.decompiled.light.classes.check.inconsistency"
    ).setValue(false)
    Registry.get(
        "kotlin.analysis.unrelatedSymbolCreation.allowed"
    ).setValue(false)
    projectExtensionPoint(
        "org.jetbrains.kotlin.kotlinContentScopeRefiner",
        "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaResolveExtensionToContentScopeRefinerBridge"
    )
    projectExtensionPoint(
        "org.jetbrains.kotlin.kotlinGlobalSearchScopeMergeStrategy",
        "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KotlinResolveExtensionGeneratedFileScopeMergeStrategy"
    )
}