package org.kotlinlsp.index.worker

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.impl.compiled.ClassFileDecompiler
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.psi.stubs.impl.KotlinClassStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinFunctionStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinParameterStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinPropertyStubImpl
import org.kotlinlsp.analysis.services.JavaStubBuilder
import org.kotlinlsp.analysis.services.KotlinStubBuilder
import org.kotlinlsp.index.db.*
import java.time.Instant

fun indexClassFile(project: Project, virtualFile: VirtualFile, db: Database) {
    // Only process .class files
    if (virtualFile.isDirectory || virtualFile.extension != "class") return

    val declarations = mutableListOf<Declaration>()
    val kotlinStubBuilder = KotlinStubBuilder.instance(project);
    val javaStubBuilder = JavaStubBuilder.instance(project);
    val binaryClassCache = ClsKotlinBinaryClassCache.getInstance()

    BinaryFileTypeDecompilers.getInstance().addExplicitExtension(JavaClassFileType.INSTANCE, ClassFileDecompiler())

    val stub = kotlinStubBuilder.createStub(virtualFile, binaryClassCache) ?: javaStubBuilder.createStub(virtualFile, binaryClassCache)
    var fqName = ""

    if (stub != null) {
        val children = stub.childrenStubs
        children.forEach { childStub ->
            when(childStub){
                is KotlinFunctionStubImpl -> {
                    // TODO: Function

                    /*
                    val fqName = childStub.getFqName()
                    val isExtension = childStub.isExtension()
                    val isTopLevel = childStub.isTopLevel()
                    val isPrivate = childStub.psi.isPrivate()
                    val file = virtualFile.url
                    val parameters = childStub.psi.valueParameters // needs further investigation
                    val returnType = childStub.psi.typeReference?.getShortTypeText() ?: ""
                    val parentFqName = ""
                    val receiverFqName = childStub.psi.receiverTypeReference?.getShortTypeText() ?: ""
                     */
                }
                is KotlinClassStubImpl -> {
                    // TODO: Class
                }
                is KotlinPropertyStubImpl -> {
                    // TODO: Property
                }
                is KotlinParameterStubImpl -> {
                    // TODO: Parameter
                }
            }
        }
    }
    else {

        // Extract the path inside the JAR/URL and convert it to a JVM internal name
        val internalPath = virtualFile.url
            .substringAfter("!/")         // strip jar prefix if present
            .removePrefix("file://")
            .removeSuffix(".class")
            .trimStart('/')

        // Ignore inner/anonymous classes
        if (internalPath.contains("$")) return

        fqName = internalPath
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
        declarations.add(declaration)
    }

    db.putDeclarations(declarations)

    val fileRecord = File(
        path = virtualFile.url,
        packageFqName = fqName.substringBeforeLast('.', ""),
        lastModified = Instant.ofEpochMilli(virtualFile.timeStamp),
        modificationStamp = 0L,
        indexed = true,
        declarationKeys = declarations.map { it.id() }.toMutableList()
    )
    db.setFile(fileRecord)
}