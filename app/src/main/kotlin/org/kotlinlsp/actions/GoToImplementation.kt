package org.kotlinlsp.actions

import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.analysis.services.DirectInheritorsProvider
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset

fun goToImplementationAction(ktFile: KtFile, position: Position): List<Location>? {
    val directInheritorsProvider = ktFile.project.getService(KotlinDirectInheritorsProvider::class.java) as DirectInheritorsProvider
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    val module = KotlinProjectStructureProvider.getModule(ktFile.project, ktElement, useSiteModule = null)
    val scope = ProjectScope.getContentScope(ktFile.project)

    val classId = analyze(ktElement){
        val symbol =
            if (ktElement is KtClass) ktElement.classSymbol ?: return@analyze null
            else ktElement.mainReference?.resolveToSymbol() as? KaClassSymbol ?: return@analyze null
        symbol.classId
    }

    val inheritors = if(classId != null){
        // If it's a class, we find its inheritors directly
        directInheritorsProvider.getDirectKotlinInheritorsByClassId(classId, module, scope, true)
    } else {
        // Otherwise it must be a class method or a variable
        // In this case, we need to search for the overridden declarations among the inheritors of the containing class
        val (callablePointer, containingClassId) = analyze(ktElement){
            val symbol =
                if(ktElement is KtDeclaration) ktElement.symbol as? KaCallableSymbol ?: return null
                else ktElement.mainReference?.resolveToSymbol() as? KaCallableSymbol ?: return null
            val classSymbol = symbol.containingSymbol as? KaClassSymbol ?: return null
            val classId = classSymbol.classId ?: return null
            Pair(symbol.createPointer(), classId)
        }

        directInheritorsProvider.getDirectKotlinInheritorsByClassId(containingClassId, module, scope, true)
            .mapNotNull { ktClass ->
                ktClass.declarations.firstOrNull { declaration ->
                    analyze(declaration){
                        val declarationSymbol = declaration.symbol as? KaCallableSymbol ?: return@analyze false
                        val callableSymbol = callablePointer.restoreSymbol() ?: return@analyze false
                        declarationSymbol.directlyOverriddenSymbols.any { isSignatureEqual(it, callableSymbol) }
                    }
                }
            }
    }

    return inheritors.map {
        Location().apply {
            uri = it.containingFile.virtualFile.url
            range = it.textRange.toLspRange(it.containingFile)
        }
    }
}

private fun KaSession.isSignatureEqual(
    symbol1: KaCallableSymbol,
    symbol2: KaCallableSymbol
): Boolean =
    when {
        symbol1 is KaFunctionSymbol && symbol2 is KaFunctionSymbol ->
            symbol1.callableId == symbol2.callableId &&
                    symbol1.valueParameters.size == symbol2.valueParameters.size &&
                    symbol1.valueParameters.zip(symbol2.valueParameters).all { (p1, p2) ->
                        p1.returnType.semanticallyEquals(p2.returnType)
                    }
        symbol1 is KaVariableSymbol && symbol2 is KaVariableSymbol -> symbol1.callableId == symbol2.callableId
        else -> false
    }

