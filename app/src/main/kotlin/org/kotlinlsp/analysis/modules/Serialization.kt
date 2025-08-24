package org.kotlinlsp.analysis.modules

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.kotlinlsp.common.warn
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

data class SerializedModule(
    val id: String,
    val dependencies: List<String>,
    val contentRoots: List<String>,
    val sourceRoots: List<String>? = null,
    val javaVersion: String,
    val isSource: Boolean,
    // SourceModule
    val kotlinVersion: String? = null,
    // LibraryModule
    val isJdk: Boolean? = null,
)

fun serializeModules(modules: List<Module>): String {
    val visited = LinkedHashMap<String, SerializedModule>()
    val stack = ArrayDeque<Module>()
    stack.addAll(modules)

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val id = current.id
        if(visited.containsKey(id)) continue

        visited[id] = when(current) {
            is SourceModule -> SerializedModule(
                id = id,
                contentRoots = current.contentRoots.map { it.absolutePathString() },
                sourceRoots = current.sourceRoots?.map { it.absolutePathString() },
                kotlinVersion = current.kotlinVersion.versionString,
                javaVersion = current.javaVersion.toString(),
                dependencies = current.dependencies.map { it.id },
                isSource = current.isSourceModule
            )
            is LibraryModule -> SerializedModule(
                id = id,
                contentRoots = current.contentRoots.map { it.absolutePathString() },
                sourceRoots = current.sourceRoots?.map { it.absolutePathString() },
                isJdk = current.isJdk,
                javaVersion = current.javaVersion.toString(),
                dependencies = current.dependencies.map { it.id },
                isSource = current.isSourceModule
            )
            else -> throw Exception("Unsupported KaModule!")
        }
        stack.addAll(current.dependencies)
    }

    return GsonBuilder().setPrettyPrinting().create().toJson(visited.values)
}

fun deserializeModules(
    data: String,
    appEnvironment: KotlinCoreApplicationEnvironment,
    project: Project
): List<Module> {
    val gson = Gson()
    val modules: List<SerializedModule> = gson.fromJson(data, Array<SerializedModule>::class.java).toList()
    val moduleMap = modules.associateBy { it.id }
    return buildModulesGraph(modules, moduleMap, appEnvironment, project)
}

fun buildModulesGraph(
    modules: List<SerializedModule>,
    moduleMap: Map<String, SerializedModule>,
    appEnvironment: KotlinCoreApplicationEnvironment,
    project: Project
): List<Module> {
    val builtModules = mutableMapOf<String, Module>()
    val visited = mutableSetOf<String>()

    fun build(id: String, depth: Int = 0): Module {
        if (builtModules.containsKey(id)) return builtModules[id]!!

        if (visited.contains(id)) {
            warn("Circular dependency detected for module: $id. Breaking cycle.")
            return builtModules.getOrPut(id) {
                val serialized = moduleMap[id] ?: throw IllegalStateException("Module not found: $id")
                if (serialized.isSource) {
                    SourceModule(
                        id = id,
                        kotlinVersion = LanguageVersion.fromVersionString(serialized.kotlinVersion ?: "2.1")!!,
                        javaVersion = JvmTarget.fromString(serialized.javaVersion)!!,
                        contentRoots = serialized.contentRoots.map { Path(it) },
                        dependencies = emptyList(), // Break the cycle by not including dependencies
                        project = project
                    )
                } else {
                    LibraryModule(
                        id = id,
                        javaVersion = JvmTarget.fromString(serialized.javaVersion)!!,
                        isJdk = serialized.isJdk ?: false,
                        contentRoots = serialized.contentRoots.map { Path(it) },
                        sourceRoots = serialized.sourceRoots?.map { Path(it) },
                        dependencies = emptyList(), // Break the cycle by not including dependencies
                        project = project,
                        appEnvironment = appEnvironment,
                    )
                }
            }
        }

        visited.add(id)
        val serialized = moduleMap[id] ?: throw IllegalStateException("Module not found: $id")
        val deps = serialized.dependencies.map { build(it, depth + 1) }
        val module = buildModule(serialized, deps, project, appEnvironment)
        builtModules[id] = module
        visited.remove(id)
        return module
    }

    return modules
        .asSequence()
        .filter { it.isSource }
        .map { build(it.id) }
        .toList()
}

private fun buildModule(
    it: SerializedModule,
    deps: List<Module>,
    project: Project,
    appEnvironment: KotlinCoreApplicationEnvironment
): Module =
    if(it.isSource) {
        SourceModule(
            id = it.id,
            kotlinVersion = LanguageVersion.fromVersionString(it.kotlinVersion!!)!!,
            javaVersion = JvmTarget.fromString(it.javaVersion)!!,
            contentRoots = it.contentRoots.map { Path(it) },
            dependencies = deps,
            project = project
        )
    } else {
        LibraryModule(
            id = it.id,
            javaVersion = JvmTarget.fromString(it.javaVersion)!!,
            isJdk = it.isJdk!!,
            contentRoots = it.contentRoots.map { Path(it) },
            sourceRoots = it.sourceRoots?.map { Path(it) },
            dependencies = deps,
            project = project,
            appEnvironment = appEnvironment,
        )
    }
