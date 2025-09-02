package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isAbstract
import org.kotlinlsp.common.read
import org.kotlinlsp.common.warn
import org.kotlinlsp.index.db.*

fun indexKtFile(project: Project, ktFile: KtFile, db: Database) {
    val newFile = File.fromKtFile(ktFile, project, indexed = true)

    // Check if skipping the indexing of that file
    val existingFile = db.file(newFile.path)
    if (
        File.shouldBeSkipped(existingFile = existingFile, newFile = newFile) &&
        existingFile?.indexed == true  // Already indexed
    ) return

    // Remove declarations for this file first
    existingFile?.declarationKeys?.let {
        db.declarationsDb.remove(it)
    }

    // Get declarations metadata
    val declarations = project.read {
        val list = mutableListOf<Declaration>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitDeclaration(dcl: KtDeclaration) {
                val record = analyze(dcl) {
                    analyzeDeclaration(newFile.path, dcl)
                } ?: return
                list.add(record)
                super.visitDeclaration(dcl)
            }
        })
        return@read list
    }

    // Save declarations
    db.putDeclarations(declarations)

    // Update the file timestamp, package and declaration names
    newFile.declarationKeys.addAll(declarations.map { it.id() })
    db.setFile(newFile)
}

private fun KaSession.analyzeDeclaration(path: String, dcl: KtDeclaration): Declaration? {
    val name = dcl.name ?: return null
    val startOffset = dcl.textOffset
    val endOffset = dcl.textOffset + name.length

    return when (dcl) {
        is KtNamedFunction -> {
            val parentFqName = if (dcl.parent is KtClassBody) {
                (dcl.parent.parent as? KtClassOrObject)?.fqName?.asString() ?: ""
            } else ""

            Declaration.Function(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.valueParameters.map {
                    Declaration.Function.Parameter(
                        it.nameAsSafeName.asString(),
                        it.returnType.toString()
                    )
                },
                dcl.returnType.toString(),
                parentFqName,
                dcl.receiverTypeReference?.type?.toString() ?: ""
            )
        }

        is KtClass -> {
            if (dcl is KtEnumEntry) {
                return Declaration.EnumEntry(
                    name,
                    dcl.fqName?.asString() ?: "",
                    path,
                    startOffset,
                    endOffset,
                    dcl.parentOfType<KtClass>()?.fqName?.asString() ?: ""
                )
            }

            val type = if (dcl.isEnum()) {
                Declaration.Class.Type.ENUM_CLASS
            } else if (dcl.isAnnotation()) {
                Declaration.Class.Type.ANNOTATION_CLASS
            } else if (dcl.isInterface()) {
                Declaration.Class.Type.INTERFACE
            } else if (dcl.isAbstract()) {
                Declaration.Class.Type.ABSTRACT_CLASS
            } else {
                Declaration.Class.Type.CLASS
            }

            Declaration.Class(
                name,
                type,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset
            )
        }

        is KtParameter -> {
            if (!dcl.hasValOrVar()) return null
            val constructor = dcl.parentOfType<KtPrimaryConstructor>() ?: return null
            val clazz = constructor.parentOfType<KtClass>() ?: return null

            Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.returnType.toString(),
                clazz.fqName?.asString() ?: ""
            )
        }

        is KtProperty -> {
            if (dcl.isLocal) return null
            val clazz = dcl.parentOfType<KtClass>() ?: return Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.returnType.toString(),
                ""
            )

            Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.returnType.toString(),
                clazz.fqName?.asString() ?: ""
            )
        }

//        is KtPrimaryConstructor -> {
//            val clazz = dcl.parentOfType<KtClass>() ?: return null
//            val constructorName = clazz.name ?: return null
//
//            Declaration.Constructor(
//                constructorName,
//                clazz.fqName?.asString() ?: "",
//                path,
//                startOffset,
//                endOffset,
//                dcl.valueParameters.map {
//                    Declaration.Constructor.Parameter(
//                        it.nameAsSafeName.asString(),
//                        it.returnType.toString()
//                    )
//                },
//                clazz.fqName?.asString() ?: ""
//            )
//        }
//
//        is KtObjectDeclaration -> {
//            Declaration.Class(
//                name,
//                Declaration.Class.Type.OBJECT,
//                dcl.fqName?.asString() ?: "",
//                path,
//                startOffset,
//                endOffset
//            )
//        }
//
//        is KtTypeParameter -> {
//            val parentClass = dcl.parentOfType<KtClassOrObject>()
//            val parentFunction = dcl.parentOfType<KtNamedFunction>()
//            val parentFqName = parentClass?.fqName?.asString() ?: parentFunction?.fqName?.asString() ?: ""
//
//            Declaration.TypeParameter(
//                name,
//                path,
//                startOffset,
//                endOffset,
//                parentFqName
//            )
//        }

        else -> {
            null
        }
    }
}
