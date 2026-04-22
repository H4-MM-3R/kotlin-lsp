/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.modules

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
internal class NotUnderContentRootModule(
    override val name: String,
    override val directRegularDependencies: List<KaModule> = emptyList(),
    override val directDependsOnDependencies: List<KaModule> = emptyList(),
    override val directFriendDependencies: List<KaModule> = emptyList(),
    override val targetPlatform: TargetPlatform = JvmPlatforms.defaultJvmPlatform,
    override val file: PsiFile? = null,
    override val moduleDescription: String,
    override val project: Project,
) : KaNotUnderContentRootModule, KaModuleBase() {
    override val baseContentScope: GlobalSearchScope =
        if (file != null) GlobalSearchScope.fileScope(file) else GlobalSearchScope.EMPTY_SCOPE
}
