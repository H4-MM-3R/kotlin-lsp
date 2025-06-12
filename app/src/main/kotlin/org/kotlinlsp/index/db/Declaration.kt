package org.kotlinlsp.index.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kotlinlsp.index.db.adapters.put

@Serializable
sealed class Declaration() {
    abstract val name: String
    abstract val file: String
    abstract val startOffset: Int
    abstract val endOffset: Int

    @Serializable
    @SerialName("function")
    data class Function(
        override val name: String,
        val fqName: String,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val parameters: List<Parameter>,
        val returnType: String,
        val parentFqName: String,
        val receiverFqName: String,
    ) : Declaration() {
        @Serializable
        data class Parameter(
            val name: String,
            val type: String,
        )
    }

    @Serializable
    @SerialName("class")
    data class Class(
        override val name: String,
        val type: Type,
        val fqName: String,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : Declaration() {
        enum class Type {
            CLASS,
            ABSTRACT_CLASS,
            INTERFACE,
            ENUM_CLASS,
            OBJECT,
            ANNOTATION_CLASS,
        }
    }

    @Serializable
    @SerialName("enumEntry")
    data class EnumEntry(
        override val name: String,
        val fqName: String,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val enumFqName: String,
    ) : Declaration()

    @Serializable
    @SerialName("field")
    data class Field(
        override val name: String,
        val fqName: String,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val type: String,
        val parentFqName: String,
    ) : Declaration()

    @Serializable
    @SerialName("constructor")
    data class Constructor(
        override val name: String,
        val fqName: String,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val parameters: List<Parameter>,
        val parentFqName: String,
    ) : Declaration() {
        @Serializable
        data class Parameter(
            val name: String,
            val type: String,
        )
    }
}

fun Declaration.id() = "${name}:${file}:${startOffset}:${endOffset}"

fun Database.putDeclarations(declarations: Iterable<Declaration>) {
    declarationsDb.put(declarations.map { Pair(it.id(), it) })
}
