package org.kotlinlsp.analysis.services

import com.intellij.openapi.fileTypes.BinaryFileDecompiler
import com.intellij.openapi.project.DefaultProjectFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.compiled.ClsFileImpl
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinClassFileDecompiler
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache

class CustomClassDecompiler: BinaryFileDecompiler {
    override fun decompile(file: VirtualFile): CharSequence {
        if(file.extension != "class") return "${file.name} is not a class file."

        if(ClsKotlinBinaryClassCache.getInstance().isKotlinJvmCompiledFile(file, file.contentsToByteArray())){
            val manager = PsiManager.getInstance(DefaultProjectFactory.getInstance().defaultProject)
            return KotlinClassFileDecompiler().createFileViewProvider(file, manager, true).contents
        }
        return ClsFileImpl.decompile(file)
    }
}
