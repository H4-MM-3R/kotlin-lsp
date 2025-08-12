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
import org.kotlinlsp.analysis.ProgressNotifier
import org.kotlinlsp.analysis.modules.*
import org.kotlinlsp.common.getCachePath
import java.io.ByteArrayOutputStream
import java.io.File
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.ModelBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

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
        val isAndroidProject = isAndroidProject(rootFolder)
        val initScript = if (isAndroidProject) getInitScriptFile(rootFolder) else null

        val modelBuilder = connection
            .model(IdeaProject::class.java)
            .setStandardOutput(stdout)
            .addProgressListener({
                progressNotifier.onReportProgress(
                    WorkDoneProgressKind.report,
                    PROGRESS_TOKEN,
                    "[GRADLE] ${it.displayName}"
                )
            }, OperationType.PROJECT_CONFIGURATION)

        // Only add init script and Android variant if it's an Android project
        if (isAndroidProject) {
            modelBuilder.withArguments("--init-script", initScript!!.absolutePath, "-DandroidVariant=${androidVariant}")
        }

        val ideaProject = modelBuilder.blockingGetWithProgress(progressNotifier)

        // For Android projects, ensure required AGP intermediates (e.g., R.jar, classes.jar) exist.
        // Target ONLY modules that have missing jar entries referenced on compile classpaths.
        if (isAndroidProject) {
            try {
                val marker = getCachePath(rootFolder).resolve(".first_assemble_done").toFile()
                if (!marker.exists()) {
                    val projectRoot = File(rootFolder).absolutePath + File.separator
                    val tasks = ideaProject.modules
                        .asSequence()
                        .mapNotNull { m -> m.contentRoots.firstOrNull() }
                        .flatMap { cr -> cr.sourceDirectories.asSequence().map { it.directory.toString() } }
                        .filter { it.startsWith("jar:") }
                        .map { it.removePrefix("jar:") }
                        .filter { it.startsWith(projectRoot) && it.contains("/build/intermediates/") }
                        .mapNotNull { jarPath ->
                            // derive module name from .../<module>/build/...
                            val rel = jarPath.removePrefix(projectRoot)
                            val moduleName = rel.substringBefore("/build/")
                            val file = File(jarPath)
                            if (!file.exists()) ":${moduleName}:assembleDebug" else null
                        }
                        .distinct()
                        .toList()

                    if (tasks.isNotEmpty()) {
                        progressNotifier.onReportProgress(
                            WorkDoneProgressKind.report,
                            PROGRESS_TOKEN,
                            "[GRADLE] First-run assemble (targeted by missing jars) for tasks: ${tasks.joinToString()}"
                        )
                        // Run as a single build invocation; if it fails, try per-task fallback
                        try {
                            connection.newBuild().forTasks(*tasks.toTypedArray()).withArguments("-x", "lint", "-x", "test").run()
                        } catch (_: Throwable) {
                            tasks.forEach { t ->
                                try { connection.newBuild().forTasks(t).withArguments("-x", "lint", "-x", "test").run() } catch (_: Throwable) {}
                            }
                        }
                    }

                    marker.writeText("done")
                }
            } catch (_: Throwable) {
                // Best-effort: do not fail project resolution if assemble fails
            }
        }
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
            val isAndroidModule = isAndroidProject && ideaExtraSourceDeps.isNotEmpty()
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
                        // Normalize module dependency names. Tooling may return paths like ":lyricsService";
                        // our module ids use the plain module name (e.g., "lyricsService").
                        ideaSourceModuleDeps.map { dep -> dep.targetModuleName.substringAfterLast(":") }
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
        initScript?.delete()

        progressNotifier.onReportProgress(WorkDoneProgressKind.report, PROGRESS_TOKEN, "[GRADLE] Building modules graph...")
        val result = BuildSystem.Result(buildModulesGraph(modules.values.toList(), modules, appEnvironment, project), metadata)
        progressNotifier.onReportProgress(WorkDoneProgressKind.report, PROGRESS_TOKEN, "[GRADLE] Graph built successfully")
        return result
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

private fun isAndroidProject(rootFolder: String): Boolean {
    val rootDir = File(rootFolder)

    // Check for Android in build files and version catalog
    val filesToCheck = listOf(
        "build.gradle",
        "build.gradle.kts",
        "libs.versions.toml"
    )

    for (fileName in filesToCheck) {
        val file = File(rootDir, fileName)
        if (file.exists()) {
            try {
                val content = file.readText()
                if (content.contains("android", ignoreCase = true)) {
                    return true
                }
            } catch (e: Exception) {
                // Skip files that can't be read
                continue
            }
        }
    }

    return false
}


private fun ModelBuilder<IdeaProject>.blockingGetWithProgress(progressNotifier: ProgressNotifier): IdeaProject {
    val latch = CountDownLatch(1)
    val resultRef = AtomicReference<IdeaProject>()
    val errorRef = AtomicReference<Throwable>()

    this.get(object : ResultHandler<IdeaProject> {
        override fun onComplete(result: IdeaProject) {
            progressNotifier.onReportProgress(
                WorkDoneProgressKind.report,
                GradleBuildSystem.PROGRESS_TOKEN,
                "[GRADLE] Project resolved successfully"
            )
            resultRef.set(result)
            latch.countDown()
        }

        override fun onFailure(t: GradleConnectionException) {
            val exceptions = mutableListOf<String>()
            var cur: Throwable? = t

            while (cur != null) {
                when(cur::class.simpleName) {
                    "LocationAwareException" -> exceptions.add("${cur.localizedMessage}")
                    "ExecException" -> exceptions.add("${cur.localizedMessage}")
                    "NativeException" -> exceptions.add("${cur.localizedMessage}")
                }
                cur = cur.cause
            }
            exceptions.forEach {
                progressNotifier.onReportProgress(
                    WorkDoneProgressKind.report,
                    GradleBuildSystem.PROGRESS_TOKEN,
                    it
                    )
            }
            progressNotifier.onReportProgress(
                WorkDoneProgressKind.end,
                GradleBuildSystem.PROGRESS_TOKEN,
                "[GRADLE] Model resolution failed"
            )
            errorRef.set(t)
            latch.countDown()
        }
    })

    latch.await()

    errorRef.get()?.let { throw it }
    return resultRef.get()
}