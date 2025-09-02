package org.kotlinlsp.actions.autocomplete

import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InsertTextFormat
import org.kotlinlsp.index.db.Declaration

fun Declaration.completionKind(): CompletionItemKind =
    when (this) {
        is Declaration.EnumEntry -> CompletionItemKind.EnumMember
        is Declaration.Class -> when (type) {
            Declaration.Class.Type.CLASS -> CompletionItemKind.Class
            Declaration.Class.Type.ABSTRACT_CLASS -> CompletionItemKind.Class
            Declaration.Class.Type.INTERFACE -> CompletionItemKind.Interface
            Declaration.Class.Type.ENUM_CLASS -> CompletionItemKind.Enum
            Declaration.Class.Type.OBJECT -> CompletionItemKind.Module
            Declaration.Class.Type.ANNOTATION_CLASS -> CompletionItemKind.Interface
        }
        is Declaration.Function -> CompletionItemKind.Function
        is Declaration.Field -> CompletionItemKind.Field
        // is Declaration.Constructor -> CompletionItemKind.Constructor
        // is Declaration.TypeParameter -> CompletionItemKind.TypeParameter
    }

fun Declaration.details(): CompletionItemLabelDetails = when (this) {
    is Declaration.Class -> CompletionItemLabelDetails().apply {
        detail = " (${fqName})"
    }
    is Declaration.EnumEntry -> CompletionItemLabelDetails().apply {
        detail = ": $enumFqName"
    }
    is Declaration.Function -> CompletionItemLabelDetails().apply {
        detail = "(${parameters.joinToString(", ") { param -> "${param.name}: ${param.type}" }}): $returnType (${fqName})"
    }
    is Declaration.Field -> CompletionItemLabelDetails().apply {
        detail = ": $type (${fqName})"
    }
    // is Declaration.Constructor -> CompletionItemLabelDetails().apply {
    //     detail = "(${parameters.joinToString(", ") { param -> "${param.name}: ${param.type}" }}) (${fqName})"
    // }
    // is Declaration.TypeParameter -> CompletionItemLabelDetails().apply {
    //     detail = "<${name}> (${parentFqName})"
    // }
}

fun Declaration.insertInfo(): Pair<String, InsertTextFormat> = when (this) {
    is Declaration.Class -> name to InsertTextFormat.PlainText
    is Declaration.EnumEntry -> "${enumFqName.substringAfterLast('.')}.${name}" to InsertTextFormat.PlainText
    is Declaration.Function -> "${name}(${
        parameters.mapIndexed { index, param -> "\${${index + 1}:${param.name}}" }.joinToString(", ")
    })" to InsertTextFormat.Snippet

    is Declaration.Field -> name to InsertTextFormat.PlainText
    // is Declaration.Constructor -> "${name}(${
    //     parameters.mapIndexed { index, param -> "\${${index + 1}:${param.name}}" }.joinToString(", ")
    // })" to InsertTextFormat.Snippet
    // is Declaration.TypeParameter -> name to InsertTextFormat.PlainText
}

fun Sequence<Declaration>.filterMatchesReceiver(receiver: String): Sequence<Declaration> =
    filter {
        when (it) {
            is Declaration.Function -> it.receiverFqName == receiver || it.receiverFqName.isEmpty()
            is Declaration.Class -> true
            is Declaration.EnumEntry -> true
            is Declaration.Field -> it.parentFqName == receiver || it.parentFqName.isEmpty()
            // is Declaration.Constructor -> it.parentFqName == receiver || it.parentFqName.isEmpty()
            // is Declaration.TypeParameter -> true
        }
    }
