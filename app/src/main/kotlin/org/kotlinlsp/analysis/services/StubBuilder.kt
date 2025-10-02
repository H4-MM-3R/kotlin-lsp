// Copyright 2024 Dmitrii Tseiler. Licensed under the Apache 2.0 Licence
// Modifications by Kumarapu Hemram.

@file:OptIn(KaImplementationDetail::class)
package org.kotlinlsp.analysis.services

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.impl.java.stubs.PsiJavaFileStub
import com.intellij.psi.impl.source.PsiJavaFileImpl
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.util.indexing.FileContentImpl
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.impl.base.symbols.pointers.SmartPointerIncompatiblePsiFile
import org.jetbrains.kotlin.analysis.decompiler.konan.K2KotlinNativeMetadataDecompiler
import org.jetbrains.kotlin.analysis.decompiler.konan.KlibMetaFileType
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInDecompiler
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInFileType
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.analysis.decompiler.stub.file.KotlinClsStubBuilder
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.stubs.impl.KotlinFileStubImpl
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol

class PseudoPsiFile(
    psiManager: PsiManager,
    virtualFile: VirtualFile,
    language: Language
) : SingleRootFileViewProvider(psiManager, virtualFile, false, language), SmartPointerIncompatiblePsiFile

class KotlinStubBuilder(project: Project) {
    companion object {
        fun instance(project: Project): KotlinStubBuilder = project.getService(KotlinStubBuilder::class.java)
        private val BUILTINS by lazy { KotlinBuiltInDecompiler() }
        private val KLIB by lazy { K2KotlinNativeMetadataDecompiler() }
    }

    private val psiManager = PsiManager.getInstance(project)

    fun createStub(file: VirtualFile, cache: ClsKotlinBinaryClassCache): KotlinFileStubImpl? {
        if (FileUtilRt.isTooLarge(file.length)) {
            return null
        }
        val bytes = file.contentsToByteArray()
        val fileType = file.fileType
        val stubBuilder = when {
            cache.isKotlinJvmCompiledFile(file, bytes) && fileType === JavaClassFileType.INSTANCE ->
                KotlinClsStubBuilder()

            fileType == KotlinBuiltInFileType && file.extension != BuiltInSerializerProtocol.BUILTINS_FILE_EXTENSION ->
                BUILTINS.stubBuilder

            fileType == KlibMetaFileType ->
                KLIB.stubBuilder

            else -> null
        } ?: return null

        val fileContent = FileContentImpl.createByContent(file, bytes)
        val stub = stubBuilder.buildFileStub(fileContent) as? KotlinFileStubImpl
        if (stub != null) {
            stub.psi = object : KtFile(
                PseudoPsiFile(
                    psiManager,
                    file,
                    KotlinLanguage.INSTANCE
                ), isCompiled = true) {
                override fun getStub() = stub
                override fun isPhysical() = false
            }
        }
        return stub
    }

}

class JavaStubBuilder(project: Project) {
    companion object {
        fun instance(project: Project): JavaStubBuilder {
            return project.getService(JavaStubBuilder::class.java)
        }
    }

    private val psiManager = PsiManager.getInstance(project)

    fun createStub(file: VirtualFile, cache: ClsKotlinBinaryClassCache): PsiFileStubImpl<*>? {
        val bytes = file.contentsToByteArray()
        val fileType = file.fileType
        when {
            cache.isKotlinJvmCompiledFile(file, bytes) && fileType == JavaClassFileType.INSTANCE -> return null
            fileType == KotlinBuiltInFileType
                    && file.extension != BuiltInSerializerProtocol.BUILTINS_FILE_EXTENSION -> return null
            fileType == KlibMetaFileType -> return null
            fileType != JavaClassFileType.INSTANCE -> return null
            ClassFileViewProvider.isInnerClass(file, bytes) -> return null
            file.name == "module-info.class" -> return null
        }
        val tempStub: PsiJavaFileStub = ClsFileImpl.buildFileStub(file, bytes) ?: throw IllegalStateException("Can't build stub from ${file.path}")
        val packageName = tempStub.packageName
        if (packageName.startsWith("kotlin")) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val stub = tempStub as PsiFileStubImpl<PsiJavaFile>
        stub.psi = object : PsiJavaFileImpl(
            PseudoPsiFile(
                psiManager,
                file,
                JavaLanguage.INSTANCE
            )
        ) {
            override fun getStub() = stub
            override fun isPhysical() = false
        }
        return stub
    }
}