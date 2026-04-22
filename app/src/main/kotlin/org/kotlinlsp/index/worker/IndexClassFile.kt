/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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
import java.net.URI
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.appendText

fun indexClassFile(project: Project, virtualFile: VirtualFile, db: Database) {
    // Only process .class files
    if (virtualFile.isDirectory || virtualFile.extension != "class") return
    
    // CRITICAL: Use virtualFile.url consistently (not .path)
    if(db.file(virtualFile.url)?.indexed == true) return
    
    // PERFORMANCE: Fast path - extract FQN from path first
    val internalPath = virtualFile.url
        .substringAfter("!/")
        .removePrefix("file://")
        .removeSuffix(".class")
        .trimStart('/')
    
    // Skip inner/anonymous classes
    if (internalPath.contains("$")) return
    
    val fqName = internalPath.replace('/', '.').replace('\\', '.')
    if (fqName.isBlank()) return
    
    val simpleName = fqName.substringAfterLast('.')
    
    val declarations = mutableListOf<Declaration>()

    // Full stub-based indexing for important API classes
    val kotlinStubBuilder = KotlinStubBuilder.instance(project)
    val javaStubBuilder = JavaStubBuilder.instance(project)
    val binaryClassCache = ClsKotlinBinaryClassCache.getInstance()

    val stub = kotlinStubBuilder.createStub(virtualFile, binaryClassCache) ?: javaStubBuilder.createStub(virtualFile, binaryClassCache)

    if (stub != null) {
        val children = stub.childrenStubs
        children.forEach { childStub ->
            when(childStub){
                is KotlinFunctionStubImpl -> {
                    declarations.add(
                        Declaration.Function(
                            childStub.name,
                            childStub.getFqName().toString(),
                            childStub.isExtension(),
                            childStub.isTopLevel(),
                            childStub.psi.isPrivate() || childStub.psi.isProtected(),
                            virtualFile.url,
                            0,
                            0 + childStub.name.length,
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
                    )
                }
                is KotlinClassStubImpl -> {
                    if (childStub.psi is KtEnumEntry){
                        declarations.add(Declaration.EnumEntry(
                            childStub.name ?: "",
                            childStub.getFqName().toString(),
                            virtualFile.url,
                            0,
                            0 + (childStub.name?.length ?: 0),
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

                    declarations.add(
                        Declaration.Class(
                            childStub.psi.name ?: "",
                            childStub.psi.isTopLevelKtOrJavaMember(),
                            childStub.psi.isPrivate() || childStub.psi.isProtected(),
                            type,
                            childStub.getFqName().toString(),
                            virtualFile.url,
                            0,
                            (childStub.name?.length ?: 0),
                        )
                    )

                }
                is KotlinPropertyStubImpl -> {
                    if (childStub.psi.isLocal) return@forEach
                    val clazz = childStub.psi.parentOfType<KtClass>()
                    
                    declarations.add(
                        Declaration.Field(
                            childStub.psi.name ?: "",
                            childStub.getFqName().toString(),
                            childStub.psi.isExtensionDeclaration(),
                            childStub.psi.isTopLevelKtOrJavaMember(),
                            virtualFile.url,
                            0,
                            0 + (childStub.name?.length ?: 0),
                            childStub.psi.typeReference?.getShortTypeText() ?: "",
                            clazz?.fqName?.asString() ?: ""
                        )
                    )
                }
                is KotlinParameterStubImpl -> {
                    if (!childStub.psi.hasValOrVar()) return@forEach
                    val constructor = childStub.psi.parentOfType<KtPrimaryConstructor>() ?: return@forEach
                    val clazz = constructor.parentOfType<KtClass>() ?: return@forEach

                    declarations.add(
                        Declaration.Field(
                            childStub.name ?: "",
                            childStub.getFqName().toString(),
                            childStub.psi.isExtensionDeclaration(),
                            childStub.psi.isTopLevelKtOrJavaMember(),
                            virtualFile.url,
                            0,
                            0 + (childStub.name?.length ?: 0),
                            childStub.psi.typeReference?.getShortTypeText() ?: "",
                            clazz.fqName?.asString() ?: ""
                        )
                    )
                }
            }
        }
    } else {
        // Stub creation failed - add lightweight class declaration
        declarations.add(Declaration.Class(
            name = simpleName,
            isTopLevel = true,
            isPrivate = false,
            type = Declaration.Class.Type.CLASS,
            fqName = fqName,
            file = virtualFile.url,
            startOffset = -1,
            endOffset = -1
        ))
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
