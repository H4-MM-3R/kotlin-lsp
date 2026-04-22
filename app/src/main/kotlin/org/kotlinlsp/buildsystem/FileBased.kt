/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.buildsystem

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.kotlinlsp.analysis.modules.deserializeModules
import java.io.File

// This build system is used to integrate projects are not supported by the LSP
// Also used for testing purposes
class FileBasedBuildSystem(
    private val project: Project,
    private val appEnvironment: KotlinCoreApplicationEnvironment,
    private val rootFolder: String
): BuildSystem {
    override val markerFiles: List<String>
        get() = listOf("$rootFolder/.kotlinlsp-modules.json")

    override fun resolveModulesIfNeeded(cachedMetadata: String?): BuildSystem.Result? {
        val file = File("$rootFolder/.kotlinlsp-modules.json")
        val currentVersion = file.lastModified()
        if(cachedMetadata != null) {
            val cachedVersionLong = cachedMetadata.toLong()
            if(currentVersion <= cachedVersionLong) return null
        }

        val contents = file.readText()
        val rootModule = deserializeModules(
            contents,
            project = project,
            appEnvironment = appEnvironment
        )
        return BuildSystem.Result(rootModule, currentVersion.toString())
    }
}
