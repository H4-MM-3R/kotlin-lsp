package org.kotlinlsp.actions

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.psi.*
import org.kotlinlsp.common.toLspRange

fun documentSymbolsAction(ktFile: KtFile): List<DocumentSymbol> {
    return ktFile.declarations.mapNotNull { declaration ->
        toDocumentSymbol(declaration)
    }
}

private fun toDocumentSymbol(declaration: KtDeclaration): DocumentSymbol? {
    val name = getDeclarationNameWithSignature(declaration) ?: return null
    val kind = getSymbolKind(declaration)
    val range = declaration.textRange.toLspRange(declaration.containingFile)

    // For the selection range, try to get the name identifier range, fallback to full range
    val selectionRange = try {
        when (declaration) {
            is KtNamedDeclaration -> {
                val nameIdentifier = declaration.nameIdentifier
                nameIdentifier?.textRange?.toLspRange(declaration.containingFile) ?: range
            }
            else -> range
        }
    } catch (e: Exception) {
        range
    }

    val documentSymbol = DocumentSymbol().apply {
        this.name = name
        this.kind = kind
        this.range = range
        this.selectionRange = selectionRange
        this.children = getChildSymbols(declaration)
    }

    return documentSymbol
}

@OptIn(KaExperimentalApi::class, KaExperimentalApi::class)
private fun getDeclarationNameWithSignature(declaration: KtDeclaration): String? {
    val originalName = declaration.name ?: return null
    return when (declaration) {
        is KtNamedFunction -> {
            val receiver = declaration.receiverTypeReference?.text?.let { "$it." } ?: ""
            val params = declaration.valueParameters.joinToString(", ") { param ->
                param.typeReference?.text ?: (param.name ?: "")
            }
            val returnType = declaration.typeReference?.text?.let { ": $it" } ?: analyze(declaration){
                val referenceText = declaration.symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
                val afterColon = referenceText.substringAfter("): ", "")
                if (afterColon.isNotEmpty()) ": $afterColon" else ": Unit"
            }
            "$receiver$originalName($params)$returnType"
        }
        is KtProperty -> {
            val type = declaration.typeReference?.text?.let { ": $it" } ?: ""
            "$originalName$type"
        }
        is KtParameter -> {
            if (declaration.hasValOrVar()) {
                val type = declaration.typeReference?.text?.let { ": $it" } ?: ""
                "$originalName$type"
            } else {
                originalName
            }
        }
        is KtObjectDeclaration -> {
            if (declaration.isCompanion()) {
                "companion object"
            } else {
                "object $originalName"
            }
        }
        else -> originalName
    }
}

private fun getChildSymbols(declaration: KtDeclaration): List<DocumentSymbol> {
    val children = mutableListOf<DocumentSymbol>()

    when (declaration) {
        is KtClassOrObject -> {
            // Add all declarations inside the class/object
            declaration.declarations.mapNotNullTo(children) { toDocumentSymbol(it) }

            // Add primary constructor parameters that are properties
            if (declaration is KtClass) {
                declaration.primaryConstructor?.valueParameters?.forEach { param ->
                    if (param.hasValOrVar()) {
                        toDocumentSymbol(param)?.let { children.add(it) }
                    }
                }
            }
        }
        is KtFunction -> {
            // Functions can have local classes and functions
            declaration.bodyBlockExpression?.statements?.forEach { statement ->
//              if (statement is KtDeclaration) {
//                  toDocumentSymbol(statement)?.let { children.add(it) }
//              }
            }
        }
        is KtProperty -> {
            // Properties can have custom getters and setters
            declaration.getter?.let { getter ->
                children.add(DocumentSymbol().apply {
                    name = "get"
                    kind = SymbolKind.Method
                    range = getter.textRange.toLspRange(declaration.containingFile)
                    selectionRange = range
                    this.children = emptyList()
                })
            }
            declaration.setter?.let { setter ->
                children.add(DocumentSymbol().apply {
                    name = "set"
                    kind = SymbolKind.Method
                    range = setter.textRange.toLspRange(declaration.containingFile)
                    selectionRange = range
                    this.children = emptyList()
                })
            }
        }
    }

    return children
}

private fun getSymbolKind(declaration: KtDeclaration): SymbolKind {
    return when (declaration) {
        is KtClass -> when {
            declaration.isInterface() -> SymbolKind.Interface
            declaration.isEnum() -> SymbolKind.Enum
            declaration.isData() -> SymbolKind.Struct
            else -> SymbolKind.Class
        }
        is KtObjectDeclaration -> SymbolKind.Object
        is KtNamedFunction -> when {
            declaration.isTopLevel -> SymbolKind.Function
            else -> SymbolKind.Method
        }
        is KtPrimaryConstructor -> SymbolKind.Constructor
        is KtSecondaryConstructor -> SymbolKind.Constructor
        is KtProperty -> when {
            declaration.isTopLevel -> SymbolKind.Variable
            declaration.isVar -> SymbolKind.Field
            else -> SymbolKind.Property
        }
        is KtPropertyAccessor -> SymbolKind.Method
        is KtParameter -> when {
            declaration.hasValOrVar() -> SymbolKind.Property
            else -> SymbolKind.Variable
        }
        is KtTypeAlias -> SymbolKind.Class
        is KtTypeParameter -> SymbolKind.TypeParameter
        else ->  SymbolKind.Variable 
    }
} 

