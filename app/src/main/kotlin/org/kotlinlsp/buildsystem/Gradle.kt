package org.kotlinlsp.buildsystem

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.WorkDoneProgressKind
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.model.GradleModuleVersion
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.util.collectionUtils.concat
import org.kotlinlsp.analysis.ProgressNotifier
import org.kotlinlsp.analysis.modules.*
import org.kotlinlsp.common.getCachePath
import java.io.ByteArrayOutputStream
import java.io.File

class GradleBuildSystem(
    private val project: Project,
    private val appEnvironment: KotlinCoreApplicationEnvironment,
    private val rootFolder: String,
    private val progressNotifier: ProgressNotifier
) : BuildSystem {
    companion object {
        const val PROGRESS_TOKEN = "GradleBuildSystem"
    }

    private val androidVariant = "debug"    // TODO Make it a config parameter

    override val markerFiles: List<String> = listOf(
        "$rootFolder/build.gradle", "$rootFolder/build.gradle.kts",
        "$rootFolder/settings.gradle", "$rootFolder/settings.gradle.kts",
    )

    override fun resolveModulesIfNeeded(cachedMetadata: String?): BuildSystem.Result? {
        if (!shouldReloadGradleProject(cachedMetadata)) {
            return null
        }

        progressNotifier.onReportProgress(WorkDoneProgressKind.begin, PROGRESS_TOKEN, "[GRADLE] Resolving project...")
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(File(rootFolder))
            .connect()

        val stdout = ByteArrayOutputStream()
        val initScript = getInitScriptFile(rootFolder)
        val ideaProject = connection
            .model(IdeaProject::class.java)
            .withArguments("--init-script", initScript.absolutePath, "-DandroidVariant=${androidVariant}")
            .setStandardOutput(stdout)
            .addProgressListener({
                progressNotifier.onReportProgress(
                    WorkDoneProgressKind.report,
                    PROGRESS_TOKEN,
                    "[GRADLE] ${it.displayName}"
                )
            }, OperationType.PROJECT_CONFIGURATION)
            .get()

        val modules = mutableMapOf<String, SerializedModule>()

        // Register the JDK module
        val jdk = ideaProject.javaLanguageSettings.jdk
        if(jdk != null) {
            val jdkModule = SerializedModule(
                id = "JDK",
                contentRoots = listOf(jdk.javaHome.absolutePath),
                javaVersion = ideaProject.jdkName,
                dependencies = listOf(),
                isSource = false,
                isJdk = true,
            )
            modules[jdkModule.id] = jdkModule
        }

        // Process each module from the idea project
        ideaProject.modules.forEach { module ->
            val contentRoot =
                module.contentRoots.first()   // Don't know in which cases we would have multiple contentRoots

            // Extra source dependencies can be specified in the source directories with the jar: prefix, it is a workaround
            // as the init script cannot add new dependencies the normal way
            val (ideaSourceDirs, ideaExtraSourceDeps) = contentRoot
                .sourceDirectories
                .partition { !it.directory.toString().startsWith("jar:") }

            // Don't process empty modules
            if (ideaSourceDirs.isEmpty()) return@forEach

            val (ideaTestDeps, ideaSourceDeps) = module
                .dependencies
                .filterIsInstance<IdeaSingleEntryLibraryDependency>()
                .filter { it.scope.scope != "RUNTIME" } // We don't need runtime deps for a LSP
                .partition { it.scope.scope == "TEST" }

            val ideaSourceModuleDeps = module
                .dependencies
                .filterIsInstance<IdeaModuleDependency>()

            // Register regular dependencies
            (ideaTestDeps.asSequence() + ideaSourceDeps.asSequence()).forEach {
                val id = it.gradleModuleVersion.formatted()
                if (modules.containsKey(id)) return@forEach

                modules[id] = SerializedModule(
                    id = id,
                    isSource = false,
                    dependencies = emptyList(),
                    isJdk = false,
                    javaVersion = ideaProject.jdkName,
                    contentRoots = listOf(it.file.absolutePath)
                )
            }

            // Register extra dependencies
            ideaExtraSourceDeps.forEach {
                val path = it.directory.toString().removePrefix("jar:")
                if (modules.containsKey(path)) return@forEach

                modules[path] = SerializedModule(
                    id = path,
                    isSource = false,
                    dependencies = emptyList(),
                    isJdk = false,
                    javaVersion = ideaProject.jdkName,
                    contentRoots = listOf(path)
                )
            }

            // Register source module
            val isAndroidModule = ideaExtraSourceDeps.isNotEmpty()  // TODO Find a better way to know this
            val sourceModuleId = module.name
            val sourceDirs = ideaSourceDirs.map { it.directory.absolutePath }
            val sourceDeps =
                ideaSourceDeps.asSequence().map { it.gradleModuleVersion.formatted() }
                    .plus(
                        ideaExtraSourceDeps.map {
                            it.directory.toString().removePrefix("jar:")
                        }
                    ).plus(
                        if (!isAndroidModule) {
                            sequenceOf("JDK")
                        } else {
                            emptySequence()
                        }
                    ).plus(
                        ideaSourceModuleDeps.map { it.targetModuleName }
                    )
            modules[sourceModuleId] = SerializedModule(
                id = sourceModuleId,
                isSource = true,
                dependencies = sourceDeps.toList(),
                contentRoots = sourceDirs,
                kotlinVersion = LanguageVersion.KOTLIN_2_1.versionString,   // TODO Figure out this
                javaVersion = ideaProject.jdkName
            )

            // Register test module
            if (contentRoot.testDirectories.isNotEmpty()) {
                val testModuleId = "${sourceModuleId}-test"
                val testDirs = contentRoot.testDirectories.map { it.directory.absolutePath }
                val testDeps =
                    sourceDeps
                        .plus(ideaTestDeps.map { it.gradleModuleVersion.formatted() })
                        .plus(sequenceOf(sourceModuleId))
                modules[testModuleId] = SerializedModule(
                    id = testModuleId,
                    isSource = true,
                    dependencies = testDeps.toList(),
                    contentRoots = testDirs,
                    kotlinVersion = LanguageVersion.KOTLIN_2_1.versionString,   // TODO Figure out this
                    javaVersion = ideaProject.jdkName
                )
            }
        }

        progressNotifier.onReportProgress(WorkDoneProgressKind.end, PROGRESS_TOKEN, "[GRADLE] Done")

        val metadata = Gson().toJson(computeGradleMetadata(ideaProject))

        connection.close()
        initScript.delete()

        val moduleList = modules.values.toList()
        return BuildSystem.Result(buildModulesGraph(moduleList, modules, appEnvironment, project), metadata)
    }
}

