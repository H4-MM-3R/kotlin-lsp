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
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.common.read
import org.kotlinlsp.index.db.Database
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.adapters.put

fun indexSourceFile(project: Project, virtualFile: VirtualFile, db: Database){
    if (virtualFile.isDirectory) return
    if (db.sourceFilesDb.get<String>(virtualFile.url) != null) return

    val ext = virtualFile.extension?.lowercase()
    if (ext != "kt" && ext != "java") return

    val psiFile = project.read { PsiManager.getInstance(project).findFile(virtualFile) }!!
    val packageFqName = when (psiFile) {
        is KtFile -> psiFile.packageFqName.asString()
        is PsiJavaFile -> {
            psiFile.packageName
        }
        else -> return
    }

    db.addSourceFile(packageFqName, psiFile.virtualFile.url)
    db.sourceFilesDb.put<String>(virtualFile.url, "1")
}