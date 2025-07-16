package org.kotlinlsp.actions.codeaction.providers

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.kotlinlsp.actions.codeaction.CodeActionContext
import org.kotlinlsp.actions.codeaction.CodeActionFactory
import org.kotlinlsp.common.toOffset

/**
 * Code action factory for implementing/overriding abstract and interface methods
 */
class ImplementMethodsFactory : CodeActionFactory() {

    @OptIn(KaExperimentalApi::class)
    private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES

    override fun isApplicable(context: CodeActionContext): Boolean {
        val ktClass = findClassAtCursor(context) ?: return false
        return analyze(ktClass) {
            val classSymbol = ktClass.classSymbol ?: return@analyze false
            findUnimplementedMethods(classSymbol, ktClass).isNotEmpty()
        }
    }

    override fun doCreateCodeActions(context: CodeActionContext): List<CodeAction> {
        val ktClass = findClassAtCursor(context) ?: return emptyList()
        
        return analyze(ktClass) {
            val classSymbol = ktClass.classSymbol ?: return@analyze emptyList()
            val unimplementedMethods = findUnimplementedMethods(classSymbol, ktClass)
            
            if (unimplementedMethods.isEmpty()) return@analyze emptyList()
            
            createCodeActionsForMethods(context, ktClass, unimplementedMethods)
        }
    }

    private fun findClassAtCursor(context: CodeActionContext): KtClass? {
        val startOffset = context.range.start.line * 1000 + context.range.start.character
        val endOffset = context.range.end.line * 1000 + context.range.end.character
        
        // Find the class that contains the cursor position
        val elements = context.ktFile.children.filterIsInstance<KtClass>()
        for (element in elements) {
            val elementStart = element.textRange.startOffset
            val elementEnd = element.textRange.endOffset
            if (elementStart <= startOffset && elementEnd >= endOffset) {
                return element
            }
        }
        
        // Also check if cursor is directly on a class element
        val offset = context.range.start.run { 
            try {
                toOffset(context.ktFile)
            } catch (e: Exception) {
                0
            }
        }
        val elementAtCursor = context.ktFile.findElementAt(offset)
        return elementAtCursor?.parentOfType<KtClass>()
    }

