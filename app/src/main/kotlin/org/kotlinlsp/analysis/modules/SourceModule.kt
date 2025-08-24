package org.kotlinlsp.analysis.modules

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.kotlinlsp.common.read
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class SourceModule(
    override val id: String,
    override val contentRoots: List<Path>,
    override val dependencies: List<Module>,
    val javaVersion: JvmTarget,
    val kotlinVersion: LanguageVersion,
    private val project: Project,
) : Module {
    override val sourceRoots: List<Path>?
        get() = contentRoots // For source modules, sourceRoots are the same as contentRoots
    override val isSourceModule: Boolean
        get() = true

    override fun computeFiles(extended: Boolean): Sequence<VirtualFile> =
        contentRoots
            .asSequence()
            .map { File(it.absolutePathString()).walk() }
            .flatten()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { "file://${it.absolutePath}" }
            .mapNotNull { project.read { VirtualFileManager.getInstance().findFileByUrl(it) } }

    override val kaModule: KaModule by lazy {
        object : KaSourceModule, KaModuleBase() {
            private val scope: GlobalSearchScope by lazy {
                val files = computeFiles(extended = true).toList()

                GlobalSearchScope.filesScope(this@SourceModule.project, files)
            }

            @KaPlatformInterface
            override val baseContentScope: GlobalSearchScope
                get() = scope
            override val directDependsOnDependencies: List<KaModule>
                get() = emptyList() // Not supporting KMP right now
            override val directFriendDependencies: List<KaModule>
                get() = emptyList() // No support for this right now
            override val directRegularDependencies: List<KaModule>
                get() = dependencies.map { it.kaModule }
            override val languageVersionSettings: LanguageVersionSettings
                get() = LanguageVersionSettingsImpl(kotlinVersion, ApiVersion.createByLanguageVersion(kotlinVersion))

            @KaExperimentalApi
            override val moduleDescription: String
                get() = "Source module: $name"
            override val name: String
                get() = id
            override val project: Project
                get() = this@SourceModule.project
            override val targetPlatform: TargetPlatform
                get() = JvmPlatforms.jvmPlatformByTargetVersion(javaVersion)
        }
    }
}
