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

    val ext = virtualFile.extension?.lowercase()
    if (ext != "kt" && ext != "java") return

    val pair = project.read {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@read null
        when (psiFile) {
            is KtFile -> psiFile.packageFqName.asString() to psiFile.virtualFile.url
            is PsiJavaFile -> {
                psiFile.packageName to psiFile.virtualFile.url
            }
            else -> null
        }
    } ?: return

    val (packageFqName, fileUrl) = pair
    val key = packageFqName

    val existing: MutableList<String> = db.sourcesDb.get<List<String>>(key)?.toMutableList() ?: mutableListOf()
    if (!existing.contains(fileUrl)) {
        existing.add(fileUrl)
        db.sourcesDb.put(key, existing)
    }
}