private fun GradleModuleVersion.formatted(): String = "$group:$name:${version}"

private fun computeGradleMetadata(project: IdeaProject): Map<String, Map<String, Long>> {
    val result = mutableMapOf<String, Map<String, Long>>()
    project.modules.forEach {
        val folder = it.contentRoots.first().rootDirectory
        result[folder.absolutePath] = getGradleFileTimestamps(folder)
    }
    return result
}

private fun getGradleFileTimestamps(dir: File): Map<String, Long> {
    if (!dir.isDirectory) return emptyMap()

    val fileNames = listOf(
        "settings.gradle",
        "settings.gradle.kts",
        "build.gradle",
        "build.gradle.kts",
        "gradle.properties"
    )

    val result = mutableMapOf<String, Long>()

    for (name in fileNames) {
        val file = File(dir, name)
        if (file.exists()) {
            result[name] = file.lastModified()
        }
    }

    return result
}

private fun shouldReloadGradleProject(metadataString: String?): Boolean {
    if(metadataString == null) return true
    val type = object : TypeToken<Map<String, Map<String, Long>>>() {}.type
    val metadata: Map<String, Map<String, Long>> = try {
        Gson().fromJson(metadataString, type)
    } catch(_: Throwable) {
        return true
    }

    metadata.forEach { (folder, timestamps) ->
        timestamps.forEach { (file, cachedTimestamp) ->
            val currentTimestamp = File(folder).resolve(file).lastModified()
            if(currentTimestamp > cachedTimestamp) return true
        }
    }

    return false
}

private fun getInitScriptFile(rootFolder: String): File {
    val inputStream = object {}.javaClass.getResourceAsStream("/android.init.gradle")
    val scriptFile = getCachePath(rootFolder).resolve(".android.init.gradle").toFile()
    scriptFile.delete()

    scriptFile.outputStream().use { out ->
        inputStream.copyTo(out)
    }

    return scriptFile
}
