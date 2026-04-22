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

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.kotlinlsp.analysis.ProgressNotifier
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.deserializeModules
import org.kotlinlsp.analysis.modules.serializeModules
import org.kotlinlsp.common.getCachePath
import org.kotlinlsp.common.info
import org.kotlinlsp.common.profile
import java.io.File
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

data class BuildSystemVersion(val version: String, val buildSystemName: String)

class BuildSystemResolver(
    private val project: Project,
    private val appEnvironment: KotlinCoreApplicationEnvironment,
    progressNotifier: ProgressNotifier,
    rootFolder: String
) {
    private val cachePath = getCachePath(rootFolder)
    private val versionFile = cachePath.resolve("buildsystem-version.json")
    private val cachedModulesFile = cachePath.resolve("buildsystem.json")

    private val BUILD_SYSTEMS: List<BuildSystem> = listOf(
        FileBasedBuildSystem(project, appEnvironment, rootFolder),
        GradleBuildSystem(project, appEnvironment, rootFolder, progressNotifier)
    )

    fun resolveModules(): List<Module> = profile("BuildSystemResolver", "") {
        val version = readVersionFile(versionFile)

        BUILD_SYSTEMS.forEach {
            if(it.markerFiles.any { File(it).exists() }) {
                val cachedVersion = getCachedVersionForBuildSystem(it::class.java.simpleName, version)
                val cachedModules = getCachedModules()
                val result = it.resolveModulesIfNeeded(if(cachedModules != null) { cachedVersion } else { null })

                if(result == null) {
                    if(cachedModules != null) {
                        info("Retrieved cached modules for ${it::class.java.simpleName} buildsystem")
                        return@profile cachedModules
                    }
                } else {
                    val newMetadata = result.metadata
                    if(newMetadata != null) {
                        cachedModulesFile.deleteIfExists()
                        versionFile.deleteIfExists()
                        val serializedCachedModules = serializeModules(result.modules)
                        val serializedVersionData = Gson().toJson(BuildSystemVersion(
                            version = newMetadata,
                            buildSystemName = it::class.java.simpleName
                        ))
                        File(cachedModulesFile.toUri()).writeText(serializedCachedModules)
                        File(versionFile.toUri()).writeText(serializedVersionData)
                    }
                    return@profile result.modules
                }
            }
        }
        throw Exception("Not suitable build system found!")
    }

    private fun readVersionFile(path: Path): BuildSystemVersion? {
        val file = File(path.toUri())
        if (!file.exists()) return null

        try {
            val content = file.readText()
            return Gson().fromJson(content, BuildSystemVersion::class.java)
        } catch(_: Exception) {
            return null
        }
    }

    private fun getCachedVersionForBuildSystem(name: String, version: BuildSystemVersion?): String? {
        if(version == null) return null
        if(version.buildSystemName != name) return null
        return version.version
    }

    private fun getCachedModules(): List<Module>? {
        val file = File(cachedModulesFile.toUri())
        if(!file.exists()) return null
        try {
            return deserializeModules(file.readText(), appEnvironment, project)
        } catch(_: Exception) {
            return null
        }
    }
}
