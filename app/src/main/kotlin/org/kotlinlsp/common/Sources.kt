package org.kotlinlsp.common

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.sourcesForPackage

public fun KaSession.findSourceSymbols(ktFile: KtFile, ref: KtReference,  index: Index): List<PsiElement> {
    val symbol = ref.resolveToSymbols().firstOrNull()
    val packageName: String?
    val symbolName: String?
    var comparerClass: Class<*>

    when(symbol) {
        is KaCallableSymbol -> {
            packageName = symbol.callableId?.packageName?.asString() ?: return emptyList()
            symbolName = symbol.callableId?.callableName?.asString() ?: return emptyList()
            comparerClass = PsiMethod::class.java
        }
        is KaClassSymbol -> {
            packageName = symbol.classId?.packageFqName?.asString() ?: return emptyList()
            symbolName = symbol.classId?.shortClassName?.asString() ?: return emptyList()
            comparerClass = PsiClass::class.java
        }
        else -> {
            return emptyList()
        }
    }

    val file2 = index.sourcesForPackage(FqName(packageName))
    var possibleVal: List<PsiElement> =  file2.map { ele -> VirtualFileManager.getInstance().findFileByUrl(ele) }
        .map{ element -> PsiManager.getInstance(ktFile.project).findFile(element!!) }
        .map { element -> PsiTreeUtil.collectElementsOfType(element, comparerClass) }
        .flatten()
        .filter { element -> element.name == symbolName }
    if (possibleVal.isEmpty()) {
        if(comparerClass == PsiMethod::class.java) comparerClass = KtNamedFunction::class.java
        else if(comparerClass == PsiClass::class.java) comparerClass = KtClassOrObject::class.java

        possibleVal = file2.map { ele -> VirtualFileManager.getInstance().findFileByUrl(ele) }
            .map{ element -> PsiManager.getInstance(ktFile.project).findFile(element!!) }
            .map { element -> PsiTreeUtil.collectElementsOfType(element, comparerClass) }
            .flatten()
            .filter { element -> element.name == symbolName }
    }
    return possibleVal
}