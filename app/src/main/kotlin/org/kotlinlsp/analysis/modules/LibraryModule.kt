package org.kotlinlsp.analysis.modules

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems.JAR_PROTOCOL
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.library.KLIB_FILE_EXTENSION
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.kotlinlsp.common.read
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class LibraryModule(
    override val id: String,
    val appEnvironment: KotlinCoreApplicationEnvironment,
    override val contentRoots: List<Path>,
    val javaVersion: JvmTarget,
    override val dependencies: List<Module> = listOf(),
    val isJdk: Boolean = false,
    private val project: Project,
    private val sourceModule: KaLibrarySourceModule? = null,
): Module {
    override val isSourceModule: Boolean
        get() = false

    @OptIn(KaImplementationDetail::class)
    override fun computeFiles(extended: Boolean): Sequence<VirtualFile> {
        val roots = if (isJdk) {
            // This returns urls to the JMOD files in the jdk
            project.read { LibraryUtils.findClassesFromJdkHome(contentRoots.first(), isJre = false) }
        } else {
            // These are JAR/class files
            contentRoots
        }

        val notExtendedFiles = roots
            .asSequence()
            .mapNotNull {
                getVirtualFileForLibraryRoot(it, appEnvironment, project)
            }

        if (!extended) return notExtendedFiles

        return notExtendedFiles
            .map {
                project.read { LibraryUtils.getAllVirtualFilesFromRoot(it, includeRoot = true) }
            }
            .flatten()
    }

    override val kaModule: KaModule by lazy {
        object : KaLibraryModule, KaModuleBase() {
            @KaPlatformInterface
            override val baseContentScope: GlobalSearchScope by lazy {
                val virtualFileUrls = computeFiles(extended = true).map { it.url }.toSet()

                object : GlobalSearchScope(project) {
                    override fun contains(file: VirtualFile): Boolean = file.url in virtualFileUrls

                    override fun isSearchInModuleContent(p0: com.intellij.openapi.module.Module): Boolean = false

                    override fun isSearchInLibraries(): Boolean = true

                    override fun toString(): String = virtualFileUrls.joinToString("\n") {
                        it
                    }
                }
            }

            override val binaryRoots: Collection<Path>
                get() = contentRoots

            @KaExperimentalApi
            override val binaryVirtualFiles: Collection<VirtualFile>
                get() = emptyList() // Not supporting in-memory libraries
            override val directDependsOnDependencies: List<KaModule>
                get() = emptyList() // Not supporting KMP right now
            override val directFriendDependencies: List<KaModule>
                get() = emptyList() // No support for this right now
            override val directRegularDependencies: List<KaModule>
                get() = dependencies.map { it.kaModule }

            @KaPlatformInterface
            override val isSdk: Boolean
                get() = isJdk
            override val libraryName: String
                get() = id
            override val librarySources: KaLibrarySourceModule?
                get() = sourceModule
            override val project: Project
                get() = this@LibraryModule.project
            override val targetPlatform: TargetPlatform
                get() = JvmPlatforms.jvmPlatformByTargetVersion(javaVersion)
        }
    }
}

private const val JAR_SEPARATOR = "!/"

private fun getVirtualFileForLibraryRoot(
    root: Path,
    environment: CoreApplicationEnvironment,
    project: Project
): VirtualFile? {
    var pathString = root.absolutePathString()

    // .jar or .klib files
    if (pathString.endsWith(JAR_PROTOCOL) || pathString.endsWith(KLIB_FILE_EXTENSION)) {
        return project.read { environment.jarFileSystem.findFileByPath(pathString + JAR_SEPARATOR) }
    }

    // JDK classes
    if (pathString.contains("!")) {
        if(!pathString.contains(JAR_SEPARATOR)) {
            pathString = pathString.replace("\\", "/")
        }
        val (libHomePath, pathInImage) = CoreJrtFileSystem.splitPath(pathString)
        val adjustedPath = "$libHomePath!/modules/$pathInImage"
        return project.read { environment.jrtFileSystem?.findFileByPath(adjustedPath) }
    }

    // Regular .class file
    return project.read { VirtualFileManager.getInstance().findFileByNioPath(root) }
}
