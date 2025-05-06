package org.kotlinlsp.buildsystem

import com.google.gson.Gson
import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.kotlinlsp.analysis.ProgressNotifier
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.deserializeRootModule
import org.kotlinlsp.analysis.modules.serializeRootModule
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

    fun resolveModuleDAG(): Module = profile("BuildSystemResolver", "") {
        val version = readVersionFile(versionFile)

        BUILD_SYSTEMS.forEach {
            if(it.markerFiles.any { File(it).exists() }) {
                val cachedVersion = getCachedVersionForBuildSystem(it::class.java.simpleName, version)
                val resolvedModules = it.resolveRootModuleIfNeeded(cachedVersion)

                if(resolvedModules == null) {
                    val cachedModules = getCachedModules()
                    if(cachedModules != null) {
                        info("Retrieved cached modules for ${it::class.java.simpleName} buildsystem")
                        return@profile cachedModules
                    }
                } else {
                    val newVersion = resolvedModules.second
                    if(newVersion != null) {
                        cachedModulesFile.deleteIfExists()
                        versionFile.deleteIfExists()
                        val serializedCachedModules = serializeRootModule(resolvedModules.first)
                        val serializedVersionData = Gson().toJson(BuildSystemVersion(
                            version = newVersion,
                            buildSystemName = it::class.java.simpleName
                        ))
                        File(cachedModulesFile.toUri()).writeText(serializedCachedModules)
                        File(versionFile.toUri()).writeText(serializedVersionData)
                    }
                    return@profile resolvedModules.first
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

    private fun getCachedModules(): Module? {
        val file = File(cachedModulesFile.toUri())
        if(!file.exists()) return null
        try {
            val rootModule = deserializeRootModule(file.readText(), appEnvironment, project)
            return rootModule
        } catch(_: Exception) {
            return null
        }
    }
}
