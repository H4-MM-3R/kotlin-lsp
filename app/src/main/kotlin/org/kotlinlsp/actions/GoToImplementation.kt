package org.kotlinlsp.actions

import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
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
        // Otherwise it must be a class method or a variable - find overriding declarations
        val callableInfo = analyze(ktElement){
            val symbol = when(ktElement) {
                is KtDeclaration -> ktElement.symbol as? KaCallableSymbol
                else -> ktElement.mainReference?.resolveToSymbol() as? KaCallableSymbol
            } ?: return@analyze null
            
            val classSymbol = symbol.containingSymbol as? KaClassSymbol ?: return@analyze null
            val containingClassId = classSymbol.classId ?: return@analyze null
            val callableId = symbol.callableId ?: return@analyze null
            
            Triple(symbol.createPointer(), callableId, containingClassId)
        } ?: return null

        val (baseCallablePtr, baseCallableId, containingClassId) = callableInfo
        val baseShortName = baseCallableId.callableName
        
        // Get inheritors and search for overriding declarations
        directInheritorsProvider
            .getDirectKotlinInheritorsByClassId(containingClassId, module, scope, true)
            .distinctBy { it.getClassId() }
            .mapNotNull { ktClass ->
                val matchingDeclarations = ktClass.declarations.filter { it.name == baseShortName.asString() }
                if (matchingDeclarations.isEmpty()) return@mapNotNull null
                
                try {
                    analyze(ktClass) {
                        val baseCallableSymbol = baseCallablePtr.restoreSymbol() ?: return@analyze null
                        
                        matchingDeclarations.firstOrNull { declaration ->
                            val declSymbol = declaration.symbol as? KaCallableSymbol ?: return@firstOrNull false
                            declSymbol.directlyOverriddenSymbols.any { it.callableId == baseCallableId }
                        }
                    }
                } catch (e: Exception) {
                    null
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

