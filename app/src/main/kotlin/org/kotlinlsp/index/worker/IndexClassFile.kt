package org.kotlinlsp.index.worker

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.impl.compiled.ClassFileDecompiler
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.isAbstract
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.psi.psiUtil.isProtected
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
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
                    Declaration.Function(
                        childStub.name,
                        childStub.getFqName().toString(),
                        childStub.isExtension(),
                        childStub.isTopLevel(),
                        childStub.psi.isPrivate() || childStub.psi.isProtected(),
                        virtualFile.url,
                        childStub.psi.textOffset,
                        childStub.psi.textOffset + childStub.name.length,
                        childStub.psi.valueParameters.map {
                            Declaration.Function.Parameter(
                                it.nameAsSafeName.asString(),
                                it.typeReference?.getShortTypeText() ?: ""
                            )
                        },
                        childStub.psi.typeReference?.getShortTypeText() ?: "",
                        "",
                        childStub.psi.receiverTypeReference?.getShortTypeText() ?: ""
                    )
                }
                is KotlinClassStubImpl -> {
                    if (childStub.psi is KtEnumEntry){
                        declarations.add(Declaration.EnumEntry(
                            childStub.name ?: "",
                            childStub.getFqName().toString(),
                            virtualFile.url,
                            childStub.psi.textOffset,
                            childStub.psi.textOffset + (childStub.name?.length ?: 0),
                            childStub.parentStub.psi.parentOfType<KtClass>()?.fqName?.asString() ?: ""
                        ))
                        return@forEach
                    }

                    val type = if (childStub.psi.isEnum()) {
                        Declaration.Class.Type.ENUM_CLASS
                    } else if (childStub.psi.isAnnotation()) {
                        Declaration.Class.Type.ANNOTATION_CLASS
                    } else if (childStub.psi.isInterface()) {
                        Declaration.Class.Type.INTERFACE
                    } else if (childStub.psi.isAbstract()) {
                        Declaration.Class.Type.ABSTRACT_CLASS
                    } else {
                        Declaration.Class.Type.CLASS
                    }

                    Declaration.Class(
                        childStub.psi.name ?: "",
                        childStub.psi.isTopLevelKtOrJavaMember(),
                        childStub.psi.isPrivate() || childStub.psi.isProtected(),
                        type,
                        childStub.getFqName().toString(),
                        virtualFile.url,
                        childStub.psi.textOffset,
                        childStub.psi.textOffset + (childStub.name?.length ?: 0),
                    )

                }
                is KotlinPropertyStubImpl -> {
                    if (childStub.psi.isLocal) return@forEach
                    val clazz = childStub.psi.parentOfType<KtClass>() ?:
                    {
                        declarations.add(
                            Declaration.Field(
                                childStub.psi.name ?: "",
                                childStub.getFqName().toString(),
                                childStub.psi.isExtensionDeclaration(),
                                childStub.psi.isTopLevelKtOrJavaMember(),
                                virtualFile.url,
                                childStub.psi.textOffset,
                                childStub.psi.textOffset + (childStub.name?.length ?: 0),
                                childStub.psi.typeReference?.getShortTypeText() ?: "",
                                ""
                            )
                        )
                    }

                    Declaration.Field(
                        childStub.psi.name ?: "",
                        childStub.getFqName().toString(),
                        childStub.psi.isExtensionDeclaration(),
                        childStub.psi.isTopLevelKtOrJavaMember(),
                        virtualFile.url,
                        childStub.psi.textOffset,
                        childStub.psi.textOffset + (childStub.name?.length ?: 0),
                        childStub.psi.typeReference?.getShortTypeText() ?: "",
                        ""
                    )
                }
                is KotlinParameterStubImpl -> {
                    if (!childStub.psi.hasValOrVar()) return@forEach
                    val constructor = childStub.psi.parentOfType<KtPrimaryConstructor>() ?: return@forEach
                    val clazz = constructor.parentOfType<KtClass>() ?: return@forEach

                    Declaration.Field(
                        childStub.name ?: "",
                        childStub.getFqName().toString(),
                        childStub.psi.isExtensionDeclaration(),
                        childStub.psi.isTopLevelKtOrJavaMember(),
                        virtualFile.url,
                        childStub.psi.textOffset,
                        childStub.psi.textOffset + (childStub.name?.length ?: 0),
                        childStub.psi.typeReference?.getShortTypeText() ?: "",
                        clazz.fqName?.asString() ?: ""
                    )
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