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
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.messages.Topic
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationListener
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator

@OptIn(KaImplementationDetail::class)
fun Registrar.lowLevelApiFir() {
    projectExtensionPoint(
        "org.jetbrains.kotlin.llFirSessionConfigurator",
        LLFirSessionConfigurator::class.java
    )
    Registry.get(
        "kotlin.parallel.resolve.under.global.lock"
    ).setValue(false)
    Registry.get(
        "kotlin.analysis.ll.locking.interval"
    ).setValue("100")
    Registry.get(
        "kotlin.analysis.jvmBuiltinActualizationForStdlibSources"
    ).setValue(true)
    projectService(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.api.services.LLFirElementByPsiElementChooser",
        "org.jetbrains.kotlin.analysis.low.level.api.fir.services.LLRealFirElementByPsiElementChooser"
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.projectStructure.LLFirBuiltinsSessionFactory",
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache",
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService"
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationEventPublisher"
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirGlobalResolveComponents"
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.LLResolutionFacadeService",
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirDeclarationModificationService"
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker"
    )
    projectServiceClass(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.statistics.LLStatisticsService"
    )
    appService(
        "org.jetbrains.kotlin.analysis.api.platform.resolution.KaResolutionActivityTracker",
        "org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirResolutionActivityTracker"
    )
    projectListener(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService\$LLKotlinModificationEventListener",
        KotlinModificationEvent.TOPIC
    )
    projectListener(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService\$LLPsiModificationTrackerListener",
        PsiModificationTracker.TOPIC
    )
    val llFirInBlockModificationListenerTopic = Topic(
        LLFirInBlockModificationListener::class.java,
        Topic.BroadcastDirection.TO_CHILDREN,
        true,
    )
    projectListener(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationListenerForCodeFragments",
        llFirInBlockModificationListenerTopic
    )
    projectListener(
        "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker\$Listener",
        llFirInBlockModificationListenerTopic
    )
}
