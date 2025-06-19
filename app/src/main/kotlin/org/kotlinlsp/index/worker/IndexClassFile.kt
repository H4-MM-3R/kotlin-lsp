package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.kotlinlsp.index.db.*
import java.time.Instant

fun indexClassFile(project: Project, virtualFile: VirtualFile, db: Database) {
    // Only process .class files
    if (virtualFile.isDirectory || virtualFile.extension != "class") return

    // Extract the path inside the JAR/URL and convert it to a JVM internal name
    // Examples:
    //  • jar://path/to/lib.jar!/java/util/ArrayList.class -> java/util/ArrayList
    //  • file:///.../out/production/MyClass.class        -> .../MyClass
    val internalPath = virtualFile.url
        .substringAfter("!/")         // strip jar prefix if present
        .removePrefix("file://")
        .removeSuffix(".class")
        .trimStart('/')

    // Build the fully-qualified class name and ignore inner/anonymous classes
    val fqName = internalPath
        .replace('/', '.')
        .replace('\\', '.')
        .substringBefore('$')

    if (fqName.isBlank()) return

    val simpleName = fqName.substringAfterLast('.')

    val declaration = Declaration.Class(
        name = simpleName,
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