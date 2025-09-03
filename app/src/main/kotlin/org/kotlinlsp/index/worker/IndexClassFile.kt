package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.kotlinlsp.index.db.*
import java.time.Instant

fun indexClassFile(project: Project, virtualFile: VirtualFile, db: Database) {
    // Only process .class files
    if (virtualFile.isDirectory || virtualFile.extension != "class") return

    // Extract the path inside the JAR/URL and convert it to a JVM internal name
    val internalPath = virtualFile.url
        .substringAfter("!/")         // strip jar prefix if present
        .removePrefix("file://")
        .removeSuffix(".class")
        .trimStart('/')

    // Ignore inner/anonymous classes
    if (internalPath.contains("$")) return

    val fqName = internalPath
        .replace('/', '.')
        .replace('\\', '.')

    if (fqName.isBlank()) return

    val simpleName = fqName.substringAfterLast('.')

    // Filter out noisy or meaningless classes:
    //  - Kotlin file facades (FooKt), metadata classes (package-info, module-info)
    //  - Obfuscated/lowercase names (a, b, k, kg, km…)
    //  - Very short names (<= 1 char)
    //  - Known noisy packages (fastutil, checkerframework.units.qual, etc.)
    val noisyPackages = listOf(
        "it.unimi.dsi.fastutil",
        "org.checkerframework.checker.units.qual"
    )

    val looksLikeApiClass =
        simpleName.isNotEmpty() &&
        simpleName[0].isUpperCase() &&
        simpleName.length > 1

    if (!looksLikeApiClass ||
        simpleName.endsWith("Kt") ||
        simpleName == "package-info" ||
        simpleName == "module-info" ||
        noisyPackages.any { fqName.startsWith(it) }
    ) {
        return
    }

    val declaration = Declaration.Class(
        name = simpleName,
        isTopLevel = true,
        isPrivate = true,
        type = Declaration.Class.Type.CLASS,
        fqName = fqName,
        file = virtualFile.url,
        startOffset = -1,
        endOffset = -1
    )

    db.putDeclarations(listOf(declaration))

    val fileRecord = File(
        path = virtualFile.url,
        packageFqName = fqName.substringBeforeLast('.', ""),
        lastModified = Instant.ofEpochMilli(virtualFile.timeStamp),
        modificationStamp = 0L,
        indexed = true,
        declarationKeys = mutableListOf(declaration.id())
    )
    db.setFile(fileRecord)
}