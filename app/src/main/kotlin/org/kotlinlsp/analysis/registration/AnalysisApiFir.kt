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
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationTopics

@OptIn(KaImplementationDetail::class)
fun Registrar.analysisApiFir() {
    analysisApiImplBase()
    lowLevelApiFir()
    symbolLightClasses()

    projectService(
        "org.jetbrains.kotlin.analysis.api.session.KaSessionProvider",
        "org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider",
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService",
        "org.jetbrains.kotlin.analysis.api.fir.modification.KaFirSourceModificationService"
    )
    projectService(
        "org.jetbrains.kotlin.idea.references.KotlinReferenceProviderContributor",
        "org.jetbrains.kotlin.analysis.api.fir.references.KotlinFirReferenceContributor"
    )
    projectService(
        "org.jetbrains.kotlin.idea.references.ReadWriteAccessChecker",
        "org.jetbrains.kotlin.analysis.api.fir.references.ReadWriteAccessCheckerFirImpl"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.imports.KaDefaultImportsProvider",
        "org.jetbrains.kotlin.analysis.api.fir.KaFirDefaultImportsProvider"
    )
    projectService(
        "org.jetbrains.kotlin.analysis.api.platform.statistics.KaStatisticsService",
        "org.jetbrains.kotlin.analysis.api.fir.statistics.KaFirStatisticsService"
    )
    Registry.get(
        "kotlin.analysis.lowMemoryCacheCleanup"
    ).setValue(true)
    Registry.get(
        "kotlin.analysis.postComputeEnhancedJavaFunctionsCache"
    ).setValue(false)
    Registry.get(
        "kotlin.analysis.compilerFacility.useStdlibBuildOutput"
    ).setValue(true)
    projectListener(
        "org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider\$SessionInvalidationListener",
        LLFirSessionInvalidationTopics.SESSION_INVALIDATION
    )
}
