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
    db.sourceFilesDb.put<String>(virtualFile.url, "1")

    val psiFile = project.read { PsiManager.getInstance(project).findFile(virtualFile) }!!
    val packageFqName = when (psiFile) {
        is KtFile -> psiFile.packageFqName.asString()
        is PsiJavaFile -> {
            psiFile.packageName
        }
        else -> return
    }
    if (db.sourcesDb.get<List<String>>(packageFqName)?.contains(psiFile.virtualFile.url) == true) return

    val existing: MutableList<String> = db.sourcesDb.get<List<String>>(packageFqName)?.toMutableList() ?: mutableListOf()
    if (!existing.contains(psiFile.virtualFile.url)) {
        existing.add(psiFile.virtualFile.url)
        db.sourcesDb.put(packageFqName, existing)
    }
}