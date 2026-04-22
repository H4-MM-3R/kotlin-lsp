/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

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
