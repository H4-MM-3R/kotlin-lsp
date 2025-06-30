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
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.analysis.services.DirectInheritorsProvider
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset

private data class CallableData(
    val pointer: KaSymbolPointer<KaCallableSymbol>,
    val callableId: CallableId
)

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
        val callableData = analyze(ktElement){
            val symbol =
                if(ktElement is KtDeclaration) ktElement.symbol as? KaCallableSymbol ?: return null
                else ktElement.mainReference?.resolveToSymbol() as? KaCallableSymbol ?: return null
            val classSymbol = symbol.containingSymbol as? KaClassSymbol ?: return null
            val containingClassId = classSymbol.classId ?: return null
            val callableId = symbol.callableId ?: return null
            
            Pair(CallableData(symbol.createPointer(), callableId), containingClassId)
        } ?: return null

        val (baseCallableData, containingClassId) = callableData
        val baseCallablePtr = baseCallableData.pointer
        val baseCallableId = baseCallableData.callableId
        val baseShortName = baseCallableId.callableName
        
        // Get distinct inheritors by classId to avoid processing duplicates
        val distinctInheritors = directInheritorsProvider
            .getDirectKotlinInheritorsByClassId(containingClassId, module, scope, true)
            .distinctBy { it.getClassId() }
        
        // Search for overriding declarations using single analysis session per inheritor
        distinctInheritors.mapNotNull { ktClass ->
            try {
                analyze(ktClass) {
                    // Restore the base callable symbol once per analysis session
                    val baseCallableSymbol = baseCallablePtr.restoreSymbol() ?: return@analyze null
                    
                    // Find declarations in this class that match the callable name (pre-filter)
                    val matchingDeclarations = ktClass.declarations.filter { declaration ->
                        declaration.name == baseShortName.asString()
                    }
                    
                    // Check which of the matching declarations actually overrides the base callable
                    matchingDeclarations.firstOrNull { declaration ->
                        val declarationSymbol = declaration.symbol as? KaCallableSymbol ?: return@firstOrNull false
                        
                        // Use built-in override checking instead of manual signature comparison
                        declarationSymbol.directlyOverriddenSymbols.any { overriddenSymbol ->
                            // Check if this symbol is the same as our base callable
                            // We compare by callableId as a lightweight check first
                            if (overriddenSymbol.callableId != baseCallableId) return@any false
                            
                            // For more thorough comparison, we could use symbol equality,
                            // but callableId comparison should be sufficient for most cases
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently ignore analysis errors for robustness
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

