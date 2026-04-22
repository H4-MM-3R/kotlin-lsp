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

import org.jetbrains.kotlin.analysis.api.KaNonPublicApi
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleJavaAnnotationsProvider
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.load.java.structure.JavaAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.kotlinlsp.common.trace

@OptIn(KaNonPublicApi::class)
class JavaModuleAnnotationsProvider(
    private val javaModuleResolver: CliJavaModuleResolver,
): KotlinJavaModuleJavaAnnotationsProvider {
    override fun getAnnotationsForModuleOwnerOfClass(classId: ClassId): List<JavaAnnotation>? {
        trace("getAnnotationsForModuleOwnerOfClass")
        return javaModuleResolver.getAnnotationsForModuleOwnerOfClass(classId)
    }
}
