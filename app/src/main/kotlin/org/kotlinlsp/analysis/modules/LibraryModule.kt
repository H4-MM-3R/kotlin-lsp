package org.kotlinlsp.analysis.modules

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileProvider
import com.intellij.openapi.vfs.StandardFileSystems.JAR_PROTOCOL
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.ArchiveHandler
import com.intellij.openapi.vfs.local.CoreLocalFileSystem
import com.intellij.openapi.vfs.local.CoreLocalVirtualFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.common.localfs.KotlinLocalVirtualFile
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.library.KLIB_FILE_EXTENSION
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.kotlinlsp.common.read
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class LibraryModule(
    override val id: String,
    val appEnvironment: KotlinCoreApplicationEnvironment,
    override val contentRoots: List<Path>,
    override val sourceRoots: List<Path>?,
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

    @OptIn(KaImplementationDetail::class)
    fun computeSources(): Sequence<VirtualFile> {
        val roots = sourceRoots ?: return emptySequence()

        if (isJdk) {
            // JDK sources come as a single src.zip. We need to mount it and return
            // the module directory roots such as java.* and jdk.* inside the archive.
            val srcZip = roots.firstOrNull() ?: return emptySequence()
            val zipRoot = getVirtualFileForLibraryRoot(srcZip, appEnvironment, project) ?: return emptySequence()
            return project.read {
                zipRoot.children
                    ?.asSequence()
                    ?.filter { it.isDirectory && (it.name.startsWith("java.") || it.name.startsWith("jdk.")) }
                    ?.map { project.read {
                        LibraryUtils.getAllVirtualFilesFromRoot(it, true)
                    } }?.flatten()
                    ?: emptySequence()
            }
        }

        // Non-JDK sources: mount the -sources.jar and traverse all files under it
        return roots
            .asSequence()
            .mapNotNull { getVirtualFileForLibraryRoot(it, appEnvironment, project) }
            .map { project.read { LibraryUtils.getAllVirtualFilesFromRoot(it, includeRoot = true) } }
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

    // .jar, .zip or .klib files
    if (pathString.endsWith(JAR_PROTOCOL) || pathString.endsWith(KLIB_FILE_EXTENSION) || pathString.endsWith(".zip") || pathString.endsWith("zip")) {
        // Skip non-existent archives (e.g., AGP intermediates like R.jar not generated yet)
        if (!Files.exists(root)) return null
        // Normalize path separators for JarFileSystem
        if(!pathString.contains(JAR_SEPARATOR)) {
            pathString = pathString.replace("\\", "/")
        }
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
    if (!Files.exists(root)) return null
    return project.read { VirtualFileManager.getInstance().findFileByNioPath(root) }
}