    @OptIn(KaExperimentalApi::class)
    private fun org.jetbrains.kotlin.analysis.api.KaSession.findUnimplementedMethods(
        classSymbol: KaClassSymbol,
        ktClass: KtClass
    ): List<KaCallableSymbol> {
        val unimplemented = mutableListOf<KaCallableSymbol>()
        
        try {
            for (superType in classSymbol.superTypes) {
                val superClassSymbol = superType.expandedSymbol as? KaClassSymbol ?: continue
                
                // Only process interfaces for now
                if (superClassSymbol.classKind == org.jetbrains.kotlin.analysis.api.symbols.KaClassKind.INTERFACE) {
                    val inheritedMembers = superClassSymbol.memberScope.callables.toList()
                    
                    for (inherited in inheritedMembers) {
                        // Check if this method is already implemented in the current class
                        val isAlreadyImplemented = classSymbol.memberScope.callables.any { existing ->
                            // Check if the method name matches and it's declared in this class
                            existing.callableId?.callableName == inherited.callableId?.callableName &&
                            existing.psi?.containingFile == ktClass.containingFile
                        }
                        
                        if (!isAlreadyImplemented) {
                            unimplemented.add(inherited)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to empty list if there are issues with symbol resolution
        }
        
        return unimplemented.distinctBy { it.callableId }
    }
    


    @OptIn(KaExperimentalApi::class)
    private fun org.jetbrains.kotlin.analysis.api.KaSession.createCodeActionsForMethods(
        context: CodeActionContext,
        ktClass: KtClass,
        methods: List<KaCallableSymbol>
    ): List<CodeAction> {
        val codeActions = mutableListOf<CodeAction>()
        
        // Create individual actions for each method
        for (method in methods) {
            val methodStub = generateMethodStub(method)
            val insertionPoint = findMethodInsertionPoint(ktClass)
            val indentedStub = indentMethodStub(methodStub, ktClass)
            
            val textEdit = textEditBuilder.insertAt(ktClass.containingKtFile, insertionPoint, indentedStub)
            val workspaceEdit = createWorkspaceEdit(context.uri, listOf(textEdit))
            
            val action = createCodeAction(
                title = "Implement '${method.callableId?.callableName?.asString() ?: "method"}'",
                kind = CodeActionKind.QuickFix,
                edit = workspaceEdit,
                isPreferred = false
            )
            
            codeActions.add(action)
        }
        
        // Create action to implement all methods at once
        if (methods.size > 1) {
            val allStubs = methods.joinToString("\n") { generateMethodStub(it) }
            val insertionPoint = findMethodInsertionPoint(ktClass)
            val indentedStubs = indentMethodStub(allStubs, ktClass)
            
            val textEdit = textEditBuilder.insertAt(ktClass.containingKtFile, insertionPoint, indentedStubs)
            val workspaceEdit = createWorkspaceEdit(context.uri, listOf(textEdit))
            
            val action = createCodeAction(
                title = "Implement all methods (${methods.size})",
                kind = CodeActionKind.QuickFix,
                edit = workspaceEdit,
                isPreferred = true
            )
            
            codeActions.add(action)
        }
        
        return codeActions
    }

    @OptIn(KaExperimentalApi::class)
    private fun org.jetbrains.kotlin.analysis.api.KaSession.generateMethodStub(method: KaCallableSymbol): String {
        return when (method) {
            is KaFunctionSymbol -> {
                val signature = method.render(renderer)
                val methodName = method.callableId?.callableName?.asString() ?: "unknownMethod"
                val returnType = method.returnType.render(org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource.WITH_SHORT_NAMES, position = org.jetbrains.kotlin.types.Variance.INVARIANT)
                
                if (returnType == "Unit") {
                    "override fun $methodName${extractParametersFromSignature(signature)} {\n    TODO(\"Not yet implemented\")\n}"
                } else {
                    "override fun $methodName${extractParametersFromSignature(signature)}: $returnType {\n    TODO(\"Not yet implemented\")\n}"
                }
            }
            is KaPropertySymbol -> {
                val propertyName = method.callableId?.callableName?.asString() ?: "unknownProperty"
                val returnType = method.returnType.render(org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource.WITH_SHORT_NAMES, position = org.jetbrains.kotlin.types.Variance.INVARIANT)
                
                if (method.isVal) {
                    "override val $propertyName: $returnType\n    get() = TODO(\"Not yet implemented\")"
                } else {
                    "override var $propertyName: $returnType\n    get() = TODO(\"Not yet implemented\")\n    set(value) { TODO(\"Not yet implemented\") }"
                }
            }
            else -> "// TODO: Implement ${method.callableId?.callableName?.asString() ?: "unknown"}"
        }
    }

    private fun extractParametersFromSignature(signature: String): String {
        val startParen = signature.indexOf('(')
        val endParen = signature.lastIndexOf(')')
        return if (startParen != -1 && endParen != -1 && endParen > startParen) {
            signature.substring(startParen, endParen + 1)
        } else {
            "()"
        }
    }

    private fun findMethodInsertionPoint(ktClass: KtClass): Int {
        val body = ktClass.body
        if (body != null) {
            // Insert before the closing brace
            val closingBrace = body.rBrace
            if (closingBrace != null) {
                return closingBrace.textRange.startOffset
            }
            // If no closing brace, insert at the end of the body
            return body.textRange.endOffset
        }
        
        // If no body, we need to create one
        val nameIdentifier = ktClass.nameIdentifier
        if (nameIdentifier != null) {
            return nameIdentifier.textRange.endOffset
        }
        
        return ktClass.textRange.endOffset
    }

    private fun indentMethodStub(methodStub: String, ktClass: KtClass): String {
        // Find the current indentation of the class
        val classText = ktClass.text
        val lines = classText.lines()
        val classIndent = if (lines.isNotEmpty()) {
            val firstLine = lines[0]
            firstLine.takeWhile { it.isWhitespace() }
        } else {
            ""
        }
        
        // Add one level of indentation for methods inside the class
        val methodIndent = classIndent + "    "
        
        return "\n" + methodStub.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else methodIndent + line
        } + "\n"
    }
} 