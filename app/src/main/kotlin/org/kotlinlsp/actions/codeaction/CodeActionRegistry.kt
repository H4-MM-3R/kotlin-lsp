package org.kotlinlsp.actions.codeaction

import org.eclipse.lsp4j.CodeAction

/**
 * Registry for managing code action providers and their mappings to diagnostics
 */
class CodeActionRegistry {
    private val providers = mutableListOf<CodeActionProvider>()

    /**
     * Register a code action provider
     */
    fun registerProvider(provider: CodeActionProvider) {
        providers.add(provider)
    }

    /**
     * Get all applicable code actions for the given context
     */
    fun getCodeActions(context: CodeActionContext): List<CodeAction> {
        return providers
            .filter { it.isApplicable(context) }
            .flatMap { it.createCodeActions(context) }
            .distinctBy { it.title } // Avoid duplicate actions
    }

    /**
     * Get the number of registered providers (for testing)
     */
    fun getProviderCount(): Int = providers.size

    companion object {
        /**
         * Create and initialize the default registry with built-in providers
         */
        fun createDefault(): CodeActionRegistry {
            val registry = CodeActionRegistry()
            
            // Register default providers here as they are implemented
            // registry.registerProvider(org.kotlinlsp.actions.codeaction.providers.AddTodoCommentFactory())
            registry.registerProvider(org.kotlinlsp.actions.codeaction.providers.AddImportFactory())
            registry.registerProvider(org.kotlinlsp.actions.codeaction.providers.ImplementMethodsFactory())
            // registry.registerProvider(AddReturnExpressionFactory())
            
            return registry
        }
    }
} 